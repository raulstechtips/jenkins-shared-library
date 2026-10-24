import dev.raulstechtips.jenkins.helpers.ShellQuote
import dev.raulstechtips.jenkins.helpers.RegistryCatalog

/**
 * Scans the uploaded chart for misconfigurations.
 *
 * In the helm container it logs in to the first publish target, pulls the chart at cfg.version into
 * .pipeline/pulled, and renders it into .pipeline/render-uploaded with the default values and with
 * each charts/ci/NAME-values.yaml file. It then runs trivyConfigScan on each render.
 *
 * @param cfg the loaded config. Reads publish-targets, project-name, project-namespace, version and
 *     trivy-fail-on.
 * @throws error (fails the build) when the pull or a render fails, or a finding matches
 *     trivy-fail-on
 * @throws IllegalArgumentException when the first publish target is not in registries.yaml
 */
def call(Map cfg) {
    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
    String targetName = cfg['publish-targets'][0]
    Map target = new RegistryCatalog(registries).target(targetName)
    String chart = readYaml(text: readFile('charts/Chart.yaml')).name
    String pulled = '.pipeline/pulled/' + chart
    String namespace = cfg['project-namespace'] ?: cfg['project-name']
    List renders = [[path: '.pipeline/render-uploaded/default.yaml', label: 'uploaded-default', values: null]]
    findFiles(glob: 'charts/ci/*-values.yaml').each { file ->
        String basename = file.name.substring(0, file.name.length() - '.yaml'.length())
        renders.add([path: ".pipeline/render-uploaded/ci-${basename}.yaml",
            label: "uploaded-ci-${basename}", values: file.path])
    }
    container('helm') {
        sh 'mkdir -p .pipeline/pulled .pipeline/render-uploaded'
        withRegistryLogin(targetName) {
            sh 'set +x\nprintf \'%s\' "$REG_PASS" | helm registry login ' + ShellQuote.quote(target.host) +
                ' -u "$REG_USER" --password-stdin\nhelm pull ' + ShellQuote.quote(target['chart-repo'] + '/' + chart) +
                ' --version ' + ShellQuote.quote(cfg.version) + ' --untar --untardir .pipeline/pulled'
        }
        renders.each { Map render ->
            sh 'helm template ' + ShellQuote.quote(cfg['project-name']) + ' ' + ShellQuote.quote(pulled) + ' -n ' + ShellQuote.quote(namespace) +
                (render.values ? ' -f ' + ShellQuote.quote(render.values) : '') + ' > ' + ShellQuote.quote(render.path)
        }
    }
    renders.each { Map render -> trivyConfigScan(render.path, render.label, cfg) }
}
