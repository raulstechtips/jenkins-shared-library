package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class ClusterConfigurablePipelineTest extends DeclarativePipelineTest {
    private DeclarativePipeline declaration
    private String configText
    private String chartVersion
    private String subject
    private String failingStep
    private boolean chartPresent
    private Map observedConfig
    private List<Map> calls
    private List<Map> archives
    private List<String> events
    private List<String> upstreamImages
    private final String digest = 'sha256:' + ('a' * 64)

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        observedConfig = null
        configText = fixture('argocd')
        chartVersion = '1.2.0'
        subject = 'feat: ordinary build'
        failingStep = null
        chartPresent = true
        calls = []
        archives = []
        events = []
        upstreamImages = ['quay.io/example/service:stable']
        binding.setVariable('env', [BRANCH_NAME: 'main', CHANGE_ID: null,
            CHANGE_BRANCH: null, BUILD_NUMBER: '13'])
        binding.setVariable('params', [RELEASE_VERSION: ''])
        binding.setVariable('scm', [url: 'mock-scm'])
        helper.registerAllowedMethod('merge', [], { -> 'merge' })
        helper.registerAllowedMethod('error', [String], { String message ->
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException(message)
        })
        helper.registerAllowedMethod('pipeline', [Closure], { Closure body ->
            events << 'pipeline'
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        })
        helper.registerAllowedMethod('readTrusted', [String], { String path ->
            events << path
            if (path == 'project-pipeline-config.yaml') {
                return configText
            }
            assertEquals('charts/Chart.yaml', path)
            if (!chartPresent) {
                throw new FileNotFoundException(path)
            }
            chartText()
        })
        helper.registerAllowedMethod('readYaml', [Map], { Map args ->
            assertEquals(['text'] as Set, args.keySet())
            new Yaml().load(args.text as String)
        })
        helper.registerAllowedMethod('libraryResource', [String], { String path ->
            events << path
            getClass().getResource('/' + path).text
        })
        helper.registerAllowedMethod('readFile', [String], { String path ->
            assertEquals('charts/Chart.yaml', path)
            chartText()
        })
        helper.registerAllowedMethod('sh', [Map], { Map args ->
            assertEquals([script: 'git log -1 --pretty=%s', returnStdout: true], args)
            subject + '\n'
        })
        helper.registerAllowedMethod('checkout', [Map], { Map scm ->
            assertEquals(binding.getVariable('scm'), scm)
            events << 'checkout'
        })
        helper.registerAllowedMethod('archiveArtifacts', [Map], { Map args -> archives << args })
        helper.registerAllowedMethod('logRotator', [Map], { Map args -> args })
        helper.registerAllowedMethod('buildDiscarder', [Map], { Map args -> })
        // Real scripts, not preloaded config or pod stubs.
        Script configLoader = loadScript('vars/loadProjectConfig.groovy')
        Script podBuilder = loadScript('vars/agentPodYaml.groovy')
        helper.registerAllowedMethod('loadProjectConfig', [Map], configLoader.&call)
        helper.registerAllowedMethod('agentPodYaml', [Map], podBuilder.&call)
        helper.registerAllowedMethod('computeVersion', [Map], { Map cfg ->
            record('computeVersion', cfg)
            cfg.releaseVersion ?: '1.2.1-main-0067101-13'
        })
        ['renderChart', 'trivySecretScan', 'trivyDockerfileScan', 'opengrepScan',
            'helmTest', 'trivyChartScan', 'argoDeploy'].each { String name ->
            helper.registerAllowedMethod(name, [Map], { Map cfg -> record(name, cfg) })
        }
        helper.registerAllowedMethod('buildDockerImage', [Map], { Map cfg ->
            record('buildDockerImage', cfg)
            digest
        })
        ['trivyImageScan', 'helmPackageUpload', 'helmInstall', 'helmCleanInstall',
            'runTestCommand'].each { String name ->
            helper.registerAllowedMethod(name, [Map, String], { Map cfg, String argument ->
                record(name, cfg, argument)
            })
        }
        binding.setVariable('releasePipeline', [
            prepare: { Map cfg -> record('prepare', cfg) },
            finish: { Map cfg -> record('finish', cfg) }
        ])
        binding.setVariable('trivyImageScan', [
            upstreamImages: { Map cfg ->
                record('upstreamImages', cfg)
                upstreamImages
            }
        ])
    }

    @Test
    void argocdOnMainRunsExactStageListAndRealConfigAndPod() {
        runPipeline()

        assertEquals(['Checkout', 'Render', 'Trivy secret scan', 'Trivy Dockerfile scan',
            'Opengrep scan', 'Helm test', 'Trivy image scan', 'Package and upload chart',
            'Trivy chart scan', 'Argo deploy'], leafStages())
        assertEquals(['computeVersion', 'renderChart', 'upstreamImages', 'trivySecretScan', 'trivyDockerfileScan',
            'opengrepScan', 'helmTest', 'trivyImageScan', 'helmPackageUpload',
            'trivyChartScan', 'argoDeploy'], calls*.name)
        assertEquals('cluster', observedConfig.type)
        assertEquals('argocd', observedConfig['project-name'])
        assertEquals(false, observedConfig['pipeline-steps']['helm-clean-install'])
        assertEquals('1.2.1-main-0067101-13', binding.getVariable('currentBuild').displayName)
        assertEquals(['project-pipeline-config.yaml', 'charts/Chart.yaml',
            'dev/raulstechtips/jenkins/tools.yaml', 'pipeline', 'checkout'], events)
        assertTrue(helper.callStack.any { it.methodName == 'echo' &&
            it.args[0].toString() == 'Steps for this build (main): render, early-checks, ' +
                'trivy-secret-scan, trivy-dockerfile-scan, opengrep-scan, helm-test, ' +
                'helm-package-upload, trivy-chart-scan, argo-deploy' })
        assertAgentStructure()
        assertFalse(agentEntries().any { it.contains('builder') || it.contains('deployer') })
        assertArchive()
        assertJobStatusSuccess()
        println 'ARGOCD-MAIN CALL STACK'
        printCallStack()
    }

    @Test
    void releaseCommitSkipsEveryLaterStageAndMarksNotBuilt() {
        configText = fixture('jenkins')
        enable('helm-install', 'helm-clean-install')
        subject = 'release: 1.3.0'
        chartVersion = '1.3.0'
        runPipeline()

        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertEquals('NOT_BUILT', binding.getVariable('currentBuild').result)
        assertTrue(helper.callStack.any { it.methodName == 'echo' &&
            it.args[0].toString() == 'Steps for this build (main): ' })
        assertTrue(binding.getVariable('currentBuild').description.contains('1.3.0'))
        assertFalse(agentEntries().any { it.contains('builder') || it.contains('deployer') })
        assertArchive()
    }

    @Test
    void mismatchedReleaseCommitVersionDoesNotSkip() {
        subject = 'release: 1.3.0'
        runPipeline()
        assertTrue(leafStages().contains('Argo deploy'))
        assertJobStatusSuccess()
    }

    @Test
    void malformedVersionMatchingReleaseSubjectFailsBeforeSkip() {
        chartVersion = '1.0'
        subject = 'release: 1.0'

        def failure = assertThrows(IllegalArgumentException) { runPipeline() }

        assertEquals('Version file charts/Chart.yaml must contain X.Y.Z', failure.message)
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertJobStatusFailure()
    }

    @Test
    void emptyChartDocumentUsesVersionErrorBeforeSkip() {
        helper.registerAllowedMethod('readFile', [String], { String path ->
            assertEquals('charts/Chart.yaml', path)
            ''
        })
        subject = 'release: null'

        def failure = assertThrows(IllegalArgumentException) { runPipeline() }

        assertEquals('Version file charts/Chart.yaml must contain X.Y.Z', failure.message)
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertJobStatusFailure()
    }

    @Test
    void releaseSubjectOnFeatureBranchDoesNotSkip() {
        featureBranch()
        subject = 'release: 1.3.0'
        chartVersion = '1.3.0'
        runPipeline()
        assertTrue(leafStages().contains('Render'))
        assertJobStatusSuccess()
    }

    @Test
    void jenkinsFeatureBuildPassesDigestToScanAndChartAndUsesShellTests() {
        configText = fixture('jenkins')
        featureBranch()
        runPipeline()

        assertEquals(['Checkout', 'Render', 'Trivy secret scan', 'Trivy Dockerfile scan',
            'Opengrep scan', 'Helm test', 'Build image', 'Unit test', 'Trivy image scan',
            'Package and upload chart', 'Trivy chart scan'], leafStages())
        assertEquals(digest, observedConfig.digest)
        assertEquals(digest, callNamed('helmPackageUpload').argument)
        assertEquals('ghcr.io/raulstechtips/jenkins-controller:' + observedConfig.version + '@' + digest,
            callNamed('trivyImageScan').argument)
        assertEquals('shell', callNamed('runTestCommand').argument)
        assertTrue(agentEntries().any { it.contains('builder') })
        assertFalse(agentEntries().any { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void builtImageReferenceUsesFirstConfiguredRegistry() {
        configText = fixture('jenkins').replace('[ghcr]', '[harbor, ghcr]')
        runPipeline()
        assertEquals('harbor.prod.raulstechtips.dev/raulstechtips/jenkins-controller:' +
            observedConfig.version + '@' + digest, callNamed('trivyImageScan').argument)
    }

    @Test
    void chartWithoutImagesOrBuildSkipsImageScanStage() {
        upstreamImages = []
        runPipeline()

        assertEquals([], observedConfig.upstreamImages)
        assertFalse(leafStages().contains('Trivy image scan'))
        assertNull(callNamed('trivyImageScan'))
        assertNull(callNamed('buildDockerImage'))
        assertEquals('Render', callNamed('upstreamImages').stage)
        assertJobStatusSuccess()
    }

    @Test
    void upstreamImagesWithoutBuildRunImageScanAfterRenderDiscovery() {
        runPipeline()

        assertEquals(upstreamImages, observedConfig.upstreamImages)
        assertTrue(leafStages().contains('Trivy image scan'))
        assertNull(callNamed('buildDockerImage'))
        assertNull(callNamed('trivyImageScan').argument)
        assertEquals(['renderChart', 'upstreamImages'], calls[1..2]*.name)
        assertEquals('Render', callNamed('upstreamImages').stage)
        assertEquals(1, calls.count { it.name == 'upstreamImages' })
        assertJobStatusSuccess()
    }

    @Test
    void builtImageWithoutUpstreamsStillRunsImageScan() {
        configText = fixture('jenkins')
        upstreamImages = []
        runPipeline()

        assertEquals([], observedConfig.upstreamImages)
        assertEquals(digest, observedConfig.digest)
        assertTrue(leafStages().contains('Trivy image scan'))
        assertEquals('ghcr.io/raulstechtips/jenkins-controller:' + observedConfig.version + '@' + digest,
            callNamed('trivyImageScan').argument)
        assertJobStatusSuccess()
    }

    @Test
    void disabledTrivySkipsDiscoveryAndImageScanEvenWithBuiltImage() {
        configText = fixture('jenkins').replace('trivy-scan: true', 'trivy-scan: false')
        runPipeline()

        assertEquals(digest, observedConfig.digest)
        assertNull(callNamed('upstreamImages'))
        assertNull(callNamed('trivyImageScan'))
        assertFalse(leafStages().contains('Trivy image scan'))
        assertJobStatusSuccess()
    }

    @Test
    void helmInstallOnBranchRunsWithDigest() {
        configText = fixture('jenkins')
        enable('helm-install')
        featureBranch()
        runPipeline()
        assertEquals('Helm install', leafStages().last())
        assertEquals(digest, callNamed('helmInstall').argument)
        assertEquals(1, agentEntries().count { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void helmInstallOnMainSkipsDeployer() {
        enable('helm-install')
        runPipeline()
        assertFalse(leafStages().contains('Helm install'))
        assertFalse(agentEntries().any { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void bothInstallFlagsOnBranchRunOnlyCleanInstallWithDigest() {
        configText = fixture('jenkins')
        enable('helm-install', 'helm-clean-install')
        featureBranch()
        runPipeline()
        assertEquals('Helm clean install', leafStages().last())
        assertFalse(calls*.name.contains('helmInstall'))
        assertEquals(digest, callNamed('helmCleanInstall').argument)
        assertEquals(1, agentEntries().count { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void releaseVersionOnBranchErrorsBeforePipelineOrAnyPod() {
        featureBranch()
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals('RELEASE_VERSION is only allowed on the main job.', failure.message)
        assertNoPipeline()
        assertTrue(events.contains('dev/raulstechtips/jenkins/tools.yaml'))
        println 'RELEASE_VERSION-ON-BRANCH: ' + failure.message
        println 'Pipeline declarations: 0; entered stages: 0; agent entries: 0; result: FAILURE'
    }

    @Test
    void releaseVersionOnPullRequestErrorsEvenWhenBranchNameIsMain() {
        binding.getVariable('env').CHANGE_ID = '42'
        binding.getVariable('env').CHANGE_BRANCH = 'feature/DEVSECOPS-34-x'
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertNoPipeline()
    }

    @Test
    void pullRequestNeverDeploysToArgoOrTriggersReleaseCommitSkip() {
        binding.getVariable('env').CHANGE_ID = '42'
        binding.getVariable('env').CHANGE_BRANCH = 'bugfix/ABC-1'
        subject = 'release: 1.3.0'
        chartVersion = '1.3.0'
        runPipeline()
        assertTrue(leafStages().contains('Render'))
        assertFalse(calls*.name.contains('argoDeploy'))
        assertJobStatusSuccess()
    }

    @Test
    void mainReleasePreparesInCheckoutAndFinishesAfterArgoWithoutInstall() {
        configText = fixture('jenkins')
        enable('helm-install', 'helm-clean-install')
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        runPipeline()

        assertEquals(['computeVersion', 'prepare'], calls.take(2)*.name)
        assertEquals('Checkout', callNamed('prepare').stage)
        assertEquals(['argoDeploy', 'finish'], calls.takeRight(2)*.name)
        assertEquals('Release', leafStages().last())
        assertTrue(calls.every { it.name == 'computeVersion' || it.version == '1.3.0' })
        assertEquals('1.3.0', binding.getVariable('currentBuild').displayName)
        assertFalse(agentEntries().any { it.contains('deployer') })
        assertArchive()
        assertJobStatusSuccess()
    }

    @Test
    void releaseRerunWithSameParameterStillCallsPrepare() {
        // A failed prior run has not pushed its local release commit or tag.
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        runPipeline()
        assertEquals('1.3.0', callNamed('prepare').releaseVersion)
        assertEquals('finish', calls.last().name)
        assertJobStatusSuccess()
    }

    @Test
    void existingTagPrepareFailureStopsReleaseAndAllLaterSteps() {
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        failingStep = 'prepare'
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals('Tag 1.3.0 already exists', failure.message)
        assertEquals(['Checkout'], leafStages())
        assertEquals(['computeVersion', 'prepare'], calls*.name)
        assertJobStatusFailure()
    }

    @Test
    void failingScanStopsBuildUploadDeployAndRelease() {
        configText = fixture('jenkins')
        binding.getVariable('params').RELEASE_VERSION = '1.3.0'
        failingStep = 'trivySecretScan'
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals(['computeVersion', 'prepare', 'renderChart', 'upstreamImages', 'trivySecretScan'], calls*.name)
        assertFalse(agentEntries().any { it.contains('builder') || it.contains('deployer') })
        assertJobStatusFailure()
        // JPU 1.29 does not execute post after thrown exceptions. Inspect its
        // captured scope, rather than replacing its executor with a passing fake.
        assertNotNull(declaration.stages['Pipeline'].post.always)
        assertNull(declaration.post)
    }

    @Test
    void configErrorPreventsPipelineDeclarationAndPods() {
        configText = fixture('unknown-flag')
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertTrue(failure.message.contains('trivy-scna'))
        assertNoPipeline()
        assertFalse(events.contains('dev/raulstechtips/jenkins/tools.yaml'))
    }

    @Test
    void malformedYamlPreventsPipelineDeclarationAndPods() {
        configText = 'project-config: ['
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertNoPipeline()
    }

    @Test
    void disabledFlagsAndAbsentChartSkipAllOptionalStagesAndAgents() {
        configText = 'project-config:\n  project-name: demo\n'
        chartPresent = false
        featureBranch()
        runPipeline()
        assertEquals(['Checkout'], leafStages())
        assertEquals(['computeVersion'], calls*.name)
        assertEquals(1, agentEntries().count { it.contains('kubernetes:') })
        assertArchive()
        assertJobStatusSuccess()
    }

    @Test
    void agentDeclarationsUseRealPodAndBeforeAgentForEveryOwnAgentStage() {
        runPipeline()
        assertAgentStructure()
        assertEquals(['Checkout', 'Render', 'Early checks', 'Helm test', 'Build image',
            'Unit test', 'Trivy image scan', 'Package and upload chart', 'Trivy chart scan',
            'Helm clean install', 'Helm install', 'Argo deploy', 'Release'],
            declaration.stages['Pipeline'].stages.keySet() as List)
        assertEquals(['Trivy secret scan', 'Trivy Dockerfile scan', 'Opengrep scan'],
            declaration.stages['Pipeline'].stages['Early checks'].parallel.stages.keySet() as List)
        assertNotNull(declaration.stages['Pipeline'].post.always)
        assertNull(declaration.post)
        assertTrue(helper.callStack.any { it.methodName == 'skipDefaultCheckout' })
        assertTrue(helper.callStack.any { it.methodName == 'disableConcurrentBuilds' })
        assertTrue(helper.callStack.any { it.methodName == 'timeout' &&
            it.args[0] == [time: 60, unit: 'MINUTES'] })
        assertTrue(helper.callStack.any { it.methodName == 'buildDiscarder' &&
            it.args[0] == [numToKeep: '20', artifactNumToKeep: '10'] })
        assertNotNull(declaration.params)
        assertEquals('', binding.getVariable('params').RELEASE_VERSION)
    }

    private void runPipeline() {
        loadScript('vars/clusterConfigurablePipeline.groovy').call()
    }

    private void record(String name, Map cfg, String argument = null) {
        observedConfig = cfg
        calls << [name: name, argument: argument, version: cfg.version,
            releaseVersion: cfg.releaseVersion, stage: leafStages().last()]
        if (name == failingStep) {
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException(name == 'prepare' ?
                'Tag 1.3.0 already exists' : 'Trivy found a secret.')
        }
    }

    private Map callNamed(String name) { calls.find { it.name == name } }
    private String fixture(String name) { getClass().getResource("/fixtures/config/${name}.yaml").text }
    private String chartText() { "name: demo\nversion: ${chartVersion}\n" }
    private void featureBranch() { binding.getVariable('env').BRANCH_NAME = 'feature/DEVSECOPS-34-x' }
    private void enable(String... flags) {
        flags.each { configText = configText.replace(it + ': false', it + ': true') }
    }

    private List<String> leafStages() {
        helper.callStack.findAll { it.methodName == 'stage' }
            .collect { it.args[0].toString() }.findAll { !(it in ['Pipeline', 'Early checks']) }
    }

    private List<String> agentEntries() {
        helper.callStack.findAll { it.methodName == 'echo' }
            .collect { it.args[0].toString() }.findAll { it.startsWith('Executing on agent ') }
    }

    private void assertNoPipeline() {
        assertNull(declaration)
        assertFalse(helper.callStack.any { it.methodName == 'pipeline' || it.methodName == 'stage' })
        assertTrue(agentEntries().empty)
        assertTrue(calls.empty)
        assertTrue(archives.empty)
        assertJobStatusFailure()
    }

    private void assertArchive() {
        assertEquals([[artifacts: '.pipeline/reports/**', allowEmptyArchive: true]], archives)
    }

    private void assertAgentStructure() {
        assertEquals('none', declaration.agent.label)
        def pipeline = declaration.stages['Pipeline']
        def agent = pipeline.agent.kubernetes
        assertEquals('generic', agent.inheritFrom)
        assertEquals(observedConfig.podYaml, agent.yaml)
        assertEquals('merge', agent.yamlMergeStrategy)
        assertNull(agent.defaultContainer)
        Map pod = new Yaml().load(agent.yaml)
        assertEquals(['tools', 'helm', 'kubeconform', 'trivy'], pod.spec.containers*.name)
        ['Build image': 'builder', 'Helm install': 'deployer', 'Helm clean install': 'deployer'].each {
            String name, String parent ->
            assertEquals(parent, pipeline.stages[name].agent.kubernetes.inheritFrom)
            assertEquals(Boolean.TRUE, pipeline.stages[name].when.beforeAgent, name)
        }
    }
}
