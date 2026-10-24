import dev.raulstechtips.jenkins.registry.RegistryCatalog

def call(Map cfg, String digest) {
    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
    def catalog = new RegistryCatalog(registries)
    if (cfg['helm-image-tag-path']) {
        container('tools') {
            withEnv(['TAG=' + cfg.version + '@' + digest,
                'YQ_EXPRESSION=' + cfg['helm-image-tag-path'] + ' = strenv(TAG)']) {
                sh 'yq -i "$YQ_EXPRESSION" charts/values.yaml'
            }
        }
    }
    container('helm') {
        String chart = readYaml(file: 'charts/Chart.yaml').name
        withEnv(['CHART_VERSION=' + cfg.version,
            'CHART_ARCHIVE=.pipeline/dist/' + chart + '-' + cfg.version + '.tgz']) {
            sh 'mkdir -p .pipeline/dist\nhelm package charts --version "$CHART_VERSION" --destination .pipeline/dist'
            cfg['publish-targets'].each { String name ->
                Map target = catalog.target(name)
                withEnv(['REG_HOST=' + target.host, 'CHART_REPO=' + target['chart-repo'],
                    'CHART_REF=' + target['chart-repo'] + '/' + chart]) {
                    withRegistryLogin(name) {
                        sh 'set +x\necho "$REG_PASS" | helm registry login "$REG_HOST" -u "$REG_USER" --password-stdin'
                        sh 'set +x\nhelm push "$CHART_ARCHIVE" "$CHART_REPO"'
                    }
                    String uploaded = sh(script: 'set +x\nhelm show chart "$CHART_REF" --version "$CHART_VERSION"',
                        returnStdout: true)
                    Map metadata = readYaml(text: uploaded)
                    if (metadata.version != cfg.version) {
                        error('Uploaded chart version does not match ' + cfg.version + ' at ' + name + '.')
                    }
                }
            }
        }
    }
}
