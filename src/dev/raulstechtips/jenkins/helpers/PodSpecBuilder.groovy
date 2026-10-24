package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput

/**
 * Builds the Kubernetes pod spec for the main pipeline agent from the loaded config and tools.yaml.
 */
class PodSpecBuilder implements Serializable {

    /**
     * Returns the agent pod spec as pretty-printed JSON.
     *
     * The pod always has a tools container. It adds helm when the repo has a chart, kubeconform
     * when helm-test is true, trivy when trivy-scan is true, and jdk for the java pipeline. Each
     * container runs sleep infinity as user 1000 with the same HOME and cache paths, and trivy also
     * gets the Trivy database mirrors. Memory is limited to 2Gi for trivy and jdk and to 1Gi for
     * the others. The service account token is not mounted.
     *
     * @param cfg the loaded config. Reads chartPresent, type and pipeline-steps.
     * @param tools the parsed tools.yaml, which maps each container name to a map with its image
     * @return the pod spec as JSON
     */
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
                    requests: [cpu: '10m', memory: '128Mi'],
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
