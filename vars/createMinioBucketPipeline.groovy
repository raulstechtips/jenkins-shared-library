import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Runs the manually started job that creates bucket NAME-ENVIRONMENT with the checked access levels.
 * The steps validate every parameter before the first MinIO or Infisical call, so the first
 * build after job discovery (empty parameters) changes nothing.
 */
def call() {
    List<String> environments = new MinioNames().environments()
    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            disableConcurrentBuilds()
            timeout(time: 10, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }
        parameters {
            string(name: 'NAME', defaultValue: '', description: 'Bucket base name. The bucket is NAME-ENVIRONMENT.')
            choice(name: 'ENVIRONMENT', choices: environments,
                description: 'Infisical environment and bucket suffix.')
            booleanParam(name: 'RO', defaultValue: false, description: 'Read-only access (level ro).')
            booleanParam(name: 'RW', defaultValue: false, description: 'Read and write access (level rw).')
        }
        stages {
            stage('Create MinIO bucket') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml minioAgentPodYaml()
                    }
                }
                steps {
                    minioCreateBucket(name: params.NAME, environment: params.ENVIRONMENT, access: params)
                }
            }
        }
    }
}
