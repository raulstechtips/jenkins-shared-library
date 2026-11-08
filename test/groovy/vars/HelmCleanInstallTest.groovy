package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmCleanInstallTest extends BasePipelineTest {
    private List calls
    private String current
    private String directory

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        calls = []
        current = null
        directory = null
        binding.setVariable('scm', [:])
        helper.registerAllowedMethod('checkout', [Map]) { Map args -> calls << ['checkout', current, args] }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            String previous = current
            current = name
            try {
                body.call()
            } finally {
                current = previous
            }
        }
        helper.registerAllowedMethod('dir', [String, Closure]) { String path, Closure body ->
            assertEquals('kubectl', current)
            assertEquals('charts', path)
            String previous = directory
            directory = path
            try {
                body.call()
            } finally {
                directory = previous
            }
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals('charts', directory)
            assertEquals('Chart.yaml', args.file)
            [name: 'demo', dependencies: [
                [name: 'a', repository: 'https://charts.example.com/a'],
                [name: 'b', repository: 'oci://ghcr.io/x'],
                [name: 'c', repository: 'https://charts.example.com/c'],
                [name: 'local', repository: 'file://../local']]]
        }
        helper.registerAllowedMethod('sh', [String]) { String script ->
            assertEquals('charts', directory)
            calls << ['sh', current, script]
        }
    }

    private Map cfg(Map extra = [:]) {
        [
            'project-name': 'demo',
            'project-namespace': 'apps',
            'helm-overrides': [],
            'helm-image-tag-path': '',
            version: '1.2.3'
        ] + extra
    }

    private List shCommands() {
        calls.findAll { it[0] == 'sh' }.collect { it[2] }
    }

    private void run(Map cfg) {
        loadScript('vars/helmCleanInstall.groovy').call(cfg)
    }

    private static final List INSTALL_PREFIX = [
        "CHART_VERSION='1.2.3' yq -i '.version = strenv(CHART_VERSION)' Chart.yaml",
        "helm repo add dep0 'https://charts.example.com/a'",
        "helm repo add dep2 'https://charts.example.com/c'",
        'helm dependency build .'
    ]
    private static final String UNINSTALL = "helm uninstall 'demo' -n 'apps' --wait --ignore-not-found"

    @Test
    void checksOutThenRunsEverythingInKubectlContainer() {
        run(cfg())
        assertEquals(['checkout', null], calls[0][0..1])
        assertTrue(calls.findAll { it[0] == 'sh' }.every { it[1] == 'kubectl' })
        assertFalse(shCommands().empty)
    }

    @Test
    void addsHttpsDependencyReposBuildsDependenciesAndUpgrades() {
        run(cfg())
        assertEquals([UNINSTALL] + INSTALL_PREFIX + [
            "helm upgrade -i 'demo' . -n 'apps' --wait --timeout 10m"], shCommands())
    }

    @Test
    void passesOverridesInListOrder() {
        run(cfg('helm-overrides': ['a', 'b']))
        assertEquals("helm upgrade -i 'demo' . -f 'a' -f 'b' -n 'apps' --wait --timeout 10m", shCommands().last())
    }

    @Test
    void emptyOverridesAddNoFlags() {
        run(cfg('helm-overrides': []))
        assertFalse(shCommands().last().contains(' -f '))
    }

    @Test
    void writesCleanTagWhenPathSet() {
        run(cfg('helm-image-tag-path': '.image.tag'))
        String write = "TAG='1.2.3' yq -i '.image.tag = strenv(TAG)' values.yaml"
        assertEquals([UNINSTALL] + [write] + INSTALL_PREFIX + [
            "helm upgrade -i 'demo' . -n 'apps' --wait --timeout 10m"], shCommands())
    }

    @Test
    void doesNotWriteTagWhenPathEmpty() {
        run(cfg())
        assertFalse(shCommands().any { it.contains('values.yaml') })
    }

    @Test
    void missingNamespaceFailsBeforeAnyHelmCommand() {
        Map bad = cfg()
        bad['project-namespace'] = null
        assertThrows(IllegalArgumentException) { run(bad) }
        assertTrue(shCommands().findAll { it.startsWith('helm') }.empty)
    }

    @Test
    void uninstallRunsFirstBeforeAnyOtherCommand() {
        run(cfg())
        assertEquals(UNINSTALL, shCommands().first())
    }

    @Test
    void keepsOverrideWithSpaceAsOneQuotedArgument() {
        run(cfg('helm-overrides': ['my values.yaml', "it's;x.yaml"]))
        assertEquals("helm upgrade -i 'demo' . -f 'my values.yaml' -f 'it'\\''s;x.yaml' -n 'apps' --wait --timeout 10m",
            shCommands().last())
    }

    @Test
    void acceptsQuotedKeyYqPath() {
        run(cfg('helm-image-tag-path': '.image["tag.name"]'))
        assertEquals("TAG='1.2.3' yq -i '.image[\"tag.name\"] = strenv(TAG)' values.yaml".toString(),
            shCommands().find { it.contains('yq') })
    }
}
