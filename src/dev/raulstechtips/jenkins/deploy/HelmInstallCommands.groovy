package dev.raulstechtips.jenkins.deploy

import com.cloudbees.groovy.cps.NonCPS

/** Builds the ordered shell commands for helmInstall and helmCleanInstall. Pure: no steps. */
class HelmInstallCommands implements Serializable {

    @NonCPS
    List<String> install(Map cfg, Map chart, String digest) {
        String name = required(cfg, 'project-name')
        String namespace = required(cfg, 'project-namespace')
        List<String> commands = []
        String tagPath = cfg['helm-image-tag-path'] ? cfg['helm-image-tag-path'].toString() : ''
        if (tagPath && digest) {
            if (!(digest ==~ /^sha256:[0-9a-f]{64}$/)) {
                throw new IllegalArgumentException("Invalid image digest '${digest}'")
            }
            String tag = "${cfg.version}@${digest}"
            commands << ("TAG=${shellQuote(tag)} yq -i ${shellQuote(tagPath + ' = strenv(TAG)')} charts/values.yaml".toString())
        }
        List dependencies = (chart != null && chart['dependencies']) ? (chart['dependencies'] as List) : []
        for (int i = 0; i < dependencies.size(); i++) {
            String repository = ((Map) dependencies[i])['repository']
            if (repository != null && repository.startsWith('https://')) {
                commands << ("helm repo add dep${i} ${shellQuote(repository)}".toString())
            }
        }
        commands << 'helm dependency build charts'
        StringBuilder upgrade = new StringBuilder("helm upgrade --install ${shellQuote(name)} charts -n ${shellQuote(namespace)}")
        List overrides = cfg['helm-overrides'] ? (cfg['helm-overrides'] as List) : []
        for (int i = 0; i < overrides.size(); i++) {
            upgrade.append(' -f ').append(shellQuote(overrides[i].toString()))
        }
        upgrade.append(' --wait --timeout 10m')
        commands << upgrade.toString()
        return commands
    }

    @NonCPS
    List<String> cleanInstall(Map cfg, Map chart, String digest) {
        List<String> commands = []
        commands << ("helm uninstall ${shellQuote(required(cfg, 'project-name'))} -n ${shellQuote(required(cfg, 'project-namespace'))} --wait --ignore-not-found".toString())
        commands.addAll(install(cfg, chart, digest))
        return commands
    }

    /** POSIX single-quotes one argument; each embedded single quote becomes '\\''. */
    @NonCPS
    String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    @NonCPS
    private static String required(Map cfg, String key) {
        Object value = cfg[key]
        if (value == null || value.toString().isEmpty()) {
            throw new IllegalArgumentException("${key} is required to run Helm install")
        }
        return value.toString()
    }
}
