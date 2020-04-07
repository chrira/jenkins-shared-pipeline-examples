#!/usr/bin/env groovy
// TODO: namepsace in pod def below is temporary
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
  namespace: mgt
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
    image: maven:3.6.1-jdk-8-alpine
    tty: true
    command:
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
          sh 'mvn clean package'
        }
      }
    }
  }
}
