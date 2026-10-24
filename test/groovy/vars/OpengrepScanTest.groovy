package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.*

class OpengrepScanTest extends BasePipelineTest {
    private static final String RULES_PATH = 'dev/raulstechtips/jenkins/opengrep-rules.yaml'
    private static final String TOOLS_PATH = 'dev/raulstechtips/jenkins/tools.yaml'
    private static final String REPORT = '.pipeline/reports/opengrep.json'

    private List<String> files
    private List<Map> findFilesCalls
    private List<Map> shCalls
    private List<String> libraryResources
    private List<String> readFiles
    private String currentContainer
    private String rulesText
    private String toolsText
    private String reportText

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        files = []
        findFilesCalls = []
        shCalls = []
        libraryResources = []
        readFiles = []
        currentContainer = null
        rulesText = new File('resources/' + RULES_PATH).text
        toolsText = new File('resources/' + TOOLS_PATH).text
        reportText = new File('test/resources/fixtures/scan/opengrep.json').text

        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            libraryResources << path
            if (path == RULES_PATH) {
                return rulesText
            }
            if (path == TOOLS_PATH) {
                return toolsText
            }
            throw new IllegalArgumentException('unexpected libraryResource ' + path)
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            new Yaml().load(args.text as String)
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            findFilesCalls << args
            List<Pattern> excludes = splitGlobs(args.excludes as String).collect { globToRegex(it) }
            Pattern include = globToRegex(args.glob as String)
            files.findAll { String f ->
                include.matcher(f).matches() && !excludes.any { it.matcher(f).matches() }
            }.collect { String f -> [path: f, name: f.tokenize('/').last()] } as Object[]
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            String previous = currentContainer
            currentContainer = name
            try {
                body.call()
            } finally {
                currentContainer = previous
            }
        }
        helper.registerAllowedMethod('sh', [String]) { String script ->
            shCalls << [container: currentContainer, script: script]
            0
        }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            readFiles << path
            reportText
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
    }

    private static List<String> splitGlobs(String csv) {
        csv == null ? [] : csv.split(',').collect { it.trim() }.findAll { it }
    }

    // Ant-style glob: '**/' spans zero or more directories, '**' spans anything, '*' stays in one segment.
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder()
        int i = 0
        while (i < glob.length()) {
            if (glob.startsWith('**/', i)) {
                sb.append('(.*/)?')
                i += 3
            } else if (glob.startsWith('**', i)) {
                sb.append('.*')
                i += 2
            } else if (glob.charAt(i) == (char) '*') {
                sb.append('[^/]*')
                i++
            } else {
                sb.append(Pattern.quote(String.valueOf(glob.charAt(i))))
                i++
            }
        }
        Pattern.compile(sb.toString())
    }

    private def scan(List failOn = []) {
        loadScript('vars/opengrepScan.groovy').call(['opengrep-fail-on': failOn])
    }

    private List<Map> inContainer(String name) {
        shCalls.findAll { it.container == name }
    }

    private String scanCommand() {
        List<Map> scans = inContainer('shell')
        assertEquals(1, scans.size(), 'expected exactly one sh call in container shell')
        scans[0].script as String
    }

    private List<String> configValues(String command) {
        def m = command =~ /--config\s+'([^']*)'/
        m.collect { it[1] as String }
    }

    private List<String> echoes() {
        helper.callStack.findAll { it.methodName == 'echo' }.collect { it.args[0].toString() }
    }

    @Test
    void bashAndDockerfileSelectOnlyThoseFolders() {
        files = ['scripts/run.sh', 'Dockerfile', 'README.md']

        scan()

        assertEquals(['.pipeline/opengrep-rules/bash', '.pipeline/opengrep-rules/dockerfile'],
            configValues(scanCommand()).sort())
    }

    @Test
    void readsBothLibraryResourcesAndParsesThemWithReadYaml() {
        files = ['a.sh']

        scan()

        assertTrue(libraryResources.containsAll([RULES_PATH, TOOLS_PATH]))
        assertTrue(shCalls.any { it.script.contains('https://github.com/opengrep/opengrep-rules.git') },
            'repo url must come from the parsed rules yaml')
    }

    @Test
    void detectionUsesMapGlobsAndExcludesGitPipelineAndChartTemplates() {
        files = ['a.sh']

        scan()

        assertEquals(['**/*.sh', '**/Dockerfile', '**/*.Dockerfile', '**/Dockerfile.*', '**/*.yaml', '**/*.yml',
            '**/*.py', '**/*.java', '**/*.js', '**/*.ts', '**/*.go'].sort(), findFilesCalls*.glob.sort())
        findFilesCalls.each { Map call ->
            assertEquals(['.git/**', '.pipeline/**', 'charts/**/templates/**'], splitGlobs(call.excludes as String))
        }
    }

    @Test
    void chartTemplatesGitAndPipelineFilesDoNotSelectLanguages() {
        files = ['charts/app/templates/cm.yaml', 'charts/app/templates/_helpers.sh', '.git/hooks/pre.sh',
            '.pipeline/bin/x.py']

        scan()

        assertTrue(shCalls.isEmpty(), 'nothing should run when only excluded files exist')
    }

    @Test
    void yamlOutsideChartTemplatesIsDetected() {
        files = ['charts/app/values.yaml', 'charts/app/templates/cm.yaml']

        scan()

        assertEquals(['.pipeline/opengrep-rules/yaml'], configValues(scanCommand()))
    }

    @Test
    void noLanguageLogsAndReturnsWithoutContainersOrReport() {
        files = ['README.md', 'LICENSE']

        def result = scan(['ERROR'])

        assertTrue(shCalls.isEmpty())
        assertTrue(readFiles.isEmpty())
        assertTrue(echoes().any { it.toLowerCase().contains('no ') && it.toLowerCase().contains('language') },
            'a log line explaining the skip is expected: ' + echoes())
        assertNull(result)
    }

    @Test
    void toolsContainerDownloadsVerifiesAndPinsRules() {
        files = ['a.sh']

        scan()

        List<Map> tools = inContainer('tools')
        assertEquals(1, tools.size())
        String script = tools[0].script
        Map toolsYaml = new Yaml().load(toolsText)
        Map rules = new Yaml().load(rulesText)
        String sha = toolsYaml.opengrep.sha256
        assertTrue(script.startsWith('set -eu') || script.startsWith('set -e'), script)
        assertTrue(script.contains("curl -fsSL -o '.pipeline/bin/opengrep' '${toolsYaml.opengrep.url}'".toString()), script)
                assertTrue(script.contains("echo '${sha}  .pipeline/bin/opengrep' | sha256sum -c -".toString()), script)
        assertTrue((script =~ /sha256sum\s+-c/).find(), 'sha256sum -c check expected')
        assertTrue(script.contains("chmod +x '.pipeline/bin/opengrep'"))
        assertTrue(script.indexOf('sha256sum') < script.indexOf('chmod +x'), 'verify before chmod')
        assertTrue(script.contains("git init '.pipeline/opengrep-rules'"))
        assertTrue(script.contains("git -C '.pipeline/opengrep-rules' fetch --depth 1 '${rules.repo}' '${rules.commit}'".toString()), script)
        assertTrue(script.contains("git -C '.pipeline/opengrep-rules' checkout FETCH_HEAD"))
        assertTrue(shCalls.indexOf(tools[0]) < shCalls.indexOf(inContainer('shell')[0]), 'tools runs before shell')
    }

    @Test
    void shellContainerRunsExactScanCommand() {
        files = ['x.sh']

        scan()

        assertTrue(scanCommand().contains("'.pipeline/bin/opengrep' scan --config '.pipeline/opengrep-rules/bash' " +
            '--json --output .pipeline/reports/opengrep.json --exclude charts --exclude .pipeline .'), scanCommand())
        assertTrue(inContainer('tools').every { !it.script.contains('opengrep scan') })
    }

    @Test
    void neverUsesRegistryRules() {
        files = ['a.sh', 'Dockerfile', 'a.yaml', 'a.py', 'A.java', 'a.js', 'a.ts', 'a.go']

        scan()

        String all = shCalls*.script.join('\n')
        assertFalse((all =~ /--config\s+'?(auto|p\/|r\/|https?:)/).find(), all)
        assertFalse(all.contains('semgrep.dev'))
        List<String> configs = configValues(scanCommand())
        assertEquals(8, configs.size())
        configs.each { assertTrue(it.startsWith('.pipeline/opengrep-rules/'), it) }
    }

    @Test
    void emptyFailOnOnlyWarnsAboutFixtureFinding() {
        files = ['a.sh']

        scan([])

        assertEquals([REPORT], readFiles)
        assertTrue(echoes().any { it.contains('WARNING') }, echoes().toString())
    }

    @Test
    void failOnWarningFailsOnFixture() {
        files = ['a.sh']

        def failure = assertThrows(IllegalArgumentException) { scan(['WARNING']) }

        assertTrue(failure.message.contains('WARNING'), failure.message)
    }

    @Test
    void failOnErrorPassesWhenFixtureHasOnlyWarning() {
        files = ['a.sh']

        scan(['ERROR'])

        assertTrue(echoes().any { it.contains('WARNING') })
    }

    @Test
    void failOnErrorFailsWhenReportHasAnErrorFinding() {
        files = ['a.sh']
        reportText = '{"results":[{"check_id":"rules.bash.demo","path":"a.sh","extra":{"severity":"ERROR"}}]}'

        def failure = assertThrows(IllegalArgumentException) { scan(['ERROR']) }

        assertTrue(failure.message.contains('ERROR'), failure.message)
    }

    @Test
    void nonMatchingFailListDoesNotFailOnErrorFinding() {
        files = ['a.sh']
        reportText = '{"results":[{"check_id":"rules.bash.demo","path":"a.sh","extra":{"severity":"ERROR"}}]}'

        scan(['WARNING'])

        assertTrue(echoes().any { it.contains('ERROR') })
    }

    @Test
    void logsOneSummaryLinePerSeverity() {
        files = ['a.sh']

        scan(['ERROR'])

        Map expected = [ERROR: '0', WARNING: '1', INFO: '0']
        expected.each { String severity, String count ->
            List<String> lines = echoes().findAll { it.startsWith('opengrep ' + severity + ' findings:') }
            assertEquals(1, lines.size(), severity + ' lines: ' + echoes())
            assertTrue(lines[0].endsWith(': ' + count), lines[0])
        }
        assertFalse(echoes().any { it.contains('ERROR') && it.contains('WARNING') && it.contains('INFO') }, echoes().toString())
    }

    @Test
    void emptyReportFailsInsteadOfClaimingSuccess() {
        files = ['a.sh']
        reportText = ''

        assertThrows(IllegalArgumentException) { scan(['ERROR']) }

        assertFalse(echoes().any { it.toLowerCase().contains('no findings') })
    }

    @Test
    void ruleMapWithoutLanguagesFails() {
        rulesText = 'repo: https://github.com/opengrep/opengrep-rules.git\ncommit: f1d2b562b414783763fd02a6ed2736eaed622efa\n'
        files = ['a.sh']

        def failure = assertThrows(IllegalArgumentException) { scan() }

        assertTrue(failure.message.contains('languages'), failure.message)
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void languageFolderWithoutGlobsFailsNamingTheFolder() {
        rulesText = 'repo: https://github.com/opengrep/opengrep-rules.git\n' +
            'commit: f1d2b562b414783763fd02a6ed2736eaed622efa\nlanguages:\n  bash:\n  go:\n    - "**/*.go"\n'
        files = ['a.go']

        def failure = assertThrows(IllegalArgumentException) { scan() }

        assertTrue(failure.message.contains('bash'), failure.message)
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void hostileFolderNameStaysInertInScanCommand() {
        rulesText = 'repo: https://github.com/opengrep/opengrep-rules.git\n' +
            'commit: f1d2b562b414783763fd02a6ed2736eaed622efa\nlanguages:\n  "x y;$(id)\'":\n    - "**/*.sh"\n'
        files = ['a.sh']

        scan()

        assertTrue(scanCommand().contains("--config '.pipeline/opengrep-rules/x y;\$(id)'\\''' --json"), scanCommand())
    }

    @Test
    void hostileUrlAndRepoStayInertInToolsScript() {
        toolsText = toolsText.replace('url: https://github.com/opengrep/opengrep/releases/download/v1.32.0/opengrep_manylinux_x86',
            'url: "https://e.com/a b;$(id)"')
        rulesText = rulesText.replace('repo: https://github.com/opengrep/opengrep-rules.git', 'repo: "https://e.com/r;$(id)\'.git"')
        files = ['a.sh']

        scan()

        String script = inContainer('tools')[0].script
        assertTrue(script.contains("'https://e.com/a b;\$(id)'"), script)
        assertTrue(script.contains("'https://e.com/r;\$(id)'\\''.git'"), script)
    }

    @Test
    void malformedPinsFailBeforeAnyShell() {
        files = ['a.sh']
        toolsText = toolsText.replace('sha256: 7360d601', 'sha256: zzzzzzzz')

        assertThrows(IllegalArgumentException) { scan() }
        assertTrue(shCalls.isEmpty())

        setUp()
        files = ['a.sh']
        rulesText = rulesText.replace('commit: f1d2b562', 'commit: main; rm -rf / #')
        assertThrows(IllegalArgumentException) { scan() }
        assertTrue(shCalls.isEmpty())
    }
}
