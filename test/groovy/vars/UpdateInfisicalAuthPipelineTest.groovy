package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class UpdateInfisicalAuthPipelineTest extends DeclarativePipelineTest {
    private boolean updateCalled
    private DeclarativePipeline declaration
    private boolean failUpdate

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        updateCalled = false
        declaration = null
        failUpdate = false
        binding.setVariable('params', [:])
        helper.registerAllowedMethod('pipeline', [Closure]) { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        }
        helper.registerAllowedMethod('logRotator', [Map]) { Map args -> args }
        helper.registerAllowedMethod('buildDiscarder', [Map]) { Map args -> }
        helper.registerAllowedMethod('updateInfisicalAuth', []) {
            updateCalled = true
            if (failUpdate) {
                throw new IllegalStateException('Update failed')
            }
        }
    }

    @Test
    void runsOnlyTheOperationalStepWithDefaultConnectionParameter() {
        loadScript('vars/updateInfisicalAuthPipeline.groovy').call()
        assertTrue(updateCalled)
        assertEquals('infisical-prod', binding.getVariable('params').INFISICAL_CONNECTION_NAME)
        assertEquals('none', declaration.agent.label)
        assertEquals('deployer',
            declaration.stages['Update Infisical authentication'].agent.kubernetes.inheritFrom)
        assertFalse(helper.callStack.any { it.methodName in ['checkout', 'git', 'archiveArtifacts'] })
        assertJobStatusSuccess()
    }

    @Test
    void doesNotSuppressUpdateFailure() {
        failUpdate = true
        assertThrows(IllegalStateException) {
            loadScript('vars/updateInfisicalAuthPipeline.groovy').call()
        }
    }
}
