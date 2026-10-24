import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Lints the chart in strict mode.
 *
 * In the helm container it runs helm lint --strict charts once with the default values and once
 * with each charts/ci/NAME-values.yaml file. It does nothing when the repo has no chart.
 *
 * @param cfg the loaded config. Reads chartPresent.
 * @throws error (fails the build) when helm lint reports a problem
 */
def call(Map cfg) {
    if (!cfg.chartPresent) {
        return
    }
    container('helm') {
        sh 'helm lint --strict charts'
        findFiles(glob: 'charts/ci/*-values.yaml').each { file ->
            sh "helm lint --strict charts -f ${ShellQuote.quote(file.path)}"
        }
    }
}
