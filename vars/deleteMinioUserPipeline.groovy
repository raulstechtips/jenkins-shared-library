import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Runs the manually started job that removes MinIO users, policies, keys and Infisical folders.
 * The bucket is never removed.
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
            string(name: 'NAME', defaultValue: '', description: 'Base name of the users. The user is NAME-ENVIRONMENT-LEVEL.')
            choice(name: 'ENVIRONMENT', choices: environments,
                description: 'Infisical environment and bucket suffix.')
            booleanParam(name: 'RO', defaultValue: false, description: 'Read-only access (level ro).')
            booleanParam(name: 'RW', defaultValue: false, description: 'Read and write access (level rw).')
            string(name: 'CONFIRM_NAME', defaultValue: '', description: 'Type NAME again to confirm the removal.')
        }
        stages {
            stage('Delete MinIO user') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml minioAgentPodYaml()
                    }
                }
                steps {
                    minioDeleteAccess(name: params.NAME, environment: params.ENVIRONMENT, access: params,
                        confirmName: params.CONFIRM_NAME)
                }
            }
        }
    }
}
