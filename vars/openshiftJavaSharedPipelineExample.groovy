#!/usr/bin/env groovy

// TODO: the maven image below is temporary
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
  imagePullSecrets:
  - name: quay-pull-secret
  containers:
  - name: 'jnlp'
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: docker-socket
  - name: jenkins-slave-mvn
    volumeMounts:
    - mountPath: "/jenkins-maven"
      name: maven-pvc
    image: maven:3.6.1-jdk-8-alpine
    tty: true
    command:
    - cat
  - name: jenkins-slave-oc
    image: registry.redhat.io/openshift3/ose-cli
    tty: true
    comand:
    - cat
  volumes:
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
                def buildSelector = bc.startBuild('--from-dir="${WORKSPACE}"')
                //buildSelector.logs('-f')

                }
              }
            }
          }
        }
      }
    }
  }
}
