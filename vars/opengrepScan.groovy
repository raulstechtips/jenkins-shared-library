import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.FindingsEvaluator
import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Runs an opengrep scan with the pinned rules for each language found in the repo.
 *
 * It reads the opengrep-rules.yaml and tools.yaml resources and picks the rule folders whose globs
 * match a file outside .git, .pipeline and chart templates. When none match, it logs that and
 * returns. In the tools container it downloads the pinned opengrep binary, checks its SHA-256, and
 * fetches the rules repo at the pinned commit. In the shell container it scans the repo, excluding
 * charts and .pipeline, into .pipeline/reports/opengrep.json. It logs the ERROR, WARNING and INFO
 * counts. When opengrep-fail-on is empty it only logs a warning.
 *
 * @param cfg the loaded config. Reads opengrep-fail-on.
 * @throws error (fails the build) when a resource is malformed, the download or checksum fails, the
 *     scan fails, or a finding matches opengrep-fail-on
 */
def call(Map cfg) {
    Map rules = readYaml(text: libraryResource('dev/raulstechtips/jenkins/opengrep-rules.yaml'))
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    Map pins = validate(rules, tools)

    List folders = []
    Map languages = (Map) rules.get('languages')
    for (Object folder : languages.keySet()) {
        if (detected(folder.toString(), (List) languages.get(folder))) {
            folders.add(folder.toString())
        }
    }
    if (folders.isEmpty()) {
        echo 'opengrep: no supported language found, skipping scan'
        return
    }
    echo "opengrep: scanning rule folders ${folders.join(', ')}".toString()

    String dir = '.pipeline/opengrep-rules'
    String bin = '.pipeline/bin/opengrep'
    container('tools') {
        sh('set -eu\n' +
            'mkdir -p .pipeline/bin .pipeline/reports\n' +
            'curl -fsSL -o ' + ShellQuote.quote(bin) + ' ' + ShellQuote.quote(pins.url) + '\n' +
            'echo ' + ShellQuote.quote(pins.sha256 + '  ' + bin) + ' | sha256sum -c -\n' +
            'chmod +x ' + ShellQuote.quote(bin) + '\n' +
            'rm -rf ' + ShellQuote.quote(dir) + '\n' +
            'git init ' + ShellQuote.quote(dir) + '\n' +
            'git -C ' + ShellQuote.quote(dir) + ' fetch --depth 1 ' + ShellQuote.quote(pins.repo) + ' ' + ShellQuote.quote(pins.commit) + '\n' +
            'git -C ' + ShellQuote.quote(dir) + ' checkout FETCH_HEAD\n')
    }

    String configs = ''
    for (String folder : folders) {
        configs += ' --config ' + ShellQuote.quote(dir + '/' + folder)
    }
    container('shell') {
        sh('set -eu\n' +
            ShellQuote.quote(bin) + ' scan' + configs +
            ' --json --output .pipeline/reports/opengrep.json --exclude charts --exclude .pipeline .\n')
    }

    List failOn = cfg['opengrep-fail-on'] as List
    Map result = new FindingsEvaluator().evaluateOpengrep(readFile('.pipeline/reports/opengrep.json'), failOn)
    Map counts = (Map) result.get('counts')
    for (String severity : ['ERROR', 'WARNING', 'INFO']) {
        echo "opengrep ${severity} findings: ${counts.get(severity)}".toString()
    }
    if (failOn == null || failOn.isEmpty()) {
        echo '[WARNING] opengrep-fail-on is empty; findings are reported but do not fail the build'
    } else if (result.get('fail')) {
        error("opengrep findings match opengrep-fail-on ${failOn}: ERROR=${counts.get('ERROR')} WARNING=${counts.get('WARNING')}".toString())
    }
}

private boolean detected(String folder, List globs) {
    for (Object glob : globs) {
        def found = findFiles(glob: glob.toString(), excludes: '.git/**,.pipeline/**,charts/**/templates/**')
        if (found != null && found.length > 0) {
            return true
        }
    }
    return false
}

// Returns the pinned download and rules values after checking the shape of both resources.
private Map validate(Map rules, Map tools) {
    Object languages = rules == null ? null : rules.get('languages')
    if (!(languages instanceof Map) || ((Map) languages).isEmpty()) {
        error('opengrep-rules.yaml must define a non-empty languages map')
    }
    for (Object folder : ((Map) languages).keySet()) {
        Object globs = ((Map) languages).get(folder)
        if (!(globs instanceof List) || ((List) globs).isEmpty()) {
            error("opengrep-rules.yaml language folder ${folder} must list at least one glob".toString())
        }
    }
    String repo = rules.get('repo')?.toString()
    String commit = rules.get('commit')?.toString()
    if (!repo) {
        error('opengrep-rules.yaml must define repo')
    }
    if (!matches(commit, '[0-9a-f]{40}')) {
        error("opengrep-rules.yaml commit must be a 40-hex SHA, got ${commit}".toString())
    }
    Object og = tools == null ? null : tools.get('opengrep')
    String url = og instanceof Map ? ((Map) og).get('url')?.toString() : null
    String sha = og instanceof Map ? ((Map) og).get('sha256')?.toString() : null
    if (!url) {
        error('tools.yaml must define opengrep.url')
    }
    if (!matches(sha, '[0-9a-f]{64}')) {
        error("tools.yaml opengrep.sha256 must be 64 hex characters, got ${sha}".toString())
    }
    return [repo: repo, commit: commit, url: url, sha256: sha]
}

@NonCPS
private boolean matches(String value, String regex) {
    return value != null && value ==~ regex
}
