package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/** Builds the ordered shell commands for helmInstall and helmCleanInstall. Pure: no steps. */
class HelmInstallCommands implements Serializable {

    /**
     * Returns the shell commands to run from charts/ to install or upgrade the chart.
     *
     * In order: when helm-image-tag-path is set, a yq command that writes
     * version to that path in values.yaml; a yq command that sets the build version
     * in Chart.yaml; a helm repo add for each chart dependency
     * with an https:// repository; helm dependency build .; and helm upgrade -i of
     * project-name in project-namespace, with one -f per helm-overrides entry, --wait and a 10
     * minute timeout. Every value is quoted with ShellQuote.
     *
     * @param cfg the validated config. Reads project-name, project-namespace, version,
     *     helm-image-tag-path and helm-overrides.
     * @param chart the parsed charts/Chart.yaml, or null
     * @return the commands, in the order to run them
     * @throws IllegalArgumentException when project-name or project-namespace is empty
     */
    @NonCPS
    List<String> install(Map cfg, Map chart) {
        String name = required(cfg, 'project-name')
        String namespace = required(cfg, 'project-namespace')
        List<String> commands = []
        String tagPath = cfg['helm-image-tag-path'] ? cfg['helm-image-tag-path'].toString() : ''
        if (tagPath) {
            String tag = cfg.version
            commands << ("TAG=${ShellQuote.quote(tag)} yq -i ${ShellQuote.quote(tagPath + ' = strenv(TAG)')} values.yaml".toString())
        }
        commands << ("CHART_VERSION=${ShellQuote.quote(cfg.version.toString())} yq -i '.version = strenv(CHART_VERSION)' Chart.yaml".toString())
        List dependencies = (chart != null && chart['dependencies']) ? (chart['dependencies'] as List) : []
        for (int i = 0; i < dependencies.size(); i++) {
            String repository = ((Map) dependencies[i])['repository']
            if (repository != null && repository.startsWith('https://')) {
                commands << ("helm repo add dep${i} ${ShellQuote.quote(repository)}".toString())
            }
        }
        commands << 'helm dependency build .'
        StringBuilder upgrade = new StringBuilder("helm upgrade -i ${ShellQuote.quote(name)} .")
        List overrides = cfg['helm-overrides'] ? (cfg['helm-overrides'] as List) : []
        for (int i = 0; i < overrides.size(); i++) {
            upgrade.append(' -f ').append(ShellQuote.quote(overrides[i].toString()))
        }
        upgrade.append(' -n ').append(ShellQuote.quote(namespace)).append(' --wait --timeout 10m')
        commands << upgrade.toString()
        return commands
    }

    /**
     * Returns the install commands preceded by a helm uninstall of the release, so the chart is
     * installed from scratch.
     *
     * The uninstall uses --wait and --ignore-not-found, so a missing release is not an error.
     *
     * @param cfg the validated config. Reads the same keys as install.
     * @param chart the parsed charts/Chart.yaml, or null
     * @return the commands, in the order to run them
     * @throws IllegalArgumentException when project-name or project-namespace is empty
     */
    @NonCPS
    List<String> cleanInstall(Map cfg, Map chart) {
        List<String> commands = []
        commands << ("helm uninstall ${ShellQuote.quote(required(cfg, 'project-name'))} -n ${ShellQuote.quote(required(cfg, 'project-namespace'))} --wait --ignore-not-found".toString())
        commands.addAll(install(cfg, chart))
        return commands
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
