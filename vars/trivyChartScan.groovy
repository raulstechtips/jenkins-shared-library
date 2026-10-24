import dev.raulstechtips.jenkins.shell.ShellQuote
import dev.raulstechtips.jenkins.registry.RegistryCatalog

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
