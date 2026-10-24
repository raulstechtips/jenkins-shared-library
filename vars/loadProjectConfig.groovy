import dev.raulstechtips.jenkins.config.ConfigValidator

def call(Map args) {
    String text
    try {
        text = readTrusted('project-pipeline-config.yaml')
    } catch (Exception ignored) {
        error('project-pipeline-config.yaml is missing in the repo root.')
        return
    }

    Object raw
    try {
        raw = readYaml(text: text)
    } catch (Exception ignored) {
        error('project-pipeline-config.yaml contains invalid YAML.')
        return
    }

    Map cfg
    try {
        cfg = ConfigValidator.validate(raw, args.type as String)
    } catch (IllegalArgumentException invalid) {
        error(invalid.message)
        return
    }
    cfg.type = args.type
    try {
        readTrusted('charts/Chart.yaml')
        cfg.chartPresent = true
    } catch (Exception ignored) {
        cfg.chartPresent = false
    }
    cfg
}
