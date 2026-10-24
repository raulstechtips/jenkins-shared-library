package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmUnitTestTest extends BasePipelineTest {
    private List commands
    private List containers
    private List environments
    private List logs
    private boolean testsPresent
    private String activeContainer

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        containers = []
        environments = []
        logs = []
        testsPresent = true
        helper.registerAllowedMethod('fileExists', [String]) { String path ->
            assertEquals('charts/tests/', path)
            testsPresent
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers.add(name)
            activeContainer = name
            try { body.call() } finally { activeContainer = null }
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            environments.add(values)
            body.call()
        }
        helper.registerAllowedMethod('sh', [String]) { String command ->
            commands.add([activeContainer, command])
        }
        helper.registerAllowedMethod('echo', [String]) { String message -> logs.add(message) }
    }
    @Test void installsVerifiedPluginWithoutDownloadHookAndRunsTests() {
        loadScript('vars/helmUnitTest.groovy').call([chartPresent: true])
        assertEquals(['tools', 'helm'], containers)
        assertEquals([['HELM_PLUGINS=/home/jenkins/agent/.helm/plugins']], environments)
        assertEquals([
            ['tools', '''mkdir -p .pipeline/helm-unittest
curl -fsSL -o .pipeline/helm-unittest.tgz https://github.com/helm-unittest/helm-unittest/releases/download/v0.3.1/helm-unittest-linux-amd64-0.3.1.tgz
echo "93234ed2bdab79186105600578ae7139d7a5c648f9a94a36b01c61b38767880b  .pipeline/helm-unittest.tgz" | sha256sum -c -
tar -xzf .pipeline/helm-unittest.tgz -C .pipeline/helm-unittest
cd .pipeline/helm-unittest
sed -i '/^hooks:/,$d' plugin.yaml'''],
            ['helm', 'helm plugin install .pipeline/helm-unittest'],
            ['helm', 'helm unittest charts']
        ], commands)
    }
    @Test void missingTestsSkipsWithLogLine() {
        testsPresent = false
        loadScript('vars/helmUnitTest.groovy').call([chartPresent: true])
        assertEquals(1, logs.size())
        assertTrue(logs[0].contains('charts/tests/'))
        assertTrue(commands.empty)
        assertTrue(containers.empty)
    }
    @Test void missingChartDoesNothing() {
        loadScript('vars/helmUnitTest.groovy').call([chartPresent: false])
        assertTrue(commands.empty)
        assertTrue(containers.empty)
        assertTrue(logs.empty)
    }
    @Test void failedVerificationCannotInstallOrRunPlugin() {
        helper.registerAllowedMethod('sh', [String]) { String command ->
            throw new IllegalStateException('checksum failed')
        }
        assertThrows(IllegalStateException) {
            loadScript('vars/helmUnitTest.groovy').call([chartPresent: true])
        }
        assertEquals(['tools'], containers)
    }
}
