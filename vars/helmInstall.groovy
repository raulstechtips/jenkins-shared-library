import dev.raulstechtips.jenkins.helpers.HelmInstallCommands

/**
 * Installs or upgrades the chart in the project namespace and waits for it to be ready.
 *
 * It checks out the repo and enters charts/ in the kubectl container. There it reads Chart.yaml
 * and runs the HelmInstallCommands.install commands on the deployer agent.
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
            List commands = new HelmInstallCommands().install(cfg, chart)
            for (int i = 0; i < commands.size(); i++) {
                sh commands[i]
            }
        }
    }
}
