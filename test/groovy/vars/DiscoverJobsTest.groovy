package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.JobDiscovery
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DiscoverJobsTest extends BasePipelineTest {
    private List<Map> files
    private List<String> actions
    private Map generated

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        files = [[path: 'pipelines/update-infisical-auth.Jenkinsfile', directory: false]]
        actions = []
        generated = null
        binding.setVariable('scm', [
            userRemoteConfigs: [[url: 'https://github.com/example/operations.git', credentialsId: 'github-app']]
        ])
        helper.registerAllowedMethod('deleteDir', []) { actions << 'clean' }
        helper.registerAllowedMethod('checkout', [Map]) { Map source ->
            assertSame(binding.getVariable('scm'), source)
            actions << 'checkout'
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            assertEquals([glob: 'pipelines/*.Jenkinsfile'], args)
            actions << 'find'
            files
        }
        helper.registerAllowedMethod('jobDsl', [Map]) { Map args ->
            actions << 'generate'
            generated = args
        }
    }

    @Test
    void cleansAndChecksOutBeforeCreatingManualJobDefinitions() {
        loadScript('vars/discoverJobs.groovy').call()
        assertEquals(['clean', 'checkout', 'find', 'generate'], actions)
        assertEquals(JobDiscovery.SCRIPT, generated.scriptText)
        assertEquals([
            jobDefinitions: [[name: 'update-infisical-auth', displayName: 'Update Infisical Auth',
                scriptPath: 'pipelines/update-infisical-auth.Jenkinsfile']],
            repositoryUrl: 'https://github.com/example/operations.git',
            scmCredentialsId: 'github-app'
        ], generated.additionalParameters)
        assertEquals('JENKINS_ROOT', generated.lookupStrategy)
        assertEquals('DISABLE', generated.removedJobAction)
        assertTrue(generated.failOnSeedCollision)
        assertFalse(generated.sandbox)
        assertFalse(helper.callStack.any { it.methodName in ['load', 'build', 'readFile'] })
    }

    @Test
    void emptyDiscoveryStillReconcilesRemovedJobs() {
        files = []
        loadScript('vars/discoverJobs.groovy').call()
        assertEquals([], generated.additionalParameters.jobDefinitions)
        assertEquals('DISABLE', generated.removedJobAction)
    }

    @Test
    void ignoresDirectoriesWithThePipelineSuffix() {
        files << [path: 'pipelines/not-a-file.Jenkinsfile', directory: true]
        loadScript('vars/discoverJobs.groovy').call()
        assertEquals(1, generated.additionalParameters.jobDefinitions.size())
    }

    @Test
    void invalidFilenameFailsBeforeUpdatingAnyJobs() {
        files << [path: 'pipelines/Bad Name.Jenkinsfile', directory: false]
        assertThrows(IllegalArgumentException) { loadScript('vars/discoverJobs.groovy').call() }
        assertNull(generated)
    }

    @Test
    void checkoutFailureDoesNotDisableExistingJobs() {
        helper.registerAllowedMethod('checkout', [Map]) { Map source ->
            throw new IllegalStateException('Checkout failed')
        }
        assertThrows(IllegalStateException) { loadScript('vars/discoverJobs.groovy').call() }
        assertNull(generated)
    }

    @Test
    void reportsJobGenerationFailure() {
        helper.registerAllowedMethod('jobDsl', [Map]) { Map args ->
            throw new IllegalStateException('Script approval required')
        }
        assertThrows(IllegalStateException) { loadScript('vars/discoverJobs.groovy').call() }
    }
}
