#!/usr/bin/env groovy

def call(Map args) {
  pipeline {
    agent {
      kubernetes {
        label "jenkins-${env.BUILD_ID}"
        defaultContainer 'jenkins-slave-mvn'
        yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: 'jnlp'
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: docker-socket
  - name: jenkins-slave-mvn
    volumeMounts:
    - mountPath: "/jenkins-maven"
      name: maven-pvc
    image: openshift/jenkins-slave-maven-centos7
    tty: true
    env:
    - name: MAVEN_OPTS
      value: "-Duser.home=/usr/share/maven"
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
      stage('SETUP: Test Stage') {
        echo 'hello jenkins openshift'
      }
    }
  }
}
