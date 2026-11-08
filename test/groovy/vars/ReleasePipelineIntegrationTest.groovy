package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.ShellQuote
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

/** Executes the release step with real Git, yq and Helm against a temporary local remote. */
@Tag('release-integration')
class ReleasePipelineIntegrationTest extends BasePipelineTest {
    private File root
    private File workspace
    private File remote
    private Script release
    private Map cfg
    private Map<String, String> environment
    private String initialCommit

    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp()
        root = Files.createTempDirectory('devsecops-192-release-').toFile()
        workspace = new File(root, 'workspace')
        remote = new File(root, 'remote.git')
        assertTrue(workspace.mkdir())
        environment = [GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null']
        command(root, 'git init --bare --initial-branch=main remote.git')
        command(workspace, 'git init --initial-branch=main')
        command(workspace, "git config user.name 'Release test'\ngit config user.email 'release@example.invalid'")
        put('charts/Chart.yaml', 'apiVersion: v2\nname: demo\nversion: 0.0.0\nappVersion: "upstream"\n')
        put('charts/values.yaml', 'image:\n  tag: old\n')
        put('gradle.properties', 'version=0.0.0\n')
        command(workspace, "git add .\ngit commit -m 'DEVSECOPS-192: initial fixture'")
        initialCommit = command(workspace, 'git rev-parse HEAD').trim()
        command(workspace, 'git push ' + ShellQuote.quote(remote.path) + ' main')
        command(workspace, 'git remote add origin https://github.com/raulstechtips/demo.git')
        cfg = [type: 'java', chartPresent: true, releaseVersion: '0.1.0',
            'helm-image-tag-path': '.image.tag']
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body -> body() }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            Map previous = new LinkedHashMap(environment)
            values.each { String value ->
                int equals = value.indexOf('=')
                environment[value.substring(0, equals)] = value.substring(equals + 1)
            }
            try { body() } finally { environment = previous }
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map value -> value }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List values, Closure body ->
            environment.GH_TOKEN = 'test-only'
            try { body() } finally { environment.remove('GH_TOKEN') }
        }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> new File(workspace, path).exists() }
        helper.registerAllowedMethod('readFile', [String]) { String path -> new File(workspace, path).text }
        helper.registerAllowedMethod('writeFile', [Map]) { Map args -> put(args.file, args.text) }
        helper.registerAllowedMethod('sh', [String]) { String script -> command(workspace, localTransport(script)); null }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> command(workspace, localTransport(args.script)) }
        helper.registerAllowedMethod('error', [String]) { String message -> throw new IllegalStateException(message) }
        release = loadScript('vars/releasePipeline.groovy')
    }

    @AfterEach
    void removeFixture() {
        if (root != null) {
            assertTrue(root.deleteDir())
        }
    }

    @Test
    void bootstrappedReleaseTagMatchesPreparedAndPackagedFiles() {
        release.prepare(cfg)
        assertEquals(initialCommit, command(workspace, 'git rev-parse HEAD').trim())
        assertEquals('', command(workspace, 'git tag').trim())
        Map chart = new Yaml().load(new File(workspace, 'charts/Chart.yaml').text)
        assertEquals('0.1.0', chart.version)
        assertEquals('upstream', chart.appVersion)
        assertEquals('version=0.1.0\n', new File(workspace, 'gradle.properties').text)
        command(workspace, 'helm package charts --destination dist')
        String packagedValues = command(workspace, 'helm show values dist/demo-0.1.0.tgz')
        assertEquals('0.1.0', new Yaml().load(packagedValues).image.tag)
        release.publish(cfg)
        String mainCommit = command(remote, 'git rev-parse main').trim()
        assertEquals(mainCommit, command(remote, 'git rev-parse 0.1.0^{}').trim())
        assertEquals('tag', command(remote, 'git cat-file -t 0.1.0').trim())
        cfg.releaseFiles.each { String path ->
            assertEquals(new File(workspace, path).text,
                command(remote, 'git show ' + ShellQuote.quote('0.1.0:' + path)))
        }
        String changelog = command(remote, 'git show 0.1.0:CHANGELOG.md')
        assertTrue(changelog.contains('## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n'))
        assertTrue(changelog.contains('## [0.1.0] - '))
        assertFalse(changelog.substring(changelog.indexOf('## [0.1.0]')).contains('### '))
        assertFalse(command(remote, 'git ls-tree -r --name-only 0.1.0').contains('dist/'))
        assertThrows(IllegalStateException) { release.prepare(cfg) }
        println 'QA PASS: bootstrap, prepared versions, real Helm archive, annotated tag, repeat-release rejection'
    }

    @Test
    void existingNotesAreIncludedInThePublishedTag() {
        put('CHANGELOG.md', '# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Update the application.\n')
        release.prepare(cfg)
        release.publish(cfg)
        String changelog = command(remote, 'git show 0.1.0:CHANGELOG.md')
        String released = changelog.substring(changelog.indexOf('## [0.1.0]'))
        assertTrue(released.contains('### Changed\n\n- Update the application.'))
        assertFalse(released.contains('### Added'))
        assertFalse(released.contains('### Fixed'))
        println 'QA PASS: existing notes moved into release and fresh Unreleased headings retained'
    }

    @Test
    void movedMainRejectsBothReleaseCommitAndTag() {
        release.prepare(cfg)
        command(root, 'git clone ' + ShellQuote.quote(remote.path) + ' competing')
        File competing = new File(root, 'competing')
        command(competing, "git config user.name 'Other test'\ngit config user.email 'other@example.invalid'")
        new File(competing, 'README.md').text = 'Concurrent change\n'
        command(competing, "git add README.md\ngit commit -m 'DEVSECOPS-192: concurrent fixture'\ngit push origin main")
        String competingCommit = command(competing, 'git rev-parse HEAD').trim()
        assertThrows(IllegalStateException) { release.publish(cfg) }
        assertEquals(competingCommit, command(remote, 'git rev-parse main').trim())
        assertEquals('', command(remote, 'git tag').trim())
        println 'QA PASS: real atomic push rejects moved main without publishing a release tag'
    }

    private void put(String path, String text) {
        File file = new File(workspace, path)
        file.parentFile.mkdirs()
        file.text = text
    }

    /** Replace only the network destination; commit, tag and atomic push run unchanged. */
    private String localTransport(String script) {
        script.replace('git ls-remote --tags origin', 'git ls-remote --tags ' + ShellQuote.quote(remote.path))
            .replace('"https://x-access-token:${GH_TOKEN}@github.com/${REPO_SLUG}.git"',
                ShellQuote.quote(remote.path))
    }

    private String command(File directory, String script) {
        File output = File.createTempFile('command-', '.log', root)
        ProcessBuilder builder = new ProcessBuilder('/bin/sh', '-eu', '-c', script)
            .directory(directory).redirectErrorStream(true).redirectOutput(output)
        builder.environment().putAll(environment)
        Process process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), 'command timed out: ' + script)
            String text = output.text
            if (process.exitValue() != 0) {
                throw new IllegalStateException(text)
            }
            return text
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            }
        }
    }
}
