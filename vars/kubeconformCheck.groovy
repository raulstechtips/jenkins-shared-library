import dev.raulstechtips.jenkins.shell.ShellQuote

def call(Map cfg) {
    if (!cfg.chartPresent) {
        return
    }
    container('kubeconform') {
        // The pinned image ships the binary at /kubeconform; it is not on PATH.
        findFiles(glob: '.pipeline/render/*.yaml').each { file ->
            sh "/kubeconform -strict -summary -schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -skip InfisicalSecret,InfisicalStaticSecret,InfisicalPushSecret,apiextensions.k8s.io/v1/CustomResourceDefinition ${ShellQuote.quote(file.path)}"
        }
    }
}
