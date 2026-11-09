/**
 * Runs the manually started Infisical authentication update job on the deployer agent.
 * Resource names are configuration, not user-selectable build parameters.
 */
def call(Map targets) {
    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            disableConcurrentBuilds()
            timeout(time: 5, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }
        parameters {
            string(name: 'INFISICAL_CLIENT_ID', defaultValue: '',
                description: 'Optional replacement client ID. Leave empty to keep the current value.')
            password(name: 'INFISICAL_CLIENT_SECRET', defaultValue: '',
                description: 'Optional replacement client secret. Leave empty to keep the current value.')
            string(name: 'INFISICAL_URL', defaultValue: '',
                description: 'Optional API URL, including /api. Changes only the shared Kubernetes connection.')
        }
        stages {
            stage('Update Infisical authentication') {
                agent {
                    kubernetes {
                        inheritFrom 'deployer'
                    }
                }
                steps {
                    updateInfisicalAuth(targets)
                }
            }
        }
    }
}
