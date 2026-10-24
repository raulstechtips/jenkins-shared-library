import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Validates every rendered manifest in .pipeline/render/ with kubeconform in strict mode.
 *
 * It runs in the kubeconform container and uses the default schemas plus the datree CRDs catalog.
 * It skips the Infisical secret kinds and CustomResourceDefinition objects. It expects renderChart
 * to have run, and does nothing when the repo has no chart.
 *
 * @param cfg the loaded config. Reads chartPresent.
 * @throws error (fails the build) when a manifest is invalid
 */
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
