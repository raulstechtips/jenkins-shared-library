package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmLintTest extends BasePipelineTest {
    private List commands
    private List containers
    private List files

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        containers = []
        files = [[path: 'charts/ci/all-on-values.yaml'], [path: 'charts/ci/minimal-values.yaml']]
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers.add(name)
            body.call()
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            assertEquals([glob: 'charts/ci/*-values.yaml'], args)
            files
        }
        helper.registerAllowedMethod('sh', [String]) { String command -> commands.add(command) }
    }
    @Test void lintsDefaultAndEveryCiFileStrictly() {
        loadScript('vars/helmLint.groovy').call([chartPresent: true])
        assertEquals(['helm'], containers)
        assertEquals(['helm lint --strict charts',
            "helm lint --strict charts -f 'charts/ci/all-on-values.yaml'",
            "helm lint --strict charts -f 'charts/ci/minimal-values.yaml'"], commands)
    }
    @Test void emptyCiLintsOnlyDefault() {
        files = []
        loadScript('vars/helmLint.groovy').call([chartPresent: true])
        assertEquals(['helm lint --strict charts'], commands)
    }
    @Test void missingChartDoesNothing() {
        loadScript('vars/helmLint.groovy').call([chartPresent: false])
        assertTrue(commands.empty)
        assertTrue(containers.empty)
    }
}
