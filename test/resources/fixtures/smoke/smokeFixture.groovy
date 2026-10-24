// Test-only fixture var: loads the library from the repo root and reads one of its resources.
@Library('jenkins-shared-library@main') _

def call(String target) {
    String registries = libraryResource('dev/raulstechtips/jenkins/registries.yaml')
    boolean inTarget = false
    String credentialsId = null
    for (String line : registries.readLines()) {
        if (!line.startsWith(' ')) {
            inTarget = (line == "${target}:")
        } else if (inTarget && line.trim().startsWith('credentials-id:')) {
            credentialsId = line.split(':', 2)[1].trim()
        }
    }
    echo "smoke: ${target} credentials-id is ${credentialsId}"
    return credentialsId
}
