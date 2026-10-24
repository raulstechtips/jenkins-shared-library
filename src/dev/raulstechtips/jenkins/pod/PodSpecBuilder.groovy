package dev.raulstechtips.jenkins.pod

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput

class PodSpecBuilder implements Serializable {

    @NonCPS
    String build(Map cfg, Map tools) {
        List names = ['tools']
        if (cfg.chartPresent) {
            names.add('helm')
        }
        if (cfg['pipeline-steps']['helm-test']) {
            names.add('kubeconform')
        }
        if (cfg['pipeline-steps']['trivy-scan']) {
            names.add('trivy')
        }
        if (cfg.type == 'java') {
            names.add('jdk')
        }
        List containers = names.collect { String name ->
            Map environment = [
                HOME: '/home/jenkins/agent',
                XDG_CACHE_HOME: '/home/jenkins/agent/.cache',
                HELM_CACHE_HOME: '/home/jenkins/agent/.helm/cache',
                HELM_CONFIG_HOME: '/home/jenkins/agent/.helm/config',
                HELM_DATA_HOME: '/home/jenkins/agent/.helm/data',
                TRIVY_CACHE_DIR: '/home/jenkins/agent/.cache/trivy',
                GRADLE_USER_HOME: '/home/jenkins/agent/.gradle'
            ]
            if (name == 'trivy') {
                environment.TRIVY_DB_REPOSITORY = 'public.ecr.aws/aquasecurity/trivy-db,ghcr.io/aquasecurity/trivy-db'
                environment.TRIVY_JAVA_DB_REPOSITORY = 'public.ecr.aws/aquasecurity/trivy-java-db,ghcr.io/aquasecurity/trivy-java-db'
            }
            [
                name: name,
                image: tools[name].image,
                command: ['sleep'],
                args: ['infinity'],
                securityContext: [runAsUser: 1000],
                env: environment.collect { key, value -> [name: key, value: value] },
                resources: [
                    requests: [cpu: '100m', memory: '128Mi'],
                    limits: [memory: name in ['trivy', 'jdk'] ? '2Gi' : '1Gi']
                ]
            ]
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson([
            apiVersion: 'v1',
            kind: 'Pod',
            spec: [
                automountServiceAccountToken: false,
                securityContext: [runAsNonRoot: true, runAsUser: 1000],
                containers: containers
            ]
        ]))
    }
}
