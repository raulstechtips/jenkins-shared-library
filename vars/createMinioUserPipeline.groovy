import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Runs the manually started job that creates MinIO users with access to existing buckets.
 * The steps validate every parameter before the first MinIO or Infisical call, so the first
 * build after job discovery (empty parameters) changes nothing.
 */
def call() {
    List<String> environments = new MinioNames().environments()
    List<String> access = accessChoices()
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
            choice(name: 'ACCESS', choices: access,
                description: 'Access level. BOTH means RO and RW.')
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
                    minioEnsureAccess(name: params.USER_NAME, environment: params.ENVIRONMENT, access: params.ACCESS,
                        buckets: params.BUCKETS)
                }
            }
        }
    }
}

// ACCESS choices: every level in levels.yaml in uppercase, then BOTH.
private List<String> accessChoices() {
    Map doc = readYaml(text: libraryResource('dev/raulstechtips/jenkins/minio/levels.yaml'))
    List<String> choices = []
    for (Object level : (doc.levels as List)) {
        choices.add(level.toString().toUpperCase())
    }
    choices.add('BOTH')
    return choices
}
