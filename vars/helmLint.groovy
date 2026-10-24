import dev.raulstechtips.jenkins.helpers.ShellQuote

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
