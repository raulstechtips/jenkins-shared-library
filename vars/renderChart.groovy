import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Renders the chart into .pipeline/render/ for the later checks and scans.
 *
 * In the helm container it builds the chart dependencies and renders default.yaml with the default
 * values and ci-NAME-values.yaml for each charts/ci/NAME-values.yaml file. The namespace is
 * project-namespace, or project-name when that is not set. When it is done it writes
 * .pipeline/render/.complete. It does nothing when the repo has no chart.
 *
 * @param cfg the loaded config. Reads chartPresent, project-name and project-namespace.
 * @throws error (fails the build) when a helm command fails
 */
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
