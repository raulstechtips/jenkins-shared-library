import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Runs the manually started job that replaces the access keys of MinIO users.
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
            string(name: 'NAME', defaultValue: '', description: 'Base name of the users. The user is NAME-ENVIRONMENT-LEVEL.')
            choice(name: 'ENVIRONMENT', choices: environments,
                description: 'Infisical environment and bucket suffix.')
            choice(name: 'ACCESS', choices: access,
                description: 'Access level. BOTH means RO and RW.')
        }
        stages {
            stage('Rotate MinIO keys') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml minioAgentPodYaml()
                    }
                }
                steps {
                    minioRotateKeys(name: params.NAME, environment: params.ENVIRONMENT, access: params.ACCESS)
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
