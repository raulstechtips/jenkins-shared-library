import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.registry.RegistryCatalog
import dev.raulstechtips.jenkins.scan.FindingsEvaluator
import dev.raulstechtips.jenkins.shell.ShellQuote
import java.security.MessageDigest

def call(Map cfg, String builtImageRef) {
    List upstream = cfg.containsKey('upstreamImages') ? cfg.upstreamImages : upstreamImages(cfg)
    if (builtImageRef) {
        scanImage(builtImageRef, cfg, cfg['publish-targets'][0])
    }
    upstream.each { String ref -> scanImage(ref, cfg, null) }
}

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
