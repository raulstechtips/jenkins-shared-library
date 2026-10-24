import dev.raulstechtips.jenkins.helpers.ConfigValidator

/**
 * Reads and validates project-pipeline-config.yaml from the repo root.
 *
 * It reads the file with readTrusted, parses it with readYaml and validates it with
 * ConfigValidator. It then sets type, and sets chartPresent to true when charts/Chart.yaml can be
 * read with readTrusted. It needs no container, so the pipelines call it before any agent starts.
 *
 * @param args a map with type, 'cluster' or 'java'
 * @return the validated config, with type and chartPresent added
 * @throws error (fails the build) when the file is missing, is not valid YAML, or fails validation.
 *     A validation error lists every problem.
 */
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
