import dev.raulstechtips.jenkins.helpers.RegistryCatalog

def call(String targetName, Closure body) {
    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
    Map target = new RegistryCatalog(registries).target(targetName)
    return withCredentials([usernamePassword(
        credentialsId: target['credentials-id'],
        usernameVariable: 'REG_USER',
        passwordVariable: 'REG_PASS'
    )]) {
        body.call()
    }
}
