import dev.raulstechtips.jenkins.shell.ShellQuote

def call(Map cfg) {
    if (!cfg.chartPresent) {
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
        sh 'mkdir -p .pipeline/render'
        String namespace = cfg['project-namespace'] ?: cfg['project-name']
        sh "helm template ${ShellQuote.quote(cfg['project-name'])} charts -n ${ShellQuote.quote(namespace)} > '.pipeline/render/default.yaml'"
        findFiles(glob: 'charts/ci/*-values.yaml').each { file ->
            String basename = file.name - '.yaml'
            sh "helm template ${ShellQuote.quote(cfg['project-name'])} charts -n ${ShellQuote.quote(namespace)} -f ${ShellQuote.quote(file.path)} > ${ShellQuote.quote(".pipeline/render/ci-${basename}.yaml")}"
        }
        // Workspace-local completion marker, written after this render completes.
        sh 'touch .pipeline/render/.complete'
    }
}
