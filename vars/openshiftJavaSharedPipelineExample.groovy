#!/usr/bin/env groovy

// TODO: the maven image below is temporary
// TODO: Secret mount is temporary
def call(Map args) {
  pipeline {
    agent {
      kubernetes {
        defaultContainer 'jenkins-slave-mvn'
        yaml """
apiVersion: v1
kind: Pod
metadata:
  name: jenkins-${env.BUILD_ID}
spec:
  serviceAccountName: jenkins
  containers:
  - name: 'jnlp'
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: docker-socket
  - name: jenkins-slave-mvn
    volumeMounts:
    - mountPath: "/jenkins-maven"
      name: maven-pvc
    - mountPath: /var/run/secrets/kubernetes.io/dockerconfigjson
      name: dockerconfigjson
      readOnly: true
    image: maven:3.6.1-jdk-8-alpine
    tty: true
    command:
    - cat
  - name: jenkins-slave-oc
    image: openshift3/ose-cli
    tty: true
    comand:
    - cat
  volumes:
  - name: dockerconfigjson
    secret:
      secretName: quay-pull-secret
  - name: docker-socket
    hostPath:
      path: /var/run/docker.sock
  - name: maven-pvc
    persistentVolumeClaim:
      claimName: jenkins-maven
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

          echo "Retagging image"

          sh "skopeo copy --authfile /var/run/secrets/kubernetes.io/dockerconfigjson/.dockerconfigjson docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:dev docker://quay-mgt-demo.griffinsdemos.com/summit-team/sample-rest-service:test"
        }
      }
    }
  }
}
