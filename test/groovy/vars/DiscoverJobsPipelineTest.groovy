package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DiscoverJobsPipelineTest extends DeclarativePipelineTest {
    private int discoveries
    private DeclarativePipeline declaration

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        discoveries = 0
        binding.setVariable('env', [BRANCH_NAME: 'main'])
        helper.registerAllowedMethod('pipeline', [Closure]) { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        }
        helper.registerAllowedMethod('logRotator', [Map]) { Map args -> args }
        helper.registerAllowedMethod('buildDiscarder', [Map]) { Map args -> }
        helper.registerAllowedMethod('discoverJobs', []) { discoveries++ }
    }

    @Test
    void mainUsesTheGenericAgentAndOnlyRunsDiscovery() {
        loadScript('vars/discoverJobsPipeline.groovy').call()
        assertEquals(1, discoveries)
        assertEquals('none', declaration.agent.label)
        assertEquals('generic', declaration.stages['Discover jobs'].agent.kubernetes.inheritFrom)
        assertFalse(helper.callStack.any { it.methodName in ['updateInfisicalAuth', 'build'] })
        assertJobStatusSuccess()
    }

    @Test
    void skipsFeatureBranchesPullRequestsTagsAndMissingBranchIdentity() {
        [
            [BRANCH_NAME: 'feature/example'],
            [BRANCH_NAME: 'PR-1', CHANGE_ID: '1'],
            [BRANCH_NAME: 'main', CHANGE_ID: '1'],
            [BRANCH_NAME: 'main', TAG_NAME: 'main'],
            [:]
        ].each { Map environment ->
            binding.setVariable('env', environment)
            loadScript('vars/discoverJobsPipeline.groovy').call()
            assertEquals(0, discoveries)
        }
    }

    @Test
    void doesNotSuppressDiscoveryFailure() {
        helper.registerAllowedMethod('discoverJobs', []) {
            throw new IllegalStateException('Discovery failed')
        }
        assertThrows(IllegalStateException) {
            loadScript('vars/discoverJobsPipeline.groovy').call()
        }
    }
}
