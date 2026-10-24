package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import static org.junit.jupiter.api.Assertions.*

abstract class TrivyTestSupport extends BasePipelineTest {
    Map cfg
    List<Map> shells
    List<String> messages
    List<String> reads
    List<Map> searches
    Map<String, List<String>> files
    Set<String> existing
    List<List> engineCalls
    List<String> logins
    String activeContainer
    boolean credentialsActive
    String reportFixture
    String renderFixture
    boolean shellFailure
    @TempDir
    Path shellWorkspace

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        cfg = ['project-name': 'demo', 'project-namespace': null, version: '1.2.3',
            'publish-targets': ['ghcr'], 'docker-image': [name: 'demo'],
            'trivy-fail-on': [], chartPresent: true]
        shells = []
        messages = []
        reads = []
        searches = []
        files = [:]
        existing = [] as Set
        engineCalls = []
        logins = []
        activeContainer = null
        credentialsActive = false
        reportFixture = 'trivy-config'
        renderFixture = 'render.yaml'
        shellFailure = false
        binding.setVariable('env', [REG_USER: 'robot$test-only', REG_PASS: 'test-only-password'])
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            String previous = activeContainer
            activeContainer = name
            try { body.call() } finally { activeContainer = previous }
        }
        helper.registerAllowedMethod('echo', [String]) { String message -> messages.add(message) }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalStateException(message)
        }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> existing.contains(path) }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            searches.add(args)
            (files[args.glob] ?: []).collect { [path: it, name: it.tokenize('/').last()] }
        }
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            new File('resources/' + path).text
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> new Yaml().load(args.text) }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            reads.add(path)
            if (path == 'charts/Chart.yaml') {
                return 'name: demo-chart\nversion: 1.0.0\n'
            }
            assertTrue(path.startsWith('.pipeline/reports/'), path)
            new File("test/resources/fixtures/scan/${reportFixture}.json").text
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List args, Closure body ->
            assertEquals('REG_USER', args[0].usernameVariable)
            assertEquals('REG_PASS', args[0].passwordVariable)
            logins.add(args[0].credentialsId)
            credentialsActive = true
            try { body.call() } finally { credentialsActive = false }
        }
        helper.registerAllowedMethod('withRegistryLogin', [String, Closure]) { String target, Closure body ->
            loadScript('vars/withRegistryLogin.groovy').call(target, body)
        }
        helper.registerAllowedMethod('trivyConfigScan', [String, String, Map]) {
            String target, String label, Map config ->
                engineCalls.add([target, label, config])
                loadScript('vars/trivyConfigScan.groovy').call(target, label, config)
        }
        helper.registerAllowedMethod('sh', [String]) { String script -> this.runShell(script) }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            this.runShell(args.script.toString())
            assertTrue(args.returnStdout)
            List values = []
            new Yaml().loadAll(new File('test/resources/fixtures/scan/trivy-vars/' + renderFixture).text).each {
                this.collectImages(it, values)
            }
            values.join('\n') + '\n---\n'
        }
    }

    protected void collectImages(Object node, List values) {
        if (node instanceof Map) {
            if (node.image instanceof String) { values.add(node.image) }
            node.values().each { collectImages(it, values) }
        } else if (node instanceof List) {
            node.each { collectImages(it, values) }
        }
    }

    protected String runShell(String script) {
        assertFalse(script.contains('robot$test-only'))
        assertFalse(script.contains('test-only-password'))
        if (credentialsActive) { assertTrue(script.startsWith('set +x\n')) }
        shells.add([script: script, container: activeContainer, credentials: credentialsActive])
        if (shellFailure && script.contains('trivy ')) {
            throw new IllegalStateException('scanner unavailable')
        }
        'Scan completed successfully'
    }

    List<String> shellArguments(String command) {
        Process process = new ProcessBuilder('/bin/sh', '-c',
            'trivy() { printf \'%s\\0\' "$@"; }\n' + command)
            .directory(shellWorkspace.toFile()).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), 'argument-only shell did not exit')
        assertEquals(0, process.exitValue(), process.errorStream.getText('UTF-8'))
        assertEquals([], shellWorkspace.toFile().listFiles().toList(), 'shell evaluated the hostile payload')
        return process.inputStream.getText('UTF-8').split('\u0000').toList()
    }

    List<Map> scans(String kind) {
        shells.findAll { it.script.contains('trivy ' + kind + ' ') }
    }

    void assertSummary(int reports = 1) {
        assertEquals(reports * 5, messages.size())
        ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].each { severity ->
            assertEquals(reports, messages.count { it.contains(severity + ': ') })
        }
    }

    void assertScanCommands(String kind, int expected = 1) {
        assertEquals(expected, scans(kind).size())
        scans(kind).each {
            assertEquals('trivy', it.container)
            assertTrue(it.script.contains('--exit-code 0'))
            assertFalse(it.script.contains('--exit-code 1'))
            assertTrue(it.script.contains('--format json --output '))
            assertTrue(it.script.contains('.pipeline/reports/trivy-'))
        }
    }

    void assertIgnore(String kind, boolean yaml, boolean plain) {
        scans(kind).each {
            if (yaml) {
                assertTrue(it.script.contains('--ignorefile .trivyignore.yaml'))
                assertFalse(it.script.contains('--ignorefile .trivyignore '))
            } else if (plain) {
                assertTrue(it.script.contains('--ignorefile .trivyignore '))
            } else {
                assertFalse(it.script.contains('--ignorefile'))
            }
        }
    }

    void setIgnore(boolean yaml, boolean plain) {
        if (yaml) { existing.add('.trivyignore.yaml') }
        if (plain) { existing.add('.trivyignore') }
    }
}
