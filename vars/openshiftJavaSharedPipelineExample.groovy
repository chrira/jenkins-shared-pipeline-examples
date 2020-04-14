#!/usr/bin/env groovy

def call(Map args) {
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
    - mountPath: "/home/jenkins/agent"
      name: workspace-volume
      readOnly: false
  - name: jenkins-slave-oc
    image: registry.redhat.io/openshift3/ose-cli
    tty: true
    command:
    - cat
    volumeMounts:
    - mountPath: "/home/jenkins/agent"
      name: workspace-volume
      readOnly: false
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
  - name: workspace-volume
    emptyDir: {}
"""
      }
    }
    stages {
      stage('BUILD: Build and Package Application') {
        steps {
          sh 'mvn package'
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
                  // TODO: Temporarily hardcoded
                  openshift.withProject('mgt') {
                    
                    echo "Creating Image Build Config"
                    openshift.apply(openshift.process(readFile('buildConfig.yml'), '-p', 'IMAGE_NAMESPACE=summit-team', '-p', 'IMAGE_REGISTRY_URL=quay-mgt-demo.griffinsdemos.com', '-p', 'IMAGE_TAG=dev'))
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
                // TODO: Temporarily hardcoded
                openshift.withProject('mgt') {
                // TODO: temporarily hardcoded bc name
                def bc = openshift.selector('bc/sample-rest-service')
                
                echo "Starting Image Build"
                def buildSelector = bc.startBuild('--from-dir="${WORKSPACE}"')
                //buildSelector.logs('-f')
                }
              }
            }
          }
        }
      }
      stage('DEV: Retrieve Clair Results') {
        steps {
          script {
            tagInfo = httpRequest url:"https://quay-mgt-demo.griffinsdemos.com/api/v1/repository/summit-team/sample-rest-service/tag/dev/images"
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
                vulns = httpRequest url:"https://quay-mgt-demo.griffinsdemos.com/api/v1/repository/summit-team/sample-rest-service/image/${imageId}/security?vulnerabilities=true"
                vulns = readJSON text: vulns.content  
                if(vulns.status != "scanned") {
                  return false
                }

                low=[]
                medium=[]
                high=[]
                critical=[]
                     
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
                        default:
                          echo "Should never be here"
                          currentBuild.result = "FAILURE"
                          break
                      }       
                    }
                  }
                }
                return true
              }
            }

            if(critical.size() > 0 || high.size() > 0) {
              echo "Image has ${critical.size()} critical vulnerabilities and ${high.size()} high vulnerabilities."
              currentBuild.result = "UNSTABLE"
            }
          }
        }
      }
      // TODO: There is a potential race condition here with the image build, follow logs will solve
      stage('DEV: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  // TODO: Temporarily hardcoded
                  openshift.withProject('dev') {
                    
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', 'IMAGE_NAMESPACE=summit-team', '-p', 'IMAGE_REGISTRY_URL=quay-mgt-demo.griffinsdemos.com', '-p', 'IMAGE_TAG=dev'))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', 'NAMESPACE=dev', '-p', 'SUBDOMAIN=apps.demo.griffinsdemos.com'))
                  }
                }
              }
            }
          }
        }
      }
      stage('DEV: Notification of Promotion') {
        steps {
          slackSend color: 'good', message: "${env.JOB_BASE_NAME} is deployed in dev now promoting to test"
        }
      }
      stage('TEST: Retag Image for Test') {
        steps {
          container('jenkins-slave-image-mgmt') {
            echo "Retagging image"
            sh "skopeo copy --authfile /var/run/secrets/kubernetes.io/dockerconfigjson/.dockerconfigjson docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:dev docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:test"
          }
        }
      }
      stage('TEST: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  // TODO: Temporarily hardcoded
                  openshift.withProject('test') {
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', 'IMAGE_NAMESPACE=summit-team', '-p', 'IMAGE_REGISTRY_URL=quay-mgt-demo.griffinsdemos.com', '-p', 'IMAGE_TAG=test'))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', 'NAMESPACE=test', '-p', 'SUBDOMAIN=apps.demo.griffinsdemos.com'))
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
              sh 'newman run sample-rest-service.postman_collection.json --env-var subdomain=apps.demo.griffinsdemos.com --env-var namespace=test'
            }
          }
        }
      }
      stage('TEST: Approval to Promote') {
          steps {
              slackSend color: 'good', message: "${env.JOB_BASE_NAME} is deployed in test and has passed AAT, are you ready to promote? ${env.BUILD_URL}/console"
              input 'Promote to PROD environment?'
          }
      }
      stage('PROD: Retag Image for Prod') {
        steps {
          container('jenkins-slave-image-mgmt') {
            echo "Retagging image"
            sh "skopeo copy --authfile /var/run/secrets/kubernetes.io/dockerconfigjson/.dockerconfigjson docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:test docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:prod"
          }
        }
      }
      stage('PROD: OpenShift Deploy Application') {
        steps {
          container('jenkins-slave-oc') {
            dir('openshift') {
              script {
                openshift.withCluster('openshift') {
                  // TODO: Temporarily hardcoded
                  openshift.withProject('prod') {
                    echo "Create all application resources"
                    openshift.apply(openshift.process(readFile('deploymentConfig.yml'), '-p', 'IMAGE_NAMESPACE=summit-team', '-p', 'IMAGE_REGISTRY_URL=quay-mgt-demo.griffinsdemos.com', '-p', 'IMAGE_TAG=prod'))
                    openshift.apply(openshift.process(readFile('service.yml')))
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', 'NAMESPACE=prod', '-p', 'SUBDOMAIN=apps.demo.griffinsdemos.com'))
                  }
                }
              }
            }
          }
        }
      }
      stage('PROD: Notification of Deployment') {
        steps {
          slackSend color: 'good', message: "${env.JOB_BASE_NAME} is deployed in prod"
        }
      }
    }
  }
}
