import dev.raulstechtips.jenkins.helpers.FindingsEvaluator

/**
 * Scans the workspace for secrets with trivy fs and fails on any secret.
 *
 * It runs in the trivy container, skips .git and .pipeline, uses .trivyignore.yaml or .trivyignore
 * when present, writes .pipeline/reports/trivy-secret.json, and logs the count for each severity.
 * trivy-fail-on does not apply: any secret fails the build.
 *
 * @param cfg the loaded config. It is not read.
 * @throws error (fails the build) when trivy fails or a secret is found
 */
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
