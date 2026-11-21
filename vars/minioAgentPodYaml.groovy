import dev.raulstechtips.jenkins.helpers.PodSpecBuilder

/**
 * Builds the pod spec for the MinIO jobs.
 *
 * Reads the tools.yaml library resource and passes it to PodSpecBuilder with a minimal config, so
 * the pod has only the tools container. It runs no shell command and needs no container, so the
 * pipelines call it before any agent starts. The result is merged into the generic pod template.
 *
 * @return the pod spec as JSON, which is also valid YAML
 */
String call() {
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    Map cfg = [type: 'cluster', chartPresent: false, 'pipeline-steps': [:]]
    return new PodSpecBuilder().build(cfg, tools)
}
