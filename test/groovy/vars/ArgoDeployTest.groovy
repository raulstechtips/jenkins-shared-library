package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import static org.junit.jupiter.api.Assertions.*

class ArgoDeployTest extends BasePipelineTest {
    private Map cfg
    private Map environment
    private List<Map> commands
    private List<Map> bindings
    private List<String> logs
    private List<String> yamlReads
    private String fixture
    private String chart
    private String numstat
    private Map<String, String> fixtures
    private Map<String, String> numstats
    private Set<String> missingFiles
    private List<String> checkedFiles
    private List<Integer> pushStatuses
    private boolean credentialsActive
    private String directory
    private String activeContainer
    private String failCommand

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        cfg = ['project-name': 'jenkins', version: '1.0.1-main-abcdef0-7']
        environment = [BUILD_URL: 'https://jenkins.example/job/test/7/', BRANCH_NAME: 'main']
        binding.setVariable('env', environment)
        commands = []
        bindings = []
        logs = []
        yamlReads = []
        fixture = new File('test/resources/fixtures/argo/jenkins.yaml').text
        chart = 'jenkins'
        numstat = '1\t1\targo/dev/jenkins.yaml\n'
        fixtures = [:]
        numstats = [:]
        missingFiles = [] as Set
        checkedFiles = []
        pushStatuses = []
        credentialsActive = false
        directory = ''
        activeContainer = ''
        failCommand = null
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertFalse(credentialsActive)
            yamlReads << args.file
            assertEquals('charts/Chart.yaml', args.file)
            [name: chart, version: '1.0.0']
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            activeContainer = name
            try { body.call() } finally { activeContainer = '' }
        }
        helper.registerAllowedMethod('dir', [String, Closure]) { String path, Closure body ->
            directory = path
            try { body.call() } finally { directory = '' }
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List entries, Closure body ->
            Map previous = new LinkedHashMap(environment)
            entries.each { String entry ->
                int separator = entry.indexOf('=')
                environment[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
            try { body.call() } finally {
                environment.clear()
                environment.putAll(previous)
            }
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List args, Closure body ->
            assertFalse(credentialsActive)
            bindings.addAll(args)
            credentialsActive = true
            try { body.call() } finally { credentialsActive = false }
        }
        helper.registerAllowedMethod('fileExists', [String]) { String path ->
            assertFalse(credentialsActive)
            List<String> names = cfg['argo-deploy-targets'] ?: [cfg['project-name']]
            assertTrue(names.collect { 'argo/dev/' + it + '.yaml' }.contains(path), path)
            checkedFiles << path
            !missingFiles.contains(path)
        }
        helper.registerAllowedMethod('echo', [String]) { String message -> logs << message }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
        helper.registerAllowedMethod('sh', [String]) { String script -> shell([script: script]) }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> shell(args) }
    }

    private Object shell(Map args) {
        assertTrue(args.script instanceof String, 'Shell scripts must not be Groovy GStrings')
        String script = args.script
        commands << [script: script, credentials: credentialsActive,
            env: new LinkedHashMap(environment), directory: directory, container: activeContainer]
        if (failCommand && script.contains(failCommand)) {
            throw new IllegalStateException('command failed')
        }
        if (script.contains('yq ') && !script.contains('yq -i')) {
            Map application = new Yaml().load(fixtures[environment.FILE] ?: fixture)
            return application.spec.sources.findAll { it.chart == environment.CHART }
                .collect { it.targetRevision }.join('\n') + '\n'
        }
        if (script.contains('git diff --numstat')) { return numstats[environment.FILE] ?: numstat }
        if (script.contains('git push')) {
            assertEquals(true, args.returnStatus)
            return pushStatuses ? pushStatuses.remove(0) : 0
        }
        args.returnStdout ? '' : null
    }

    private void deploy() {
        loadScript('vars/argoDeploy.groovy').call(cfg)
    }

    private Map command(String text) {
        Map result = commands.find { it.script.contains(text) }
        assertNotNull(result, 'Missing command: ' + text)
        result
    }

    private boolean ran(String text) {
        commands.any { it.script.contains(text) }
    }

    private List<Map> all(String text) {
        commands.findAll { it.script.contains(text) }
    }

    @Test
    void bindsGithubAppPasswordAsGhToken() {
        deploy()
        assertFalse(bindings.empty)
        assertTrue(bindings.every { it == [credentialsId: 'github-app',
            usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN'] })
    }

    @Test
    void tokenRemainsLiteralInNonInterpolatedShellWithTracingDisabled() {
        deploy()
        List<Map> authenticated = commands.findAll { it.credentials }
        assertEquals(2, authenticated.size())
        authenticated.each {
            assertTrue(it.script.startsWith('set +x\n'))
            assertTrue(it.script.contains('$GH_TOKEN'))
        }
    }

    @Test
    void clonesMainAtDepthOneInToolsContainer() {
        deploy()
        Map clone = command('git clone')
        assertTrue(clone.script.contains('--depth 1'))
        assertTrue(clone.script.contains('--branch main'))
        assertTrue(clone.script.contains('https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git'))
        assertTrue(clone.script.contains('.pipeline/cluster-config'))
        assertTrue(commands.every { it.container == 'tools' })
    }

    @Test
    void removesTokenFromStoredOriginBeforeLeavingCloneScope() {
        deploy()
        assertTrue(command('git clone').script.contains(
            'remote set-url origin https://github.com/raulstechtips/cluster-config.git'))
    }

    @Test
    void skipsWhenVersionIsAlreadySet() {
        cfg.version = '1.0.0-main-0067101-13'
        deploy()
        assertTrue(logs.contains('argo/dev/jenkins.yaml is already at ' + cfg.version + '. No commit.'))
        assertFalse(ran('git push'))
        assertFalse(ran('yq -i'))
        assertFalse(ran('git commit'))
    }

    @Test
    void failsWhenNoChartSourceMatches() {
        fixture = fixture.replace('chart: jenkins', 'ref: not-a-chart')
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('No source with chart jenkins in argo/dev/jenkins.yaml', failure.message)
        assertFalse(ran('git push'))
    }

    @Test
    void failsWhenArgoFileIsMissing() {
        missingFiles << 'argo/dev/jenkins.yaml'
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('argo/dev/jenkins.yaml is missing', failure.message)
        assertFalse(ran('yq '))
    }

    @Test
    void pushesStraightToClusterConfigMainWithoutBranchOrPr() {
        deploy()
        Map push = command('git push')
        assertTrue(push.script.contains(
            'git push "https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git" HEAD:refs/heads/main'))
        assertEquals('.pipeline/cluster-config', push.directory)
        String scripts = commands*.script.join('\n')
        assertFalse(scripts.contains('git checkout'))
        assertFalse(scripts.contains('curl'))
        assertFalse(scripts.contains('/pulls'))
        assertTrue(logs.contains('Pushed 1 deploy commit(s) for ' + cfg.version + ' to cluster-config main.'))
    }

    @Test
    void selectsChartSourceUsingEnvironmentInBothYqCommands() {
        deploy()
        String select = '(.spec.sources[] | select(.chart == strenv(CHART))).targetRevision'
        assertTrue(command('yq ').script.contains(select))
        assertTrue(command('yq -i').script.contains(select + ' = strenv(VERSION)'))
        assertEquals(cfg.version, command('yq -i').env.VERSION)
        assertEquals('jenkins', command('yq -i').env.CHART)
        assertTrue(command('yq -i').script.contains('"$FILE"'))
    }

    @Test
    void rejectsAdditionalChangedFile() {
        numstat += '1\t1\targo/dev/argocd.yaml\n'
        assertThrows(IllegalArgumentException) { deploy() }
        assertFalse(ran('git commit'))
        assertFalse(ran('git push'))
    }

    @Test
    void rejectsWrongLineCount() {
        numstat = '2\t1\targo/dev/jenkins.yaml\n'
        assertThrows(IllegalArgumentException) { deploy() }
        assertFalse(ran('git commit'))
    }

    @Test
    void rejectsWrongChangedFile() {
        numstat = '1\t1\targo/dev/argocd.yaml\n'
        assertThrows(IllegalArgumentException) { deploy() }
        assertFalse(ran('git commit'))
    }

    @Test
    void rejectsSpaceSeparatedNumstat() {
        numstat = '1 1 argo/dev/jenkins.yaml\n'
        assertThrows(IllegalArgumentException) { deploy() }
        assertFalse(ran('git commit'))
    }

    @Test
    void commitsOnlyTargetFileWithBotIdentityExactMessageAndBuildUrl() {
        deploy()
        Map identity = command('git config user.name')
        assertTrue(identity.script.contains("git config user.name 'raulstechtips-jenkins[bot]'"))
        assertTrue(identity.script.contains("git config user.email 'raulstechtips-jenkins[bot]@users.noreply.github.com'"))
        Map commit = command('git commit')
        assertEquals('git commit -m "$TITLE" -m "Jenkins build: $BUILD_URL" -- "$FILE"', commit.script)
        assertEquals('deploy(jenkins): dev ' + cfg.version, commit.env.TITLE)
        assertEquals('argo/dev/jenkins.yaml', commit.env.FILE)
        assertFalse(commit.credentials)
    }

    @Test
    void rejectedPushFetchesMainAgainAndRedoesTheCommits() {
        pushStatuses = [1, 0]
        deploy()
        assertEquals(2, all('git push').size())
        assertEquals(1, all('git fetch').size())
        Map fetch = command('git fetch')
        assertTrue(fetch.credentials)
        assertTrue(fetch.script.startsWith('set +x\n'))
        assertTrue(fetch.script.contains('--depth 1'))
        assertTrue(fetch.script.contains('git reset --hard FETCH_HEAD'))
        assertEquals(2, all('git commit').size())
        assertEquals(2, all('yq -i').size())
        assertTrue(logs.contains('cluster-config main moved. Attempt 2 of 3.'))
    }

    @Test
    void threeRejectedPushesFailTheBuild() {
        pushStatuses = [1, 1, 1]
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('Could not push the deploy commits for ' + cfg.version +
            ' to cluster-config main after 3 attempts.', failure.message)
        assertEquals(3, all('git push').size())
        assertEquals(2, all('git fetch').size())
        assertFalse(credentialsActive)
    }

    @Test
    void neverOpensPrOrCallsMergeApi() {
        deploy()
        String scripts = commands*.script.join('\n')
        assertFalse(scripts.contains('/merge'))
        assertFalse(scripts.contains('/pulls'))
        assertFalse(scripts.contains('gh pr'))
    }

    @Test
    void bindsCredentialsOnlyAroundCloneFetchAndPush() {
        pushStatuses = [1, 0]
        deploy()
        commands.each {
            boolean needsToken = it.script.contains('git clone') ||
                it.script.contains('git push') || it.script.contains('git fetch')
            assertEquals(needsToken, it.credentials, it.script)
        }
        assertFalse(command('yq -i').credentials)
        assertFalse(command('git diff').credentials)
        assertFalse(credentialsActive)
    }

    @Test
    void readsChartNameFromChartYamlRatherThanProjectName() {
        chart = 'wrapper-chart'
        fixture = fixture.replace('chart: jenkins', 'chart: wrapper-chart')
        deploy()
        assertEquals(['charts/Chart.yaml'], yamlReads)
        assertEquals('wrapper-chart', command('yq -i').env.CHART)
        assertEquals('argo/dev/jenkins.yaml', command('yq -i').env.FILE)
    }

    @Test
    void configAndFileValuesStayInQuotedEnvironmentDespiteShellMetacharacters() {
        String hostile = ' space ; $(printf INJECTION_EXECUTED) \' "'
        cfg['project-name'] = 'jenkins' + hostile
        cfg.version = '1.0.1' + hostile
        chart = 'wrapper' + hostile
        fixture = fixture.replace('chart: jenkins', 'chart: ' + groovy.json.JsonOutput.toJson(chart))
        numstat = '1\t1\targo/dev/' + cfg['project-name'] + '.yaml\n'
        environment.BUILD_URL += hostile
        deploy()
        commands.each { assertFalse(it.script.contains(hostile)) }
        assertEquals(chart, command('yq -i').env.CHART)
        assertEquals(cfg.version, command('yq -i').env.VERSION)
        assertEquals('argo/dev/' + cfg['project-name'] + '.yaml', command('yq -i').env.FILE)
        assertEquals('deploy(' + cfg['project-name'] + '): dev ' + cfg.version,
            command('git commit').env.TITLE)
        assertTrue(command('yq -i').script.contains('"$FILE"'))
        assertTrue(command('git commit').script.contains('"$TITLE"'))
        assertTrue(command('git commit').script.contains('"Jenkins build: $BUILD_URL"'))
    }

    @Test
    void supportsArgocdFixtureWithValuesSourceUntouched() {
        cfg['project-name'] = 'argocd'
        chart = 'argocd'
        fixture = new File('test/resources/fixtures/argo/argocd.yaml').text
        numstat = '1\t1\targo/dev/argocd.yaml\n'
        deploy()
        assertEquals('argocd', command('yq -i').env.CHART)
        assertEquals('argo/dev/argocd.yaml', command('yq -i').env.FILE)
    }

    @Test
    void containsNoBranchGatingInsideStep() {
        environment.BRANCH_NAME = 'feature/DEVSECOPS-34'
        environment.CHANGE_ID = '123'
        deploy()
        assertTrue(ran('git push'))
    }

    @Test
    void cloneFailureStopsBeforeLocalEdits() {
        failCommand = 'git clone'
        assertThrows(IllegalStateException) { deploy() }
        assertFalse(ran('yq '))
        assertFalse(credentialsActive)
    }

    @Test
    void commitFailureStopsBeforePush() {
        failCommand = 'git commit'
        assertThrows(IllegalStateException) { deploy() }
        assertFalse(ran('git push'))
        assertFalse(credentialsActive)
    }

    private void useGhaRunnerTargets() {
        cfg['project-name'] = 'gha-runners'
        cfg['argo-deploy-targets'] = ['gha-runners', 'gha-runners-packer']
        chart = 'gha-runners'
        fixture = fixture.replace('chart: jenkins', 'chart: gha-runners')
        numstats['argo/dev/gha-runners.yaml'] = '1\t1\targo/dev/gha-runners.yaml\n'
        numstats['argo/dev/gha-runners-packer.yaml'] = '1\t1\targo/dev/gha-runners-packer.yaml\n'
    }

    @Test
    void targetsReplaceProjectNameAndCommitEachTargetThenPushOnce() {
        useGhaRunnerTargets()
        deploy()
        assertEquals(['argo/dev/gha-runners.yaml', 'argo/dev/gha-runners-packer.yaml'], checkedFiles)
        assertEquals(['argo/dev/gha-runners.yaml', 'argo/dev/gha-runners-packer.yaml'], all('yq -i')*.env.FILE)
        assertEquals(['deploy(gha-runners): dev ' + cfg.version, 'deploy(gha-runners-packer): dev ' + cfg.version],
            all('git commit')*.env.TITLE)
        assertEquals(1, all('git clone').size())
        assertEquals(1, all('git push').size())
        assertTrue(logs.contains('Pushed 2 deploy commit(s) for ' + cfg.version + ' to cluster-config main.'))
    }

    @Test
    void targetsDoNotIncludeProjectNameUnlessListed() {
        cfg['argo-deploy-targets'] = ['jenkins-agents']
        numstat = '1\t1\targo/dev/jenkins-agents.yaml\n'
        deploy()
        assertEquals(['argo/dev/jenkins-agents.yaml'], checkedFiles)
        assertEquals('deploy(jenkins-agents): dev ' + cfg.version, command('git commit').env.TITLE)
    }

    @Test
    void emptyTargetsUseProjectName() {
        cfg['argo-deploy-targets'] = []
        deploy()
        assertEquals(['argo/dev/jenkins.yaml'], checkedFiles)
        assertEquals('deploy(jenkins): dev ' + cfg.version, command('git commit').env.TITLE)
    }

    @Test
    void missingLaterTargetFailsBeforeAnyCommit() {
        useGhaRunnerTargets()
        missingFiles << 'argo/dev/gha-runners-packer.yaml'
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('argo/dev/gha-runners-packer.yaml is missing', failure.message)
        assertFalse(ran('yq -i'))
        assertFalse(ran('git commit'))
        assertFalse(ran('git push'))
    }

    @Test
    void laterTargetWithoutChartSourceFailsBeforeAnyCommit() {
        useGhaRunnerTargets()
        fixtures['argo/dev/gha-runners-packer.yaml'] = fixture.replace('chart: gha-runners', 'chart: other')
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('No source with chart gha-runners in argo/dev/gha-runners-packer.yaml', failure.message)
        assertFalse(ran('git commit'))
        assertFalse(ran('git push'))
    }

    @Test
    void targetAlreadyAtVersionIsSkippedWhileOthersDeploy() {
        useGhaRunnerTargets()
        fixtures['argo/dev/gha-runners-packer.yaml'] = fixture.replace('1.0.0-main-0067101-13', cfg.version)
        deploy()
        assertTrue(logs.contains('argo/dev/gha-runners-packer.yaml is already at ' + cfg.version + '. No commit.'))
        assertEquals(['deploy(gha-runners): dev ' + cfg.version], all('git commit')*.env.TITLE)
        assertEquals(1, all('git push').size())
    }
}
