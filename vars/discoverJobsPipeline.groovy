/**
 * Discovers operational Jenkinsfiles and creates manual jobs under the root Jobs folder.
 * Only main builds can change generated jobs. Operations are not executed by discovery.
 */
def call() {
    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            disableConcurrentBuilds()
            timeout(time: 5, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }
        stages {
            stage('Discover jobs') {
                when {
                    beforeAgent true
                    expression { env.BRANCH_NAME == 'main' && !env.CHANGE_ID && !env.TAG_NAME }
                }
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                    }
                }
                steps {
                    discoverJobs()
                }
            }
        }
    }
}
