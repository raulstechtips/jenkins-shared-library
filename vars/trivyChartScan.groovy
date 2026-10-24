import com.cloudbees.groovy.cps.NonCPS
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
            sh 'set +x\nprintf \'%s\' "$REG_PASS" | helm registry login ' + quote(target.host) +
                ' -u "$REG_USER" --password-stdin\nhelm pull ' + quote(target['chart-repo'] + '/' + chart) +
                ' --version ' + quote(cfg.version) + ' --untar --untardir .pipeline/pulled'
        }
        renders.each { Map render ->
            sh 'helm template ' + quote(cfg['project-name']) + ' ' + quote(pulled) + ' -n ' + quote(namespace) +
                (render.values ? ' -f ' + quote(render.values) : '') + ' > ' + quote(render.path)
        }
    }
    renders.each { Map render -> trivyConfigScan(render.path, render.label, cfg) }
}

@NonCPS
private String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'"
}
