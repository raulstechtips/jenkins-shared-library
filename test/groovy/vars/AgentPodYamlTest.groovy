package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class AgentPodYamlTest extends BasePipelineTest {
    private String toolsText
    private Map tools
    private List readResources
    private List parsedResources

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        toolsText = new File('resources/dev/raulstechtips/jenkins/tools.yaml').text
        tools = new Yaml().load(toolsText)
        readResources = []
        parsedResources = []
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            readResources.add(path)
            assertEquals('dev/raulstechtips/jenkins/tools.yaml', path)
            toolsText
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            parsedResources.add(args.text)
            assertEquals(toolsText, args.text)
            tools
        }
    }

    private Map config(String type, boolean chartPresent, Map steps = [:]) {
        Map flags = [
            'helm-test': false,
            'trivy-scan': false,
            'opengrep-scan': false,
            'unit-test': false,
            'build-docker-image': false,
            'helm-package-upload': false,
            'helm-install': false,
            'helm-clean-install': false,
            'argo-deploy': false
        ]
        if (type == 'java') {
            flags.build = false
        }
        flags.putAll(steps)
        [type: type, chartPresent: chartPresent, 'pipeline-steps': flags]
    }

    private Map pod(Map cfg) {
        String yaml = loadScript('vars/agentPodYaml.groovy').call(cfg)
        assertEquals(['dev/raulstechtips/jenkins/tools.yaml'], readResources)
        assertEquals([toolsText], parsedResources)
        new JsonSlurper().parseText(yaml)
    }

    @Test
    void allFlagsClusterUsesRealPinnedToolsResource() {
        Map result = pod(config('cluster', true, [
            'helm-test': true,
            'trivy-scan': true,
            'opengrep-scan': true,
            'unit-test': true,
            'build-docker-image': true,
            'helm-package-upload': true,
            'helm-install': true,
            'helm-clean-install': true,
            'argo-deploy': true
        ]))

        assertEquals(['tools', 'helm', 'kubeconform', 'trivy'], result.spec.containers*.name)
        assertTrue(result.spec.containers.every { it.image.contains('@sha256:') })
    }

    @Test
    void javaConfigIncludesJdk() {
        Map result = pod(config('java', false))

        assertTrue(result.spec.containers*.name.contains('jdk'))
    }

    @Test
    void chartAbsentExcludesHelmContainer() {
        Map result = pod(config('cluster', false))

        assertFalse(result.spec.containers*.name.contains('helm'))
    }

    @Test
    void disablesServiceAccountTokenAndRequiresNonRootUser() {
        Map result = pod(config('cluster', false))

        assertFalse(result.spec.automountServiceAccountToken)
        assertEquals(true, result.spec.securityContext.runAsNonRoot)
        assertEquals(1000, result.spec.securityContext.runAsUser)
    }
}
