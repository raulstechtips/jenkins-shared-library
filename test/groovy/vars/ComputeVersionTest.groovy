package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ComputeVersionTest extends BasePipelineTest {
    private Script step
    private Map environment
    private List<String> reads
    private List<String> shells
    private Object chart
    private String chartContents
    private String propertiesContents
    private int revisionCalls

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        environment = [BRANCH_NAME: 'main', BUILD_NUMBER: '13']
        binding.setVariable('env', environment)
        binding.setVariable('params', [RELEASE_VERSION: '9.9.9'])
        reads = []
        shells = []
        chart = [version: '1.0.0']
        chartContents = 'version: 1.0.0\n'
        propertiesContents = 'name=example\nversion=1.0.0\n'
        revisionCalls = 0
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals([text: chartContents], args)
            chart
        }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            reads << path
            path == 'charts/Chart.yaml' ? chartContents : propertiesContents
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
    void clusterReadsChartAndUsesOnlyHeadCommand() {
        assertEquals('1.0.1-main-0067101-13', step.call([type: 'cluster']))
        assertEquals(['charts/Chart.yaml'], reads)
        assertEquals(['git rev-parse HEAD'], shells)
        assertEquals(0, revisionCalls)
    }

    @Test
    void javaReadsGradleProperties() {
        propertiesContents = '# comment\nname=example\nversion=1.2.0\n'
        environment.BRANCH_NAME = 'feature/DEVSECOPS-70-x'
        environment.BUILD_NUMBER = '3'
        assertEquals('1.2.1-feature-devsecops-70-0067101-3', step.call([type: 'java']))
        assertEquals(['gradle.properties'], reads)
        assertEquals(['git rev-parse HEAD'], shells)
    }

    @Test
    void pullRequestUsesRevisionMetadataInsteadOfGit() {
        environment.putAll([CHANGE_ID: '1', CHANGE_BRANCH: 'bugfix/ABC-1', BRANCH_NAME: 'PR-1', BUILD_NUMBER: '2'])
        chart = [version: '1.2.0']
        chartContents = 'version: 1.2.0\n'
        assertEquals('1.2.1-bugfix-abc-1-a1b2c3d-9f8e7d6-2', step.call([type: 'cluster']))
        assertEquals([], shells)
        assertEquals(1, revisionCalls)
    }

    @Test
    void releaseReturnsNormalizedConfigVersionExactly() {
        assertEquals('1.3.0', step.call([type: 'java', releaseVersion: '1.3.0']))
        assertEquals([], reads)
        assertEquals([], shells)
        assertEquals(0, revisionCalls)
    }

    @Test
    void malformedChartVersionFails() {
        chart = [version: '1.0']
        chartContents = 'version: 1.0\n'
        assertVersionError('cluster', 'charts/Chart.yaml')
    }

    @Test
    void emptyChartFails() {
        chart = null
        chartContents = ''
        assertVersionError('cluster', 'charts/Chart.yaml')
    }

    @Test
    void malformedGradleVersionFails() {
        propertiesContents = 'version=1.0\n'
        assertVersionError('java', 'gradle.properties')
    }

    @Test
    void emptyGradleFileFails() {
        propertiesContents = ''
        assertVersionError('java', 'gradle.properties')
    }

    @Test
    void missingVersionLineFails() {
        propertiesContents = '# version=1.2.3\nname=example\n'
        assertVersionError('java', 'gradle.properties')
    }

    @Test
    void invalidBranchFailsWithExactMessage() {
        environment.BRANCH_NAME = 'wip/test'
        def error = assertThrows(IllegalArgumentException) { step.call([type: 'cluster']) }
        assertEquals('Branch "wip/test" must be <type>/<KEY>-<number>[-text]. The type must be feature, bugfix, hotfix or release. Example: feature/DEVSECOPS-54-argocd-chart.', error.message)
    }

    private void assertVersionError(String type, String path) {
        def error = assertThrows(IllegalArgumentException) { step.call([type: type]) }
        assertEquals("Version file ${path} must contain X.Y.Z".toString(), error.message)
    }
}
