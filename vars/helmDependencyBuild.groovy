import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Downloads the chart dependencies.
 *
 * In the helm container it adds a Helm repo for each dependency with an https:// repository, then
 * runs helm dependency build charts. It does nothing when the repo has no chart or when renderChart
 * has already finished, because renderChart builds the dependencies too.
 *
 * @param cfg the loaded config. Reads chartPresent.
 * @throws error (fails the build) when a helm command fails
 */
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
