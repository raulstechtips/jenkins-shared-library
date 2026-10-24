/**
 * Runs the helm-unittest tests in charts/tests/.
 *
 * In the tools container it downloads the pinned helm-unittest plugin (0.3.1), checks its SHA-256
 * and removes its install hooks. In the helm container it installs the plugin and runs helm
 * unittest charts. It does nothing when the repo has no chart or charts/tests/ is missing.
 *
 * @param cfg the loaded config. Reads chartPresent.
 * @throws error (fails the build) when the download or checksum fails or a test fails
 */
def call(Map cfg) {
    if (!cfg.chartPresent) {
        return
    }
    if (!fileExists('charts/tests/')) {
        echo 'Skipping helm unittest: charts/tests/ is missing.'
        return
    }
    container('tools') {
        sh '''mkdir -p .pipeline/helm-unittest
curl -fsSL -o .pipeline/helm-unittest.tgz https://github.com/helm-unittest/helm-unittest/releases/download/v0.3.1/helm-unittest-linux-amd64-0.3.1.tgz
echo "93234ed2bdab79186105600578ae7139d7a5c648f9a94a36b01c61b38767880b  .pipeline/helm-unittest.tgz" | sha256sum -c -
tar -xzf .pipeline/helm-unittest.tgz -C .pipeline/helm-unittest
cd .pipeline/helm-unittest
sed -i '/^hooks:/,$d' plugin.yaml'''
    }
    container('helm') {
        withEnv(['HELM_PLUGINS=/home/jenkins/agent/.helm/plugins']) {
            sh 'helm plugin install .pipeline/helm-unittest'
            sh 'helm unittest charts'
        }
    }
}
