package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class HarnessOrderTest extends DeclarativePipelineTest {

    private DeclarativePipeline declaration

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        // KubernetesAgentDeclaration already implements inheritFrom, yaml and
        // yamlMergeStrategy(String); only the plugin's merge() symbol is missing.
        helper.registerAllowedMethod('merge', [], { -> 'merge' })
        // JPU's default error only marks FAILURE and returns; Jenkins error
        // aborts execution. Preserve both effects for the pre-pipeline proof.
        helper.registerAllowedMethod('error', [String], { String message ->
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException(message)
        })
        // Same create/execute sequence as JPU 1.29's pipelineInterceptor, retaining
        // the declaration for inspection without evaluating any closure twice.
        helper.registerAllowedMethod('pipeline', [Closure], { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        })
    }

    @Test
    void agentReceivesConfigComputedBeforePipeline() {
        loadScript('test/resources/fixtures/harness/harnessOrderFixture.groovy').call()

        def agent = declaration.stages['Pipeline'].agent.kubernetes
        assertEquals('none', declaration.agent.label)
        assertEquals('generic', agent.inheritFrom)
        assertEquals('POD-FROM-CONFIG', agent.yaml)
        assertEquals('merge', agent.yamlMergeStrategy)
        assertNull(agent.defaultContainer)
        assertTrue(echoes().any { it.startsWith('Executing on agent ') && it.contains('POD-FROM-CONFIG') })
        assertJobStatusSuccess()
    }

    @Test
    void laterWhenSeesMutationFromCheckoutScript() {
        loadScript('test/resources/fixtures/harness/harnessOrderFixture.groovy').call()

        assertEquals(['Pipeline', 'Checkout', 'Second'], enteredStages())
        assertTrue(echoes().contains('SECOND-SAW-CHECKOUT'))
        assertJobStatusSuccess()
    }

    @Test
    void falseWhenSkipsBuilderAgentAndStage() {
        loadScript('test/resources/fixtures/harness/harnessOrderFixture.groovy').call()

        def skipped = declaration.stages['Pipeline'].stages['Skipped builder']
        assertEquals('builder', skipped.agent.kubernetes.inheritFrom)
        assertEquals(Boolean.TRUE, skipped.when.beforeAgent)
        assertTrue(echoes().contains('Skipping stage Skipped builder'))
        assertFalse(enteredStages().contains('Skipped builder'))
        assertFalse(echoes().contains('BUILDER-MUST-NOT-RUN'))
        assertFalse(echoes().any { it.startsWith('Executing on agent ') && it.contains('builder') })
        assertJobStatusSuccess()
    }

    @Test
    void configErrorPreventsPipelineDeclarationAndStages() {
        def fixture = loadScript('test/resources/fixtures/harness/harnessOrderFixture.groovy')

        def failure = assertThrows(IllegalArgumentException) { fixture.call(true) }

        assertEquals('CONFIG-ERROR', failure.message)
        assertNull(declaration)
        assertTrue(enteredStages().isEmpty())
        assertFalse(helper.callStack.any { it.methodName == 'pipeline' })
        assertFalse(echoes().any { it.startsWith('Executing on agent ') })
        assertJobStatusFailure()
    }

    private List<String> echoes() {
        helper.callStack.findAll { it.methodName == 'echo' }.collect { it.args[0].toString() }
    }

    private List<String> enteredStages() {
        helper.callStack.findAll { it.methodName == 'stage' }.collect { it.args[0].toString() }
    }
}
