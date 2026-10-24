package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmCleanInstallTest extends BasePipelineTest {
    private static final String DIGEST = 'sha256:' + ('a' * 64)
    private List calls
    private String current

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        calls = []
        current = null
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
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals('charts/Chart.yaml', args.file)
            [name: 'demo', dependencies: [
                [name: 'a', repository: 'https://charts.example.com/a'],
                [name: 'b', repository: 'oci://ghcr.io/x'],
                [name: 'c', repository: 'https://charts.example.com/c'],
                [name: 'local', repository: 'file://../local']]]
        }
        helper.registerAllowedMethod('sh', [String]) { String script -> calls << ['sh', current, script] }
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

    private void run(Map cfg, String digest = null) {
        loadScript('vars/helmCleanInstall.groovy').call(cfg, digest)
    }

    private static final List INSTALL_PREFIX = [
        "helm repo add dep0 'https://charts.example.com/a'",
        "helm repo add dep2 'https://charts.example.com/c'",
        'helm dependency build charts'
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
            "helm upgrade --install 'demo' charts -n 'apps' --wait --timeout 10m"], shCommands())
    }

    @Test
    void passesOverridesInListOrder() {
        run(cfg('helm-overrides': ['a', 'b']))
        assertEquals("helm upgrade --install 'demo' charts -n 'apps' -f 'a' -f 'b' --wait --timeout 10m", shCommands().last())
    }

    @Test
    void emptyOverridesAddNoFlags() {
        run(cfg('helm-overrides': []))
        assertFalse(shCommands().last().contains(' -f '))
    }

    @Test
    void writesTagWhenPathSetAndDigestGiven() {
        run(cfg('helm-image-tag-path': '.image.tag'), DIGEST)
        String write = "TAG='1.2.3@${DIGEST}' yq -i '.image.tag = strenv(TAG)' charts/values.yaml"
        assertEquals([UNINSTALL] + [write] + INSTALL_PREFIX + [
            "helm upgrade --install 'demo' charts -n 'apps' --wait --timeout 10m"], shCommands())
    }

    @Test
    void doesNotWriteTagWithoutDigest() {
        run(cfg('helm-image-tag-path': '.image.tag'))
        assertFalse(shCommands().any { it.contains('yq') })
    }

    @Test
    void doesNotWriteTagWhenPathEmpty() {
        run(cfg(), DIGEST)
        assertFalse(shCommands().any { it.contains('yq') })
    }

    @Test
    void rejectsMalformedDigest() {
        assertThrows(IllegalArgumentException) { run(cfg('helm-image-tag-path': '.image.tag'), 'sha256:xyz') }
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
        assertEquals("helm upgrade --install 'demo' charts -n 'apps' -f 'my values.yaml' -f 'it'\\''s;x.yaml' --wait --timeout 10m",
            shCommands().last())
    }

    @Test
    void acceptsQuotedKeyYqPath() {
        run(cfg('helm-image-tag-path': '.image["tag.name"]'), DIGEST)
        assertEquals("TAG='1.2.3@${DIGEST}' yq -i '.image[\"tag.name\"] = strenv(TAG)' charts/values.yaml".toString(),
            shCommands().find { it.contains('yq') })
    }
}
