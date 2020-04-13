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
          sh 'id'
          sh 'echo $PATH'
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
          echo "TODO: Retrieve Clair Results"
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
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', 'NAMESPACE=dev', '-p', 'SUBDOMAIN=griffinsdemos.com'))
                  }
                }
              }
            }
          }
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
                    openshift.apply(openshift.process(readFile('route.yml'), '-p', 'NAMESPACE=test', '-p', 'SUBDOMAIN=griffinsdemos.com'))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
