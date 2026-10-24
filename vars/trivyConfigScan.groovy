import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.scan.FindingsEvaluator

def call(String target, String label, Map cfg) {
    String report = ".pipeline/reports/trivy-config-${label}.json"
    container('trivy') {
        sh 'mkdir -p .pipeline/reports'
        String ignore = fileExists('.trivyignore.yaml') ? ' --ignorefile .trivyignore.yaml' :
            (fileExists('.trivyignore') ? ' --ignorefile .trivyignore' : '')
        sh 'trivy config --format json --output ' + quote(report) + ' --exit-code 0' + ignore + ' ' + quote(target)
    }
    Map result = new FindingsEvaluator().evaluateTrivy(readFile(report), cfg['trivy-fail-on'])
    result.counts.each { severity, count -> echo "Trivy config ${label} ${severity}: ${count}" }
    if (result.fail) {
        error("Trivy findings match trivy-fail-on. See ${report} in the build artifacts.")
    }
}

@NonCPS
private String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'"
}
