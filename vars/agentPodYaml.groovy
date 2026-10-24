import dev.raulstechtips.jenkins.helpers.PodSpecBuilder

/**
 * Builds the pod spec for the main pipeline agent.
 *
 * Reads the tools.yaml library resource and passes it, with cfg, to PodSpecBuilder. It runs no
 * shell command and needs no container, so the pipelines call it before any agent starts. The
 * result is merged into the generic pod template.
 *
 * @param cfg the loaded config. Reads chartPresent, type and pipeline-steps.
 * @return the pod spec as JSON, which is also valid YAML
 */
String call(Map cfg) {
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    return new PodSpecBuilder().build(cfg, tools)
}
