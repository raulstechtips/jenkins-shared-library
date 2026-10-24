import dev.raulstechtips.jenkins.helpers.HelmInstallCommands

def call(Map cfg, String digest = null) {
    checkout scm
    container('kubectl') {
        Map chart = readYaml(file: 'charts/Chart.yaml')
        List commands = new HelmInstallCommands().install(cfg, chart, digest)
        for (int i = 0; i < commands.size(); i++) {
            sh commands[i]
        }
    }
}
