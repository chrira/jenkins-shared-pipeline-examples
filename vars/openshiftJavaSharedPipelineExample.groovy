#!/usr/bin/env groovy

def call(Map args) {

  def serviceName = args.serviceName

  def subdomain = args.subdomain

  def mgtNamespace = 'mgt'
  def devNamespace = 'dev'
  def testNamespace = 'test'
  def prodNamespace = 'prod'

  def imageRegistryUrl = args.imageRegistryUrl

  def imageNamespace = args.imageNamespace

  def prodTag = 'latest'

  pipeline {
    agent {
      kubernetes {
        cloud 'openshift'
        defaultContainer 'jenkins-slave-mvn'
        yaml """
apiVersion: v1
kind: Pod
metadata:
  name: jenkins-${env.BUILD_ID}
spec:
  containers:
  - name: jenkins-slave-mvn
    image: registry.redhat.io/openshift4/ose-jenkins-agent-maven
    tty: true
    env:
    - name: "PATH"
      value: "/opt/rh/rh-maven35/root/usr/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    command:
    - cat
    volumeMounts:
    - mountPath: "/home/jenkins"
      name: jenkins-maven
      readOnly: false
  - name: jenkins-slave-oc
    image: registry.redhat.io/openshift3/ose-cli
    tty: true
    command:
    - cat
  - name: jenkins-slave-image-mgmt
    image: quay-mgt-demo.griffinsdemos.com/summit-team/jenkins-slave-image-mgmt
    tty: true
    command:
    - cat
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/dockerconfigjson
      name: dockerconfigjson
      readOnly: true
  - name: jenkins-slave-npm
    image: quay-mgt-demo.griffinsdemos.com/summit-team/jenkins-slave-npm
    tty: true
    command:
    - cat
  volumes:
  - name: dockerconfigjson
    secret:
      secretName: quay-pull-secret
  - name: jenkins-maven
    persistentVolumeClaim:
      claimName: jenkins-maven
"""
      }
    }
    stages {
      stage('BUILD: Build and Package Application') {
        steps {
          sh 'mvn package -Dmaven.test.skip=true'
        }
      }
      stage('DEV: SonarQube Scan') {
        steps {
          withSonarQubeEnv('sonarqube') {
            sh 'mvn sonar:sonar'
          }
        }
      }
      stage('DEV: Unit Test Application') {
        steps {
          sh 'mvn test'
        }
      }
     stage('DEV: OpenShift Create Build Config') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  openshift.withProject("${mgtNamespace}") {
                    
                    echo "Creating Image Build Config"
                    openshift.apply(openshift.process(readFile('buildConfig.yml'), '-p', "IMAGE_NAMESPACE=${imageNamespace}", '-p', "IMAGE_REGISTRY_URL=${imageRegistryUrl}", '-p', "IMAGE_TAG=${devNamespace}"))
                  }
                }
              }
            }
          }
        }
      }
     stage('DEV: OpenShift Start Source to Image Build') {
        steps {
          container('jenkins-slave-oc') {
            script {
              openshift.withCluster('openshift') {
                openshift.withProject("${mgtNamespace}") {
                def bc = openshift.selector("bc/${serviceName}")
                
                echo "Starting Image Build"
                bc.startBuild("--from-dir='${WORKSPACE}'", '--follow')
                }
              }
            }
          }
        }
      }
      stage('DEV: Retrieve Clair Results') {
        steps {
          script {

            def quayApiUrl = "https://${imageRegistryUrl}/api/v1/repository/${imageNamespace}/${serviceName}"

            withCredentials([string(credentialsId: 'quay-bearer-token', variable: 'bearerToken')]) {
              tagInfo = httpRequest url:"${quayApiUrl}/tag/${devNamespace}/images", customHeaders:[[name:'Authorization', value:"Bearer ${bearerToken}"]]
              tagInfo = readJSON text: tagInfo.content
              index_max = -1
              for( imageRef in tagInfo.images ) {
                if( imageRef.sort_index > index_max ) {
                  imageId = imageRef.id
                  index_max = imageRef.sort_index
                }
              }

              timeout(time: 5, unit: 'MINUTES') {
                waitUntil() {
                  vulns = httpRequest url:"${quayApiUrl}/image/${imageId}/security?vulnerabilities=true", customHeaders:[[name:'Authorization', value:"Bearer ${bearerToken}"]]
                  vulns = readJSON text: vulns.content  
                  if(vulns.status != "scanned") {
                    return false
                  }

                  low=[]
                  medium=[]
                  high=[]
                  critical=[]
                  negligible=[]
                  unknown=[]

                  for ( rpm in vulns.data.Layer.Features ) {
                    vulnList = rpm.Vulnerabilities
                    if(vulnList != null && vulnList.size() != 0) {
                      i = 0;
                      for(vuln in vulnList) {
                        switch(vuln.Severity) {
                          case "Low":
                            low.add(vuln)
                            break
                          case "Medium":
                            medium.add(vuln)
                            break
                          case "High":
                            high.add(vuln)
                            break
                          case "Critical":
                            critical.add(vuln)
                            break
                          case "Negligible":
                            negligible.add(vuln)
                            break
                          case "Unknown":
                            unknown.add(vuln)
                            break
                          default:
                            currentBuild.result = "FAILURE"
                            error("Issue with Clair Image Scanning")
                            break
                        }       
                      }
                    }
                  }
                  return true
                }
              }

              echo "------------------CLAIR SCAN RESULTS------------------\n" +
              "Image has ${critical.size()} critical vulnerabilities\n" +
              "Image has ${high.size()} high vulnerabilities\n" +
              "Image has ${medium.size()} medium vulnerabilities\n" +
              "Image has ${low.size()} low vulnerabilities\n" +
              "Image has ${negligible.size()} negligible vulnerabilities\n" +
              "Image has ${unknown.size()} unknown vulnerabilities" 

              // can increase/decrease threshold here
              if(critical.size() > 0 || high.size() > 0) {
                echo "Image did not meet acceptable threshold, marking UNSTABLE"
                currentBuild.result = "UNSTABLE"
              }
            }
          }
        }
      }
      stage('DEV: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  openshift.withProject("${devNamespace}") {
                    
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', "IMAGE_NAMESPACE=${imageNamespace}", '-p', "IMAGE_REGISTRY_URL=${imageRegistryUrl}", '-p', "IMAGE_TAG=${devNamespace}"))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', "NAMESPACE=${devNamespace}", '-p', "SUBDOMAIN=${subdomain}"))
                    
                    def dc = openshift.selector('dc', "${serviceName}")

                    dc.rollout().status()
                  }
                }
              }
            }
          }
        }
      }
      stage('DEV: Notification of Promotion') {
        steps {
          slackSend color: 'good', message: "${serviceName} is deployed in ${devNamespace} now promoting to ${testNamespace}"
        }
      }
      stage('TEST: Retag Image for Test') {
        steps {
          container('jenkins-slave-image-mgmt') {
            echo "Retagging image"
            sh "skopeo copy --authfile /var/run/secrets/kubernetes.io/dockerconfigjson/.dockerconfigjson docker://${imageRegistryUrl}/${imageNamespace}/${serviceName}:${devNamespace} docker://${imageRegistryUrl}/${imageNamespace}/${serviceName}:${testNamespace}"
          }
        }
      }
      stage('TEST: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  openshift.withProject("${testNamespace}") {
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', "IMAGE_NAMESPACE=${imageNamespace}", '-p', "IMAGE_REGISTRY_URL=${imageRegistryUrl}", '-p', "IMAGE_TAG=${testNamespace}"))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', "NAMESPACE=${testNamespace}", '-p', "SUBDOMAIN=${subdomain}"))

                    def dc = openshift.selector('dc', "${serviceName}")

                    dc.rollout().status()

                  }
                }
              }
            }
          }
        }
      }
      stage('TEST: Automated Acceptance Testing') {
        steps {
          container('jenkins-slave-npm') {
            dir('postman') {
              echo "Run Postman Tests"
              sh "newman run ${serviceName}.postman_collection.json --env-var subdomain=${subdomain} --env-var namespace=${testNamespace}"
            }
          }
        }
      }
      stage('TEST: Approval to Promote') {
          steps {
              slackSend color: 'good', message: "${serviceName} is deployed in ${testNamespace} and has passed AAT, are you ready to promote? ${env.BUILD_URL}/console"
              input 'Promote to PROD environment?'
          }
      }
      stage('PROD: Retag Image for Prod') {
        steps {
          container('jenkins-slave-image-mgmt') {
            echo "Retagging image"
            sh "skopeo copy --authfile /var/run/secrets/kubernetes.io/dockerconfigjson/.dockerconfigjson docker://${imageRegistryUrl}/${imageNamespace}/${serviceName}:${testNamespace} docker://${imageRegistryUrl}/${imageNamespace}/${serviceName}:${prodTag}"
          }
        }
      }
      stage('PROD: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  openshift.withProject("${prodNamespace}") {
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', "IMAGE_NAMESPACE=${imageNamespace}", '-p', "IMAGE_REGISTRY_URL=${imageRegistryUrl}", '-p', "IMAGE_TAG=${prodTag}"))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', "NAMESPACE=${prodNamespace}", '-p', "SUBDOMAIN=${subdomain}"))

                    def dc = openshift.selector('dc', "${serviceName}")

                    dc.rollout().status()

                  }
                }
              }
            }
          }
        }
      }
      stage('PROD: Notification of Deployment') {
        steps {
          slackSend color: 'good', message: "${serviceName} is deployed in ${prodNamespace}"
        }
      }
    }
  }
}
