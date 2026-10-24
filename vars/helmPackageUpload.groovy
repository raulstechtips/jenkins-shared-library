import dev.raulstechtips.jenkins.helpers.RegistryCatalog

/**
 * Packages the chart with the build version and pushes it to every publish target.
 *
 * When helm-image-tag-path is set, it first writes VERSION@DIGEST to that path in
 * charts/values.yaml with yq in the tools container. In the helm container it runs helm package
 * into .pipeline/dist. For each target it logs in to the registry, runs helm push, and reads the
 * chart back with helm show chart to check its version.
 *
 * @param cfg the loaded config. Reads publish-targets, version and helm-image-tag-path.
 * @param digest the built image digest. Used only when helm-image-tag-path is set.
 * @throws error (fails the build) when the uploaded chart version is not cfg.version or a helm
 *     command fails
 * @throws IllegalArgumentException when a publish target is not in registries.yaml
 */
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
