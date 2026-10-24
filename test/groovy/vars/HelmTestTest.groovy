package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmTestTest extends BasePipelineTest {
    private List calls
    private Map cfg

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        calls = []
        cfg = [chartPresent: true, 'project-name': 'demo']
        ['helmDependencyBuild', 'helmLint', 'helmUnitTest', 'kubeconformCheck'].each { String step ->
            helper.registerAllowedMethod(step, [Map]) { Map actual ->
                assertSame(cfg, actual)
                calls.add(step)
            }
        }
    }
    @Test void callsAllFourStepsInOrder() {
        loadScript('vars/helmTest.groovy').call(cfg)
        assertEquals(['helmDependencyBuild', 'helmLint', 'helmUnitTest', 'kubeconformCheck'], calls)
    }
    @Test void missingChartSkipsBundle() {
        cfg.chartPresent = false
        loadScript('vars/helmTest.groovy').call(cfg)
        assertTrue(calls.empty)
    }
    @Test void failingStepStopsBundle() {
        helper.registerAllowedMethod('helmLint', [Map]) { Map actual ->
            throw new IllegalStateException('lint failed')
        }
        assertThrows(IllegalStateException) { loadScript('vars/helmTest.groovy').call(cfg) }
        assertEquals(['helmDependencyBuild'], calls)
    }
}
