package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class MinioAgentPodYamlTest extends BasePipelineTest {
    private String toolsText
    private Map tools
    private List readResources

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        toolsText = new File('resources/dev/raulstechtips/jenkins/tools.yaml').text
        tools = new Yaml().load(toolsText)
        readResources = []
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            readResources.add(path)
            toolsText
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals(toolsText, args.text)
            tools
        }
    }

    private Map pod() {
        String yaml = loadScript('vars/minioAgentPodYaml.groovy').call()
        assertEquals(['dev/raulstechtips/jenkins/tools.yaml'], readResources)
        new JsonSlurper().parseText(yaml)
    }

    @Test
    void podHasOnlyThePinnedToolsContainer() {
        Map result = pod()

        assertEquals(['tools'], result.spec.containers*.name)
        assertEquals(tools.tools.image, result.spec.containers[0].image)
        assertTrue(result.spec.containers[0].image ==~ /.+:[^@]+@sha256:[0-9a-f]{64}/)
    }

    @Test
    void disablesServiceAccountTokenAndRequiresNonRootUser() {
        Map result = pod()

        assertFalse(result.spec.automountServiceAccountToken)
        assertEquals(true, result.spec.securityContext.runAsNonRoot)
        assertEquals(1000, result.spec.securityContext.runAsUser)
    }
}
