package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import java.nio.file.Files
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class ReleasePipelineTest extends BasePipelineTest {
    private Script step
    private Map cfg
    private Map environment
    private Map<String, String> files
    private List<Map> commands
    private List<String> containers
    private List<Map> credentials
    private String remote
    private String tags
    private String existingTag
    private int pushStatus
    private boolean credentialScope
    private final String token = 'test-only-secret-sentinel'

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        cfg = [type: 'cluster', releaseVersion: '1.10.0', version: '1.10.0']
        environment = [:]
        binding.setVariable('env', environment)
        files = [
            'charts/Chart.yaml': 'name: demo\nversion: 1.9.0\n',
            'gradle.properties': '# version=keep\nname=demo\nversion=1.9.0\nother.version=keep\n',
            'CHANGELOG.md': '# Changelog\n\n## [Unreleased]\n\n### Added\n\n- New feature\n\n' +
                '[Unreleased]: https://github.com/raulstechtips/demo/commits/main\n'
        ]
        commands = []
        containers = []
        credentials = []
        remote = 'https://github.com/raulstechtips/demo.git\n'
        tags = 'a\trefs/tags/1.2.0\nb\trefs/tags/1.9.0\nc\trefs/tags/1.9.0^{}\n' +
            'd\trefs/tags/1.9.9-rc1\ne\trefs/tags/v9.0.0\nf\trefs/tags/1.3.0\n' +
            'g\trefs/tags/1.9.10\nh\trefs/tags/1.9.9\n'
        existingTag = ''
        pushStatus = 0
        credentialScope = false
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers << name
            body()
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            Map saved = new LinkedHashMap(environment)
            values.each { String value ->
                int equals = value.indexOf('=')
                environment[value.substring(0, equals)] = value.substring(equals + 1)
            }
            try {
                body()
            } finally {
                environment.clear()
                environment.putAll(saved)
            }
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args ->
            credentials << args
            args
        }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List values, Closure body ->
            assertEquals('github-app', values[0].credentialsId)
            assertEquals('GH_TOKEN', values[0].passwordVariable)
            assertTrue(values[0].usernameVariable instanceof String)
            environment.GH_TOKEN = token
            credentialScope = true
            try {
                body()
            } finally {
                environment.remove('GH_TOKEN')
                credentialScope = false
            }
        }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> files.containsKey(path) }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            assertTrue(files.containsKey(path), "unexpected file read: ${path}")
            files[path]
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals(files['charts/Chart.yaml'], args.text)
            [name: 'demo', version: args.text.readLines().find { it.startsWith('version: ') }?.substring(9)]
        }
        helper.registerAllowedMethod('writeFile', [Map]) { Map args ->
            assertTrue(args.file in ['CHANGELOG.md', 'gradle.properties'])
            assertFalse(credentialScope, 'file writes must not hold credentials')
            files[args.file] = args.text
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalStateException(message)
        }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> shell(args) }
        helper.registerAllowedMethod('sh', [String]) { String script -> shell([script: script]) }
        step = loadScript('vars/releasePipeline.groovy')
    }

    private Object shell(Map args) {
        String script = args.script.toString()
        commands << [script: script, environment: new LinkedHashMap(environment), credentials: credentialScope]
        assertFalse(script.contains(token), 'secret was interpolated into sh')
        if (script.contains('git ls-remote') || script.contains('git push')) {
            assertTrue(credentialScope)
            assertTrue(script.startsWith('set +x\n'))
            assertTrue(script.contains('$GH_TOKEN') || script.contains('${GH_TOKEN}'))
        } else {
            assertFalse(credentialScope, 'credentials must wrap only authenticated git calls')
        }
        if (script == 'git remote get-url origin') {
            return remote
        }
        if (script.contains('git ls-remote --tags origin refs/tags/') ||
                script.contains('git ls-remote --tags origin "refs/tags/$V"')) {
            assertEquals(true, args.returnStdout)
            return existingTag
        }
        if (script.contains('git ls-remote --tags origin')) {
            assertEquals(true, args.returnStdout)
            return tags
        }
        if (script == 'date -u +%Y-%m-%d') {
            return '2026-10-24\n'
        }
        if (script.contains('git push')) {
            if (args.returnStatus) {
                return pushStatus
            }
            if (pushStatus != 0) {
                throw new IllegalStateException("script returned exit code ${pushStatus}")
            }
        }
        return args.returnStdout ? '' : 0
    }

    @Test
    void rejectsMissingPatch() {
        cfg.releaseVersion = '1.0'
        assertPrepareFails('X.Y.Z')
    }

    @Test
    void rejectsVersionPrefix() {
        cfg.releaseVersion = 'v1.0.0'
        assertPrepareFails('X.Y.Z')
    }

    @Test
    void rejectsPrereleaseVersion() {
        cfg.releaseVersion = '1.0.0-rc1'
        assertPrepareFails('X.Y.Z')
    }

    @Test
    void rejectsEqualVersion() {
        cfg.releaseVersion = '1.9.0'
        assertPrepareFails('greater')
    }

    @Test
    void rejectsLowerVersionNumerically() {
        files['charts/Chart.yaml'] = 'version: 1.10.0\n'
        cfg.releaseVersion = '1.9.0'
        assertPrepareFails('greater')
    }

    @Test
    void rejectsMalformedVersionFile() {
        files['charts/Chart.yaml'] = 'version: 1.0\n'
        assertPrepareFails('Version file charts/Chart.yaml must contain X.Y.Z')
    }

    @Test
    void rejectsExistingTag() {
        existingTag = 'deadbeef\trefs/tags/1.10.0\n'
        assertPrepareFails('already exists')
    }

    @Test
    void rejectsMissingChangelog() {
        files.remove('CHANGELOG.md')
        assertPrepareFails('CHANGELOG.md')
    }

    @Test
    void rejectsEmptyUnreleasedSection() {
        files['CHANGELOG.md'] = '# Changelog\n\n## [Unreleased]\n\n### Added\n\n'
        assertPrepareFails('Unreleased')
    }

    @Test
    void rejectsBulletsOnlyInPreviousRelease() {
        files['CHANGELOG.md'] = '# Changelog\n\n## [Unreleased]\n\n## [1.9.0]\n\n- Old\n'
        assertPrepareFails('Unreleased')
    }

    @Test
    void prepareRunsInToolsContainer() {
        step.prepare(cfg)
        assertEquals(['tools'], containers)
    }

    @Test
    void clusterWritesChartUsingVersionEnvironment() {
        step.prepare(cfg)
        def command = commands.find { it.script.contains('yq -i') }
        assertNotNull(command)
        assertEquals('yq -i \'.version = strenv(V)\' charts/Chart.yaml', command.script)
        assertEquals('1.10.0', command.environment.V)
        assertEquals('1.10.0', cfg.version)
    }

    @Test
    void javaReplacesOnlyVersionLinePreservingOtherProperties() {
        cfg.type = 'java'
        step.prepare(cfg)
        assertEquals('# version=keep\nname=demo\nversion=1.10.0\nother.version=keep\n',
            files['gradle.properties'])
        assertFalse(commands.any { it.script.contains('yq') })
        assertTrue(commands.any { it.script.contains('git add CHANGELOG.md gradle.properties') })
    }

    @Test
    void changelogUsesUtcDateAndHighestNumericReleaseTag() {
        step.prepare(cfg)
        assertTrue(commands.any { it.script == 'date -u +%Y-%m-%d' })
        assertTrue(files['CHANGELOG.md'].contains('## [1.10.0] - 2026-10-24'))
        assertTrue(files['CHANGELOG.md'].contains('/compare/1.9.10...1.10.0'))
        assertTrue(files['CHANGELOG.md'].contains('/compare/1.10.0...HEAD'))
    }

    @Test
    void firstReleaseUsesTagLink() {
        tags = 'a\trefs/tags/v1.0.0\nb\trefs/tags/1.0.0-rc1\n'
        step.prepare(cfg)
        assertTrue(files['CHANGELOG.md'].contains('/releases/tag/1.10.0'))
    }

    @Test
    void prepareCreatesLocalCommitAndAnnotatedTagWithBotIdentity() {
        step.prepare(cfg)
        String all = commands*.script.join('\n')
        assertTrue(all.contains('git add CHANGELOG.md charts/Chart.yaml'))
        assertTrue(all.contains("git -c user.name='raulstechtips-jenkins[bot]' " +
            "-c user.email='raulstechtips-jenkins[bot]@users.noreply.github.com' commit -m \"release: \$V\""))
        assertTrue(all.contains('git tag -a "$V" -m "$V"'))
        def tagCommand = commands.find { it.script.contains('git tag -a') }
        assertEquals('raulstechtips-jenkins[bot]', tagCommand.environment.GIT_COMMITTER_NAME)
        assertEquals('raulstechtips-jenkins[bot]@users.noreply.github.com', tagCommand.environment.GIT_COMMITTER_EMAIL)
    }

    @Test
    void prepareNeverPushes() {
        step.prepare(cfg)
        assertFalse(commands.any { it.script.contains('git push') })
    }

    @Test
    void tagLookupAuthenticatesOriginWithEnvironmentOnlyToken() {
        step.prepare(cfg)
        assertEquals(2, commands.count { it.script.contains('git ls-remote --tags origin') })
        assertTrue(commands.any { it.script.contains('refs/tags/$V') && it.environment.V == '1.10.0' })
        assertEquals(1, credentials.size())
    }

    @Test
    void finishPushesCommitAndTagAtomicallyFromHttpsOrigin() {
        step.finish(cfg)
        assertAtomicPush('raulstechtips/demo')
    }

    @Test
    void finishHandlesScpStyleSshOrigin() {
        remote = 'git@github.com:raulstechtips/other.git\n'
        step.finish(cfg)
        assertAtomicPush('raulstechtips/other')
    }

    @Test
    void finishHandlesSshProtocolOrigin() {
        remote = 'ssh://git@github.com/raulstechtips/other.git\n'
        step.finish(cfg)
        assertAtomicPush('raulstechtips/other')
    }

    @Test
    void finishKeepsHostilePushOperandsAsArguments() {
        remote = 'https://github.com/raulstechtips/demo.git\n'
        cfg.releaseVersion = '1.10.0;touch version-pwned'
        step.finish(cfg)

        Map push = commands.find { it.script.contains('git push') }
        String script = push.script
        File dir = Files.createTempDirectory('release-push-test').toFile()
        try {
            File bin = new File(dir, 'bin')
            assertTrue(bin.mkdir())
            File argvFile = new File(dir, 'argv')
            File git = new File(bin, 'git')
            git.text = '#!/bin/sh\nprintf "%s\\0" "$@" > "$ARGV_FILE"\n'
            assertTrue(git.setExecutable(true))
            ProcessBuilder builder = new ProcessBuilder('/bin/sh', '-c', script)
                .directory(dir).redirectErrorStream(true)
            builder.environment().putAll(push.environment)
            builder.environment().REPO_SLUG = 'a/b;touch x'
            builder.environment().V = '1.10.0;touch version-pwned'
            builder.environment().PATH = bin.path
            builder.environment().ARGV_FILE = argvFile.path
            Process process = builder.start()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals([
                'push', '--atomic',
                'https://x-access-token:' + token + '@github.com/a/b;touch x.git',
                'HEAD:refs/heads/main',
                'refs/tags/1.10.0;touch version-pwned',
                ''
            ], new String(argvFile.bytes, 'UTF-8').split('\u0000', -1).toList())
            assertFalse(new File(dir, 'x.git').exists())
            assertFalse(new File(dir, 'version-pwned').exists())
        } finally {
            dir.deleteDir()
        }
    }

    @Test
    void mainMovedMakesFinishFailOnNonzeroPush() {
        pushStatus = 1
        assertThrows(IllegalStateException) { step.finish(cfg) }
        assertEquals(1, commands.count { it.script.contains('git push') })
    }

    @Test
    void noSecretAppearsInRegisteredShScriptStrings() {
        step.prepare(cfg)
        step.finish(cfg)
        def calls = helper.callStack.findAll { it.methodName == 'sh' }
        assertFalse(calls.isEmpty())
        calls.each { call ->
            Object script = call.args[0] instanceof Map ? call.args[0].script : call.args[0]
            assertTrue(script instanceof String, 'sh must receive a plain String, not a GString')
            assertFalse(script.contains(token))
            if (script.contains('git push') || script.contains('git ls-remote')) {
                assertTrue(script.contains('$GH_TOKEN') || script.contains('${GH_TOKEN}'))
            }
        }
        assertFalse(environment.containsKey('GH_TOKEN'))
    }

    private void assertPrepareFails(String message) {
        def failure = assertThrows(IllegalStateException) { step.prepare(cfg) }
        assertTrue(failure.message.contains(message), failure.message)
        assertFalse(commands.any { it.script.contains('git add') || it.script.contains('git tag -a') ||
            it.script.contains('git push') || it.script.contains('yq -i') })
        assertTrue(files['CHANGELOG.md'] == null || !files['CHANGELOG.md'].contains('## [1.10.0]'))
    }

    private void assertAtomicPush(String slug) {
        def pushes = commands.findAll { it.script.contains('git push') }
        assertEquals(1, pushes.size())
        String script = pushes[0].script
        assertTrue(script.contains('git push --atomic "https://x-access-token:${GH_TOKEN}@github.com/${REPO_SLUG}.git" "HEAD:refs/heads/main" "refs/tags/${V}"'))
        assertEquals(slug, pushes[0].environment.REPO_SLUG)
        assertEquals('1.10.0', pushes[0].environment.V)
        assertEquals(1, credentials.size())
    }
}
