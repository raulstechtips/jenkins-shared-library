package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ComputeVersionTest extends BasePipelineTest {
    private Script step
    private Map environment
    private List<String> shells
    private String latest
    private int latestCalls
    private int revisionCalls

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        environment = [BRANCH_NAME: 'main', BUILD_NUMBER: '13']
        binding.setVariable('env', environment)
        binding.setVariable('params', [RELEASE_VERSION: '9.9.9'])
        shells = []
        latest = '1.0.0'
        latestCalls = 0
        revisionCalls = 0
        helper.registerAllowedMethod('latestRelease', []) {
            latestCalls++
            latest
        }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            fail("computeVersion must not read version files: ${path}")
        }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            assertEquals(true, args.returnStdout)
            shells << args.script
            '0067101012345\n'
        }
        helper.registerAllowedMethod('scmRevision', []) {
            revisionCalls++
            [head: 'a1b2c3d012345', base: '9f8e7d6012345']
        }
        step = loadScript('vars/computeVersion.groovy')
    }

    @Test
    void mainIncreasesMinorOfLatestReleaseTag() {
        assertEquals('1.1.0-main-0067101-13', step.call([type: 'cluster']))
        assertEquals(1, latestCalls)
        assertEquals(['git rev-parse HEAD'], shells)
        assertEquals(0, revisionCalls)
    }

    @Test
    void noReleaseTagStartsFromZero() {
        latest = null
        assertEquals('0.1.0-main-0067101-13', step.call([type: 'cluster']))
    }

    @Test
    void javaUsesTheSameReleaseTagBase() {
        latest = '1.2.3'
        environment.BRANCH_NAME = 'feature/DEVSECOPS-70-x'
        environment.BUILD_NUMBER = '3'
        assertEquals('1.3.0-feature-devsecops-70-0067101-3', step.call([type: 'java']))
        assertEquals(['git rev-parse HEAD'], shells)
    }

    @Test
    void pullRequestUsesRevisionMetadataInsteadOfGit() {
        environment.putAll([CHANGE_ID: '1', CHANGE_BRANCH: 'bugfix/ABC-1', BRANCH_NAME: 'PR-1', BUILD_NUMBER: '2'])
        latest = '1.2.0'
        assertEquals('1.3.0-bugfix-abc-1-a1b2c3d-9f8e7d6-2', step.call([type: 'cluster']))
        assertEquals([], shells)
        assertEquals(1, revisionCalls)
    }

    @Test
    void releaseReturnsConfigVersionWithoutTagLookup() {
        assertEquals('1.3.0', step.call([type: 'java', releaseVersion: '1.3.0']))
        assertEquals(0, latestCalls)
        assertEquals([], shells)
        assertEquals(0, revisionCalls)
    }

    @Test
    void invalidBranchFailsWithExactMessage() {
        environment.BRANCH_NAME = 'wip/test'
        def error = assertThrows(IllegalArgumentException) { step.call([type: 'cluster']) }
        assertEquals('Branch "wip/test" must be <type>/<KEY>-<number>[-text]. The type must be feature, bugfix, hotfix or release. Example: feature/DEVSECOPS-54-argocd-chart.', error.message)
    }
}
