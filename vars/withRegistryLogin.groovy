import dev.raulstechtips.jenkins.helpers.RegistryCatalog

/**
 * Runs body with a registry target's username and password bound to REG_USER and REG_PASS.
 *
 * It looks up the target's credentials-id in registries.yaml and uses withCredentials. It has no
 * container of its own, so body runs where the caller is.
 *
 * @param targetName the publish target, for example ghcr or harbor
 * @param body the code to run with the credentials bound
 * @return the value body returns
 * @throws IllegalArgumentException when targetName is not in registries.yaml
 */
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
