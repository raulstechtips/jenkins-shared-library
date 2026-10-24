import dev.raulstechtips.jenkins.helpers.ShellQuote
import dev.raulstechtips.jenkins.helpers.FindingsEvaluator

/**
 * Runs trivy config on one file or directory and fails on findings in trivy-fail-on.
 *
 * It runs in the trivy container, uses .trivyignore.yaml or .trivyignore when present, writes
 * .pipeline/reports/trivy-config-LABEL.json, and logs the count for each severity.
 *
 * @param target the file or directory to scan
 * @param label the name used in the report file and the log
 * @param cfg the loaded config. Reads trivy-fail-on.
 * @throws error (fails the build) when trivy fails or a finding matches trivy-fail-on
 * @throws IllegalArgumentException when the report is empty or not valid JSON
 */
def call(String target, String label, Map cfg) {
    String report = ".pipeline/reports/trivy-config-${label}.json"
    container('trivy') {
        sh 'mkdir -p .pipeline/reports'
        String ignore = fileExists('.trivyignore.yaml') ? ' --ignorefile .trivyignore.yaml' :
            (fileExists('.trivyignore') ? ' --ignorefile .trivyignore' : '')
        sh 'trivy config --format json --output ' + ShellQuote.quote(report) + ' --exit-code 0' + ignore + ' ' + ShellQuote.quote(target)
    }
    Map result = new FindingsEvaluator().evaluateTrivy(readFile(report), cfg['trivy-fail-on'])
    result.counts.each { severity, count -> echo "Trivy config ${label} ${severity}: ${count}" }
    if (result.fail) {
        error("Trivy findings match trivy-fail-on. See ${report} in the build artifacts.")
    }
}
