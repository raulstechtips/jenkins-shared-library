import dev.raulstechtips.jenkins.helpers.PodSpecBuilder

String call(Map cfg) {
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    return new PodSpecBuilder().build(cfg, tools)
}
