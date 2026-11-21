import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Installs the pinned mc and infisical binaries for the MinIO jobs.
 *
 * It reads the tools.yaml resource. In the tools container it downloads mc and the infisical
 * archive, checks the SHA-256 of each downloaded file, unpacks infisical, and makes both binaries
 * executable in WORKSPACE/.minio-tools/bin. The script runs with set -eu, so a checksum mismatch
 * fails the build before any binary becomes executable.
 *
 * @return the bin folder path, WORKSPACE/.minio-tools/bin
 * @throws error (fails the build) when a tools.yaml entry is malformed, the download fails, or a
 *     checksum does not match
 */
String call() {
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    Map mc = pin(tools, 'mc')
    Map infisical = pin(tools, 'infisical')

    container('tools') {
        sh('set -eu\n' +
            'bin="${WORKSPACE}/.minio-tools/bin"\n' +
            'tmp="$(mktemp -d)"\n' +
            'trap \'rm -rf "$tmp"\' EXIT\n' +
            'mkdir -p "$bin"\n' +
            'curl -fsSL -o "$tmp/mc" ' + ShellQuote.quote(mc.url) + '\n' +
            'echo ' + ShellQuote.quote(mc.sha256 + '  ') + '"$tmp/mc" | sha256sum -c -\n' +
            'curl -fsSL -o "$tmp/infisical.tar.gz" ' + ShellQuote.quote(infisical.url) + '\n' +
            'echo ' + ShellQuote.quote(infisical.sha256 + '  ') + '"$tmp/infisical.tar.gz" | sha256sum -c -\n' +
            'tar -xzf "$tmp/infisical.tar.gz" -C "$bin" infisical\n' +
            'cp "$tmp/mc" "$bin/mc"\n' +
            'chmod +x "$bin/mc" "$bin/infisical"\n')
    }
    return "${env.WORKSPACE}/.minio-tools/bin".toString()
}

// Returns the url and sha256 of one tools.yaml entry after checking its shape.
private Map pin(Map tools, String name) {
    Object entry = tools == null ? null : tools.get(name)
    String url = entry instanceof Map ? ((Map) entry).get('url')?.toString() : null
    String sha = entry instanceof Map ? ((Map) entry).get('sha256')?.toString() : null
    if (!url) {
        error("tools.yaml must define ${name}.url".toString())
    }
    if (!matches(sha, '[0-9a-f]{64}')) {
        error("tools.yaml ${name}.sha256 must be 64 hex characters, got ${sha}".toString())
    }
    return [url: url, sha256: sha]
}

@NonCPS
private boolean matches(String value, String regex) {
    return value != null && value ==~ regex
}
