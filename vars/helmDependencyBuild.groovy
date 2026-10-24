import dev.raulstechtips.jenkins.shell.ShellQuote

def call(Map cfg) {
    if (!cfg.chartPresent || fileExists('.pipeline/render/.complete')) {
        return
    }
    container('helm') {
        Map chart = readYaml(file: 'charts/Chart.yaml')
        (chart.dependencies ?: []).eachWithIndex { Map dependency, int index ->
            String repository = dependency.repository
            if (repository?.startsWith('https://')) {
                sh "helm repo add dep${index} ${ShellQuote.quote(repository)}"
            }
        }
        sh 'helm dependency build charts'
    }
}
