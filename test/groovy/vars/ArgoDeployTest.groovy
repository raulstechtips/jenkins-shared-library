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
    private String openPr
    private String remoteBranch
    private boolean missing
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
        openPr = ''
        remoteBranch = ''
        missing = false
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
            assertEquals('argo/dev/' + cfg['project-name'] + '.yaml', path)
            !missing
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
            Map application = new Yaml().load(fixture)
            return application.spec.sources.findAll { it.chart == environment.CHART }
                .collect { it.targetRevision }.join('\n') + '\n'
        }
        if (script.contains('git diff --numstat')) { return numstat }
        if (script.contains('state=open')) { return openPr }
        if (script.contains('git ls-remote')) { return remoteBranch }
        if (script.contains('-X POST')) { return 'https://github.com/raulstechtips/cluster-config/pull/42\n' }
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
        assertEquals(4, authenticated.size())
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
        assertTrue(logs.contains('Already at ' + cfg.version + '. No PR.'))
        assertFalse(ran('curl'))
        assertFalse(ran('git push'))
        assertFalse(ran('yq -i'))
    }

    @Test
    void failsWhenNoChartSourceMatches() {
        fixture = fixture.replace('chart: jenkins', 'ref: not-a-chart')
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('No source with chart jenkins in argo/dev/jenkins.yaml', failure.message)
        assertFalse(ran('curl'))
    }

    @Test
    void failsWhenArgoFileIsMissing() {
        missing = true
        def failure = assertThrows(IllegalArgumentException) { deploy() }
        assertEquals('argo/dev/jenkins.yaml is missing', failure.message)
        assertFalse(ran('yq '))
    }

    @Test
    void usesExactProjectDevVersionBranch() {
        deploy()
        assertEquals('argo-deploy/jenkins-dev-' + cfg.version, command('git checkout -b').env.BRANCH)
        assertTrue(command('git checkout -b').script.contains('"$BRANCH"'))
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
    void acceptsExactlyOneAddedAndRemovedLineForTargetFile() {
        deploy()
        assertTrue(ran('git diff --numstat'))
        assertTrue(ran('git commit'))
    }

    @Test
    void rejectsAdditionalChangedFile() {
        numstat += '1\t1\targo/dev/argocd.yaml\n'
        assertThrows(IllegalArgumentException) { deploy() }
        assertFalse(ran('git commit'))
        assertFalse(ran('git push'))
        assertFalse(ran('-X POST'))
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
    void commitsWithBotIdentityAndExactMessage() {
        deploy()
        String scripts = commands*.script.join('\n')
        assertTrue(scripts.contains('raulstechtips-jenkins[bot]'))
        assertTrue(scripts.contains('raulstechtips-jenkins[bot]@users.noreply.github.com'))
        assertEquals('deploy(jenkins): dev ' + cfg.version, command('git commit').env.TITLE)
        assertTrue(command('git commit').script.contains('-m "$TITLE"'))
    }

    @Test
    void postsPrToMainWithExactTitleAndBuildUrlBody() {
        deploy()
        Map post = command('-X POST')
        assertTrue(post.script.contains('https://api.github.com/repos/raulstechtips/cluster-config/pulls'))
        assertTrue(post.script.contains('base: "main"'))
        assertTrue(post.script.contains('--arg title "$TITLE"'))
        assertEquals('deploy(jenkins): dev ' + cfg.version, post.env.TITLE)
        assertTrue(post.script.contains('Opened by Jenkins build $BUILD_URL. Merge by hand.'))
        assertTrue(logs.contains('https://github.com/raulstechtips/cluster-config/pull/42'))
    }

    @Test
    void buildsJsonWithJqArgumentsRatherThanConcatenation() {
        deploy()
        String script = command('-X POST').script
        assertTrue(script.contains('jq -n --arg'))
        assertTrue(script.contains('--arg head "$BRANCH"'))
        assertTrue(script.contains('--arg body'))
        assertTrue(script.contains('title: $title'))
        assertTrue(script.contains('head: $head'))
        assertTrue(script.contains('body: $body'))
    }

    @Test
    void parsesApiJsonWithJqWithoutEvaluatingUntrustedTitles() {
        deploy()
        assertTrue(command('state=open').script.contains('jq -'))
        assertTrue(command('-X POST').script.contains('jq -er'))
        assertFalse(commands*.script.join('\n').contains('eval '))
    }

    @Test
    void neverCallsAutoMergeOrMergeApi() {
        deploy()
        String scripts = commands*.script.join('\n')
        assertFalse(scripts.contains('/merge'))
        assertFalse(scripts.contains('auto-merge'))
        assertFalse(scripts.contains('enablePullRequestAutoMerge'))
        assertFalse(scripts.contains('gh pr merge'))
    }

    @Test
    void existingOpenPrLogsUrlWithoutPushOrCreate() {
        openPr = 'https://github.com/raulstechtips/cluster-config/pull/21\n'
        deploy()
        assertTrue(logs.contains(openPr.trim()))
        assertFalse(ran('git push'))
        assertFalse(ran('-X POST'))
        assertFalse(ran('git ls-remote'))
        assertFalse(ran('yq -i'))
        assertEquals('argo-deploy/jenkins-dev-' + cfg.version, command('state=open').env.BRANCH)
        assertTrue(command('state=open').script.contains('head=raulstechtips:$BRANCH&state=open'))
    }

    @Test
    void existingRemoteBranchOnlyCreatesPr() {
        remoteBranch = 'abcdef\trefs/heads/argo-deploy/jenkins-dev-' + cfg.version + '\n'
        deploy()
        assertTrue(ran('git fetch'))
        assertTrue(ran('git checkout'))
        assertFalse(ran('git checkout -b'))
        assertFalse(ran('yq -i'))
        assertFalse(ran('git diff'))
        assertFalse(ran('git commit'))
        assertFalse(ran('git push'))
        assertEquals(1, commands.count { it.script.contains('-X POST') })
    }

    @Test
    void bindsCredentialsOnlyAroundClonePushAndApiCalls() {
        deploy()
        commands.each {
            boolean needsToken = it.script.contains('git clone') ||
                it.script.contains('git push') || it.script.contains('curl ')
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
        assertEquals('argo-deploy/' + cfg['project-name'] + '-dev-' + cfg.version,
            command('git checkout -b').env.BRANCH)
        assertEquals('deploy(' + cfg['project-name'] + '): dev ' + cfg.version,
            command('git commit').env.TITLE)
        assertTrue(command('yq -i').script.contains('"$FILE"'))
        assertTrue(command('git checkout -b').script.contains('"$BRANCH"'))
        assertTrue(command('git commit').script.contains('"$TITLE"'))
        assertTrue(command('-X POST').script.contains('--arg title "$TITLE"'))
        assertTrue(command('-X POST').script.contains('--arg head "$BRANCH"'))
        assertTrue(command('-X POST').script.contains('$BUILD_URL. Merge by hand."'))
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
        assertTrue(ran('-X POST'))
    }

    @Test
    void cloneFailureStopsBeforeLocalEdits() {
        failCommand = 'git clone'
        assertThrows(IllegalStateException) { deploy() }
        assertFalse(ran('yq '))
        assertFalse(credentialsActive)
    }

    @Test
    void pushFailureNeverCreatesPr() {
        failCommand = 'git push'
        assertThrows(IllegalStateException) { deploy() }
        assertFalse(ran('-X POST'))
        assertFalse(credentialsActive)
    }

    @Test
    void apiFailurePropagatesRatherThanReportingSuccess() {
        failCommand = '-X POST'
        assertThrows(IllegalStateException) { deploy() }
        assertFalse(logs.contains('https://github.com/raulstechtips/cluster-config/pull/42'))
        assertFalse(credentialsActive)
    }
}
