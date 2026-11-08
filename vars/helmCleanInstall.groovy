import dev.raulstechtips.jenkins.helpers.HelmInstallCommands

/**
 * Uninstalls the release and installs the chart again, so the branch build starts from a clean
 * release.
 *
 * It checks out the repo and enters charts/ in the kubectl container. There it reads Chart.yaml
 * and runs the HelmInstallCommands.cleanInstall commands on the deployer agent.
 *
 * @param cfg the loaded config. Reads project-name, project-namespace, version, helm-image-tag-path
 *     and helm-overrides.
 * @throws IllegalArgumentException when project-name or project-namespace is missing
 * @throws error (fails the build) when a helm or yq command fails
 */
def call(Map cfg) {
    checkout scm
    container('kubectl') {
        dir('charts') {
            Map chart = readYaml(file: 'Chart.yaml')
            List commands = new HelmInstallCommands().cleanInstall(cfg, chart)
            for (int i = 0; i < commands.size(); i++) {
                sh commands[i]
            }
        }
    }
}
