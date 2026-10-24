import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.RegistryCatalog
import dev.raulstechtips.jenkins.helpers.FindingsEvaluator
import dev.raulstechtips.jenkins.helpers.ShellQuote
import java.security.MessageDigest

/**
 * Scans the built image and the third-party images in the rendered chart for vulnerabilities.
 *
 * It uses cfg.upstreamImages when the key is set, and otherwise calls upstreamImages. The built
 * image is scanned with the credentials of the first publish target, and the other images without
 * credentials. Each scan runs trivy image --scanners vuln in the trivy container, uses
 * .trivyignore.yaml or .trivyignore when present, writes a report under .pipeline/reports, and logs
 * the count for each severity.
 *
 * @param cfg the loaded config. Reads upstreamImages, publish-targets and trivy-fail-on.
 * @param builtImageRef the full reference of the built image with its digest, or null when no image
 *     was built
 * @throws error (fails the build) when trivy fails or a finding matches trivy-fail-on
 * @throws IllegalArgumentException when a report is empty or not valid JSON
 */
def call(Map cfg, String builtImageRef) {
    List upstream = cfg.containsKey('upstreamImages') ? cfg.upstreamImages : upstreamImages(cfg)
    if (builtImageRef) {
        scanImage(builtImageRef, cfg, cfg['publish-targets'][0])
    }
    upstream.each { String ref -> scanImage(ref, cfg, null) }
}

/**
 * Lists the third-party images used by the rendered chart.
 *
 * With yq in the tools container it reads every image string from .pipeline/render/*.yaml, then
 * drops duplicates and every image that starts with an image-prefix from registries.yaml, which are
 * the project's own images.
 *
 * @param cfg the loaded config. The method does not read it.
 * @return the image references, or an empty list when nothing was rendered
 */
List upstreamImages(Map cfg) {
    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
    RegistryCatalog catalog = new RegistryCatalog(registries)
    List prefixes = registries.keySet().collect { catalog.target(it)['image-prefix'] }
    List upstream = []
    List rendered = findFiles(glob: '.pipeline/render/*.yaml').collect { it.path }
    if (!rendered.isEmpty()) {
        String images
        container('tools') {
            images = sh(script: 'yq eval-all \'.. | select(has("image")) | .image | select(tag == "!!str")\' ' +
                rendered.collect { ShellQuote.quote(it) }.join(' '), returnStdout: true)
        }
        images.readLines().each { String line ->
            String ref = line.trim()
            if (ref && ref != '---' && !prefixes.any { ref.startsWith(it) } && !upstream.contains(ref)) {
                upstream.add(ref)
            }
        }
    }
    return upstream
}

private void scanImage(String ref, Map cfg, String target) {
    String report = '.pipeline/reports/trivy-image-' + slug(ref) + '.json'
    container('trivy') {
        sh 'mkdir -p .pipeline/reports'
        String ignore = fileExists('.trivyignore.yaml') ? ' --ignorefile .trivyignore.yaml' :
            (fileExists('.trivyignore') ? ' --ignorefile .trivyignore' : '')
        String command = 'trivy image --scanners vuln --format json --output ' + ShellQuote.quote(report) +
            ' --exit-code 0' + ignore + ' ' + ShellQuote.quote(ref)
        if (target) {
            withRegistryLogin(target) {
                sh 'set +x\nTRIVY_USERNAME="$REG_USER" TRIVY_PASSWORD="$REG_PASS" ' + command
            }
        } else {
            sh command
        }
    }
    Map result = new FindingsEvaluator().evaluateTrivy(readFile(report), cfg['trivy-fail-on'])
    result.counts.each { severity, count -> echo "Trivy image ${ref} ${severity}: ${count}" }
    if (result.fail) {
        error("Trivy findings match trivy-fail-on. See ${report} in the build artifacts.")
    }
}

@NonCPS
private String slug(String ref) {
    String safe = ref.replaceAll('[^A-Za-z0-9._-]', '-').take(96)
    String hash = MessageDigest.getInstance('SHA-256').digest(ref.getBytes('UTF-8')).encodeHex().toString()
    return safe + '-' + hash
}
