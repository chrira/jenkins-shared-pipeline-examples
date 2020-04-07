#!/usr/bin/env groovy
// TODO: namepsace in pod def below is temporary
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
    image: openshift/jenkins-agent-maven-35-centos7
    env:
    - name: MAVEN_OPTS
      value: "-Duser.home=/usr/share/maven"
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
          sh 'mkdir -p /usr/share/maven'
          sh 'mvn clean package'
        }
      }
    }
  }
}
