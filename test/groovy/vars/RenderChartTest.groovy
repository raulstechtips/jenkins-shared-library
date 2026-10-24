package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import static org.junit.jupiter.api.Assertions.*

class RenderChartTest extends BasePipelineTest {
    private List commands
    private List containers
    private List globs
    private List files
    private String chart
    private Map cfg

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        containers = []
        globs = []
        files = [[path: 'charts/ci/all-on-values.yaml', name: 'all-on-values.yaml'],
                 [path: 'charts/ci/minimal-values.yaml', name: 'minimal-values.yaml']]
        chart = '''name: demo
dependencies:
  - repository: https://charts.example.org
  - repository: oci://registry.example.org/charts
  - repository: https://other.example.org
'''
        cfg = [chartPresent: true, 'project-name': 'demo', 'project-namespace': 'team']
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers.add(name)
            body.call()
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals([file: 'charts/Chart.yaml'], args)
            new Yaml().load(chart)
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            globs.add(args.glob)
            files
        }
        helper.registerAllowedMethod('sh', [String]) { String command -> commands.add(command) }
    }

    @Test void rendersDefaultAndEveryCiFileWithOnlyHttpsRepositories() {
        loadScript('vars/renderChart.groovy').call(cfg)
        assertEquals(['helm'], containers)
        assertEquals(['charts/ci/*-values.yaml'], globs)
        assertEquals([
            "helm repo add dep0 'https://charts.example.org'",
            "helm repo add dep2 'https://other.example.org'",
            'helm dependency build charts',
            'mkdir -p .pipeline/render',
            "helm template 'demo' charts -n 'team' > '.pipeline/render/default.yaml'",
            "helm template 'demo' charts -n 'team' -f 'charts/ci/all-on-values.yaml' > '.pipeline/render/ci-all-on-values.yaml'",
            "helm template 'demo' charts -n 'team' -f 'charts/ci/minimal-values.yaml' > '.pipeline/render/ci-minimal-values.yaml'",
            'touch .pipeline/render/.complete'
        ], commands)
    }

    @Test void noDependenciesAndEmptyCiStillRenderDefaultWithProjectNamespaceFallback() {
        chart = 'name: demo\nversion: 1.0.0\n'
        files = []
        cfg['project-namespace'] = null
        loadScript('vars/renderChart.groovy').call(cfg)
        assertEquals(['helm'], containers)
        assertEquals(['charts/ci/*-values.yaml'], globs)
        assertEquals(['helm dependency build charts', 'mkdir -p .pipeline/render',
            "helm template 'demo' charts -n 'demo' > '.pipeline/render/default.yaml'",
            'touch .pipeline/render/.complete'], commands)
    }

    @Test void missingChartDoesNothing() {
        cfg.chartPresent = false
        loadScript('vars/renderChart.groovy').call(cfg)
        assertTrue(commands.empty)
        assertTrue(containers.empty)
        assertTrue(globs.empty)
        assertFalse(helper.callStack.any { it.methodName == 'readYaml' })
    }

    @Test void renderFailurePropagatesWithoutCompletionMarker() {
        helper.registerAllowedMethod('sh', [String]) { String command ->
            commands.add(command)
            if (command.startsWith('helm template')) {
                throw new IllegalStateException('render failed')
            }
        }
        assertThrows(IllegalStateException) { loadScript('vars/renderChart.groovy').call(cfg) }
        assertFalse(commands.contains('touch .pipeline/render/.complete'))
    }
}
