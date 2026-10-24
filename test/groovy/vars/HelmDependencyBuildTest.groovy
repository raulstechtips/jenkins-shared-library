package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmDependencyBuildTest extends BasePipelineTest {
    private List commands
    private List containers
    private boolean rendered
    private Map chart

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        containers = []
        rendered = false
        chart = [dependencies: [[repository: 'https://charts.example.org'],
            [repository: 'oci://registry.example.org/charts'], [repository: 'https://other.example.org']]]
        helper.registerAllowedMethod('fileExists', [String]) { String path ->
            assertEquals('.pipeline/render/.complete', path)
            rendered
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers.add(name)
            body.call()
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals([file: 'charts/Chart.yaml'], args)
            chart
        }
        helper.registerAllowedMethod('sh', [String]) { String command -> commands.add(command) }
    }

    @Test void buildsWithOnlyHttpsRepositoriesUsingOriginalIndices() {
        loadScript('vars/helmDependencyBuild.groovy').call([chartPresent: true])
        assertEquals(['helm'], containers)
        assertEquals(["helm repo add dep0 'https://charts.example.org'",
            "helm repo add dep2 'https://other.example.org'", 'helm dependency build charts'], commands)
    }
    @Test void buildsWithoutDependencies() {
        chart = [name: 'demo']
        loadScript('vars/helmDependencyBuild.groovy').call([chartPresent: true])
        assertEquals(['helm dependency build charts'], commands)
    }
    @Test void completedRenderMakesDependencyBuildANoop() {
        rendered = true
        loadScript('vars/helmDependencyBuild.groovy').call([chartPresent: true])
        assertTrue(commands.empty)
        assertTrue(containers.empty)
        assertFalse(helper.callStack.any { it.methodName == 'readYaml' })
    }
    @Test void missingChartDoesNothing() {
        loadScript('vars/helmDependencyBuild.groovy').call([chartPresent: false])
        assertTrue(commands.empty)
        assertTrue(containers.empty)
    }
}
