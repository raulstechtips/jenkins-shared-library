package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class JavaConfigurablePipelineTest extends DeclarativePipelineTest {
    private DeclarativePipeline declaration
    private String configText
    private String versionFile
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
        configText = fixture('jenkins-shared-library')
        versionFile = '# version=ignored\nversion=0.1.0\nother.version=ignored\n'
        subject = 'feat: ordinary build'
        failingStep = null
        chartPresent = false
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
            'name: demo\nversion: 9.9.9\n'
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
            assertEquals('gradle.properties', path)
            versionFile
        })
        helper.registerAllowedMethod('sh', [Map], { Map args ->
            assertEquals(true, args.returnStdout)
            if (args.script == 'git log -1 --pretty=%s') {
                return subject + '\n'
            }
            assertEquals('git rev-parse HEAD', args.script)
            '0067101abcdef\n'
        })
        helper.registerAllowedMethod('checkout', [Map], { Map scm ->
            assertEquals(binding.getVariable('scm'), scm)
            events << 'checkout'
        })
        helper.registerAllowedMethod('archiveArtifacts', [Map], { Map args -> archives << args })
        helper.registerAllowedMethod('logRotator', [Map], { Map args -> args })
        helper.registerAllowedMethod('buildDiscarder', [Map], { Map args -> })
        // Keep config, pod composition and version calculation real.
        Script configLoader = loadScript('vars/loadProjectConfig.groovy')
        Script podBuilder = loadScript('vars/agentPodYaml.groovy')
        Script versionCalculator = loadScript('vars/computeVersion.groovy')
        helper.registerAllowedMethod('loadProjectConfig', [Map], configLoader.&call)
        helper.registerAllowedMethod('agentPodYaml', [Map], podBuilder.&call)
        helper.registerAllowedMethod('computeVersion', [Map], { Map cfg ->
            record('computeVersion', cfg)
            versionCalculator.call(cfg)
        })
        helper.registerAllowedMethod('scmRevision', [], { ->
            [head: 'a1b2c3dabcdef', base: '9f8e7d6abcdef']
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
            'runBuildCommand', 'runTestCommand'].each { String name ->
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
    void libraryOnMainRunsExactStagesWithRealConfigPodAndVersion() {
        runPipeline()

        assertEquals(['Checkout', 'Trivy scan', 'Opengrep scan', 'Build', 'Unit test'], leafStages())
        assertEquals(['computeVersion', 'trivySecretScan', 'trivyDockerfileScan',
            'opengrepScan', 'runBuildCommand', 'runTestCommand'], calls*.name)
        assertEquals('java', observedConfig.type)
        assertEquals('jenkins-shared-library', observedConfig['project-name'])
        assertEquals('./gradlew build -x test', observedConfig['build-command'])
        assertEquals('./gradlew test', observedConfig['test-command'])
        assertEquals(false, observedConfig.chartPresent)
        assertEquals([], observedConfig.upstreamImages)
        assertNull(callNamed('upstreamImages'))
        assertEquals('0.1.1-main-0067101-13', binding.getVariable('currentBuild').displayName)
        assertEquals('jdk', callNamed('runBuildCommand').argument)
        assertEquals('jdk', callNamed('runTestCommand').argument)
        assertEquals(['project-pipeline-config.yaml', 'charts/Chart.yaml',
            'dev/raulstechtips/jenkins/tools.yaml', 'pipeline', 'checkout'], events)
        assertTrue(helper.callStack.any { it.methodName == 'echo' &&
            it.args[0].toString() == 'Steps for this build (main): early-checks, trivy-scan, ' +
                'opengrep-scan, build, unit-test' })
        assertAgentStructure()
        assertFalse(agentEntries().any { it.contains('builder') || it.contains('deployer') })
        assertArchive()
        assertJobStatusSuccess()
        println 'LIBRARY-MAIN CALL STACK'
        printCallStack()
    }

    @Test
    void missingBuildCommandErrorsBeforePipelineOrAnyPod() {
        configText = fixture('build-without-command')
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertTrue(failure.message.contains('project-config.build-command'))
        assertNoPipeline()
        assertFalse(events.contains('dev/raulstechtips/jenkins/tools.yaml'))
    }

    @Test
    void malformedYamlErrorsBeforePipelineOrAnyPod() {
        configText = 'project-config: ['
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertNoPipeline()
    }

    @Test
    void unknownFlagErrorsBeforePipelineOrAnyPod() {
        configText = fixture('unknown-flag')
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertTrue(failure.message.contains('trivy-scna'))
        assertNoPipeline()
    }

    @Test
    void mainReleasePreparesJavaVersionFileAndFinishesLast() {
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        runPipeline()

        assertEquals(['computeVersion', 'prepare'], calls.take(2)*.name)
        assertEquals('Checkout', callNamed('prepare').stage)
        assertEquals('java', callNamed('prepare').type)
        assertEquals('1.0.0', callNamed('prepare').releaseVersion)
        assertEquals(['prepare', 'finish'], calls.findAll { it.name in ['prepare', 'finish'] }*.name)
        assertEquals('finish', calls.last().name)
        assertEquals('Release', leafStages().last())
        assertTrue(calls.every { it.name == 'computeVersion' || it.version == '1.0.0' })
        assertEquals('1.0.0', binding.getVariable('currentBuild').displayName)
        assertArchive()
        assertJobStatusSuccess()
        // The real release var's Java write and atomic push are covered by ReleasePipelineTest.
    }

    @Test
    void releaseVersionOnBranchErrorsBeforePipelineOrAnyPod() {
        featureBranch()
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals('RELEASE_VERSION is only allowed on the main job.', failure.message)
        assertNoPipeline()
    }

    @Test
    void releaseVersionOnPullRequestErrorsEvenWhenBranchNameIsMain() {
        binding.getVariable('env').CHANGE_ID = '42'
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertNoPipeline()
    }

    @Test
    void releaseCommitSkipsEveryLaterStageAndMarksNotBuilt() {
        subject = 'release: 1.0.0'
        versionFile = '# version=ignored\nversion=1.0.0\nother.version=ignored\n'
        runPipeline()
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertEquals('NOT_BUILT', binding.getVariable('currentBuild').result)
        assertTrue(helper.callStack.any { it.methodName == 'echo' &&
            it.args[0].toString() == 'Steps for this build (main): ' })
        assertEquals('Release commit 1.0.0: already built by the release run.',
            binding.getVariable('currentBuild').description.toString())
        assertArchive()
    }

    @Test
    void mismatchedReleaseCommitVersionDoesNotSkip() {
        subject = 'release: 1.0.0'
        runPipeline()
        assertTrue(leafStages().contains('Build'))
        assertJobStatusSuccess()
    }

    @Test
    void malformedVersionMatchingReleaseSubjectFailsBeforeSkip() {
        versionFile = 'version=1.0\n'
        subject = 'release: 1.0'

        def failure = assertThrows(IllegalArgumentException) { runPipeline() }

        assertEquals('Version file gradle.properties must contain X.Y.Z', failure.message)
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertJobStatusFailure()
    }

    @Test
    void missingVersionMatchingReleaseSubjectFailsBeforeSkip() {
        versionFile = 'name=demo\n'
        subject = 'release: null'

        def failure = assertThrows(IllegalArgumentException) { runPipeline() }

        assertEquals('Version file gradle.properties must contain X.Y.Z', failure.message)
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertJobStatusFailure()
    }

    @Test
    void releaseSubjectOnBranchDoesNotSkip() {
        featureBranch()
        subject = 'release: 0.1.0'
        runPipeline()
        assertTrue(leafStages().contains('Build'))
        assertEquals('0.1.1-feature-devsecops-34-0067101-13', observedConfig.version)
        assertJobStatusSuccess()
    }

    @Test
    void pullRequestDoesNotSkipOrDeploy() {
        deliveryConfig()
        binding.getVariable('env').CHANGE_ID = '42'
        binding.getVariable('env').CHANGE_BRANCH = 'bugfix/ABC-1'
        subject = 'release: 0.1.0'
        runPipeline()
        assertTrue(leafStages().contains('Build'))
        assertFalse(calls*.name.contains('argoDeploy'))
        assertEquals('0.1.1-bugfix-abc-1-a1b2c3d-9f8e7d6-13', observedConfig.version)
        assertJobStatusSuccess()
    }

    @Test
    void disabledFlagsAndAbsentChartSkipOptionalStagesAndAgents() {
        configText = 'project-config:\n  project-name: demo\n'
        runPipeline()
        assertEquals(['Checkout'], leafStages())
        assertEquals(['computeVersion'], calls*.name)
        assertEquals(1, agentEntries().count { it.contains('kubernetes:') })
        assertArchive()
    }

    @Test
    void scanFlagsAreIndependentAndDoNotDisableBuildOrUnitTest() {
        configText = configText.replace('trivy-scan: true', 'trivy-scan: false')
        runPipeline()
        assertEquals(['Checkout', 'Opengrep scan', 'Build', 'Unit test'], leafStages())
        assertFalse(calls*.name.any { it.startsWith('trivy') })
    }

    @Test
    void opengrepAndBuildFlagsAreIndependentOfUnitTest() {
        configText = configText.replace('opengrep-scan: true', 'opengrep-scan: false')
            .replace('build: true', 'build: false')
        runPipeline()
        assertEquals(['Checkout', 'Trivy scan', 'Unit test'], leafStages())
        assertFalse(calls*.name.contains('runBuildCommand'))
    }

    @Test
    void deliveryStagesRunAfterJavaTestsAndPropagateImageDigest() {
        deliveryConfig()
        featureBranch()
        runPipeline()
        assertEquals(['Checkout', 'Render', 'Trivy scan', 'Opengrep scan', 'Build', 'Unit test',
            'Helm test', 'Build image', 'Trivy image scan', 'Package and upload chart',
            'Trivy chart scan', 'Helm clean install'], leafStages())
        assertEquals(digest, observedConfig.digest)
        assertEquals(digest, callNamed('helmPackageUpload').argument)
        assertEquals(digest, callNamed('helmCleanInstall').argument)
        assertEquals('ghcr.io/raulstechtips/jenkins-controller:' + observedConfig.version + '@' + digest,
            callNamed('trivyImageScan').argument)
        assertFalse(calls*.name.contains('helmInstall'))
        assertEquals(1, agentEntries().count { it.contains('builder') })
        assertEquals(1, agentEntries().count { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void chartWithoutImagesOrBuildSkipsImageScanStage() {
        chartPresent = true
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
        chartPresent = true
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
    void builtImageWithoutChartOrUpstreamsStillRunsImageScan() {
        configText = configText.replace('  pipeline-steps:',
            '  publish-targets: [ghcr]\n  pipeline-steps:\n    build-docker-image: true')
        runPipeline()

        assertEquals(false, observedConfig.chartPresent)
        assertEquals([], observedConfig.upstreamImages)
        assertNull(callNamed('upstreamImages'))
        assertEquals(digest, observedConfig.digest)
        assertTrue(leafStages().contains('Trivy image scan'))
        assertEquals('ghcr.io/raulstechtips/jenkins-shared-library:' + observedConfig.version + '@' + digest,
            callNamed('trivyImageScan').argument)
        assertJobStatusSuccess()
    }

    @Test
    void disabledTrivySkipsDiscoveryAndImageScanEvenWithBuiltImage() {
        deliveryConfig()
        configText = configText.replace('trivy-scan: true', 'trivy-scan: false')
        runPipeline()

        assertEquals(digest, observedConfig.digest)
        assertNull(callNamed('upstreamImages'))
        assertNull(callNamed('trivyImageScan'))
        assertFalse(leafStages().contains('Trivy image scan'))
        assertJobStatusSuccess()
    }

    @Test
    void deliveryOnMainUsesArgoAndNeverStartsDeployer() {
        deliveryConfig()
        runPipeline()
        assertEquals('argoDeploy', calls.last().name)
        assertFalse(agentEntries().any { it.contains('deployer') })
        assertJobStatusSuccess()
    }

    @Test
    void helmInstallRunsOnBranchWhenCleanInstallIsDisabled() {
        deliveryConfig()
        configText = configText.replace('helm-clean-install: true', 'helm-clean-install: false')
        featureBranch()
        runPipeline()
        assertEquals('helmInstall', calls.last().name)
        assertEquals(digest, callNamed('helmInstall').argument)
    }

    @Test
    void failingSecretScanStopsBuildDeliveryAndRelease() {
        deliveryConfig()
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        failingStep = 'trivySecretScan'
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals(['computeVersion', 'prepare', 'renderChart', 'upstreamImages', 'trivySecretScan'], calls*.name)
        assertFalse(agentEntries().any { it.contains('builder') || it.contains('deployer') })
        assertJobStatusFailure()
        assertNotNull(declaration.stages['Pipeline'].post.always)
        assertNull(declaration.post)
    }

    @Test
    void agentAndStageDeclarationsMatchContract() {
        runPipeline()
        assertAgentStructure()
        def pipeline = declaration.stages['Pipeline']
        assertEquals(['Checkout', 'Render', 'Early checks', 'Build', 'Unit test', 'Helm test',
            'Build image', 'Trivy image scan', 'Package and upload chart', 'Trivy chart scan',
            'Helm clean install', 'Helm install', 'Argo deploy', 'Release'],
            pipeline.stages.keySet() as List)
        assertEquals(['Trivy scan', 'Opengrep scan'],
            pipeline.stages['Early checks'].parallel.stages.keySet() as List)
        assertEquals(['trivySecretScan', 'trivyDockerfileScan'],
            calls.findAll { it.stage == 'Trivy scan' }*.name)
        assertNotNull(pipeline.post.always)
        assertNull(declaration.post)
        assertTrue(helper.callStack.any { it.methodName == 'skipDefaultCheckout' })
        assertTrue(helper.callStack.any { it.methodName == 'disableConcurrentBuilds' })
        assertTrue(helper.callStack.any { it.methodName == 'timeout' &&
            it.args[0] == [time: 60, unit: 'MINUTES'] })
        assertTrue(helper.callStack.any { it.methodName == 'buildDiscarder' &&
            it.args[0] == [numToKeepStr: '20', artifactNumToKeepStr: '10'] })
        assertNotNull(declaration.params)
    }

    private void runPipeline() {
        loadScript('vars/javaConfigurablePipeline.groovy').call()
    }

    private void record(String name, Map cfg, String argument = null) {
        observedConfig = cfg
        calls << [name: name, argument: argument, type: cfg.type, version: cfg.version,
            releaseVersion: cfg.releaseVersion, stage: leafStages().last()]
        if (name == failingStep) {
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException('Trivy found a secret.')
        }
    }

    private Map callNamed(String name) { calls.find { it.name == name } }
    private String fixture(String name) { getClass().getResource("/fixtures/config/${name}.yaml").text }
    private void featureBranch() { binding.getVariable('env').BRANCH_NAME = 'feature/DEVSECOPS-34-x' }
    private void deliveryConfig() {
        chartPresent = true
        configText = fixture('jenkins').replace('  test-command:',
            '  build-command: "./gradlew build -x test"\n  test-command:')
            .replace('  pipeline-steps:', '  pipeline-steps:\n    build: true')
            .replace('helm-install: false', 'helm-install: true')
            .replace('helm-clean-install: false', 'helm-clean-install: true')
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
        assertEquals(['tools', 'trivy', 'jdk'], pod.spec.containers*.name)
        ['Build image': 'builder', 'Helm install': 'deployer', 'Helm clean install': 'deployer'].each {
            String name, String parent ->
            assertEquals(parent, pipeline.stages[name].agent.kubernetes.inheritFrom)
            assertEquals(Boolean.TRUE, pipeline.stages[name].when.beforeAgent, name)
        }
    }
}
