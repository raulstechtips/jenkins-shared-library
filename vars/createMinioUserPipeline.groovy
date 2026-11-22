import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Runs the manually started job that creates MinIO users with access to existing buckets.
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
            string(name: 'USER_NAME', defaultValue: '', description: 'User base name. The user is USER_NAME-ENVIRONMENT-LEVEL.')
            booleanParam(name: 'RO', defaultValue: false, description: 'Read-only access (level ro).')
            booleanParam(name: 'RW', defaultValue: false, description: 'Read and write access (level rw).')
            text(name: 'BUCKETS', defaultValue: '',
                description: 'Bucket names, separated by commas, spaces or new lines.')
            choice(name: 'ENVIRONMENT', choices: environments,
                description: 'Infisical environment and bucket suffix.')
        }
        stages {
            stage('Create MinIO user') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml minioAgentPodYaml()
                    }
                }
                steps {
                    minioCreateAccess(name: params.USER_NAME, environment: params.ENVIRONMENT, access: params,
                        buckets: params.BUCKETS)
                }
            }
        }
    }
}
