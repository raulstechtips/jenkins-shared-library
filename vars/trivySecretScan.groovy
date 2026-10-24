import dev.raulstechtips.jenkins.helpers.FindingsEvaluator

def call(Map cfg) {
    container('trivy') {
        sh 'mkdir -p .pipeline/reports'
        String ignore = fileExists('.trivyignore.yaml') ? ' --ignorefile .trivyignore.yaml' :
            (fileExists('.trivyignore') ? ' --ignorefile .trivyignore' : '')
        sh 'trivy fs --scanners secret --skip-dirs .git --skip-dirs .pipeline --format json --output .pipeline/reports/trivy-secret.json --exit-code 0' + ignore + ' .'
    }
    Map result = new FindingsEvaluator().evaluateTrivy(readFile('.pipeline/reports/trivy-secret.json'), [])
    result.counts.each { severity, count -> echo "Trivy secret ${severity}: ${count}" }
    if (result.secrets > 0) {
        error('Trivy found a secret. See .pipeline/reports/trivy-secret.json in the build artifacts.')
    }
}
