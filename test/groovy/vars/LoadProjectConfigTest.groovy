package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class LoadProjectConfigTest extends BasePipelineTest {
    private List<String> errors
    private List<String> reads
    private String yamlText
    private boolean missingConfig
    private boolean chartPresent
    private Script loader

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        errors = []
        reads = []
        yamlText = 'project-config:\n  project-name: demo\n'
        missingConfig = false
        chartPresent = false
        helper.registerAllowedMethod('readTrusted', [String]) { String path ->
            reads.add(path)
            if (path == 'project-pipeline-config.yaml') {
                if (missingConfig) {
                    throw new FileNotFoundException(path)
                }
                return yamlText
            }
            assertEquals('charts/Chart.yaml', path)
            if (!chartPresent) {
                throw new FileNotFoundException(path)
            }
            'name: demo\nversion: 1.0.0\n'
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals(['text'] as Set, args.keySet())
            assertEquals(yamlText, args.text)
            new Yaml().load(args.text as String)
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            errors.add(message)
            throw new IllegalStateException(message)
        }
        loader = loadScript('vars/loadProjectConfig.groovy')
    }

    private void useFixture(String name) {
        yamlText = getClass().getResource("/fixtures/config/${name}.yaml").text
    }

    private String rejected(String name) {
        useFixture(name)
        def ex = assertThrows(IllegalStateException) { loader.call(type: 'cluster') }
        assertEquals(1, errors.size())
        assertEquals(ex.message, errors[0])
        ex.message
    }

    @Test void readsTrustedConfigAndAddsTypeAndPresentChart() {
        chartPresent = true
        Map cfg = loader.call(type: 'cluster')
        assertEquals('cluster', cfg.type)
        assertEquals(true, cfg.chartPresent)
        assertEquals('demo', cfg['project-name'])
        assertEquals([name: 'demo', dockerfile: 'Dockerfile', context: '.'], cfg['docker-image'])
        assertEquals(['project-pipeline-config.yaml', 'charts/Chart.yaml'], reads)
        assertTrue(errors.empty)
    }
    @Test void missingChartIsFalseWithoutError() {
        Map cfg = loader.call(type: 'java')
        assertEquals('java', cfg.type)
        assertEquals(false, cfg.chartPresent)
        assertTrue(cfg.containsKey('build-command'))
        assertTrue(errors.empty)
    }
    @Test void missingConfigUsesExactE8MessageOnce() {
        missingConfig = true
        def ex = assertThrows(IllegalStateException) { loader.call(type: 'cluster') }
        assertEquals('project-pipeline-config.yaml is missing in the repo root.', ex.message)
        assertEquals([ex.message], errors)
        assertEquals(['project-pipeline-config.yaml'], reads)
    }
    @Test void allValidationErrorsReachOneErrorCallOneLineEach() {
        String message = rejected('two-errors')
        assertEquals(2, message.readLines().size())
        assertTrue(message.contains('project-config.project-name'))
        assertTrue(message.contains('project-config.pipeline-steps.helm-test'))
    }
    @Test void malformedRootProducesClearError() { assertTrue(rejected('root-list').contains('map')) }
    @Test void malformedProjectConfigProducesClearError() { assertTrue(rejected('project-list').contains('project-config')) }
    @Test void malformedPipelineStepsProducesClearError() { assertTrue(rejected('steps-string').contains('pipeline-steps')) }
    @Test void emptyFileProducesClearError() { assertTrue(rejected('empty').contains('root')) }
    @Test void yamlNullProducesClearError() { assertTrue(rejected('null-values').contains('null')) }
    @Test void quotedTrueProducesClearTypeError() { assertTrue(rejected('quoted-true').contains('boolean')) }
    @Test void malformedYamlProducesSingleParseError() {
        yamlText = 'project-config: ['
        def ex = assertThrows(IllegalStateException) { loader.call(type: 'cluster') }
        assertEquals(1, errors.size())
        assertTrue(ex.message.contains('project-pipeline-config.yaml'), ex.message)
        assertEquals(1, ex.message.readLines().size())
    }
}
