package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

// Runs the real fetch and branch scripts with local Git (2.45 or later) against bare repositories.
@Tag('release-integration')
class InfisicalReleaseScriptsIntegrationTest {
    private File root
    private File upstreamWork
    private File forkWork
    private Map<String, String> environment

    @BeforeEach
    void setUp() {
        root = Files.createTempDirectory('infisical-release').toFile()
        ['upstream.git', 'fork.git'].each { String name ->
            git(root, "init -q --bare --initial-branch=main ${name}")
            File bare = new File(root, name)
            git(bare, 'config uploadpack.allowFilter true')
            git(bare, 'config uploadpack.allowAnySHA1InWant true')
        }
        upstreamWork = new File(root, 'upstream-work')
        upstreamWork.mkdirs()
        git(upstreamWork, 'init -q --initial-branch=main')
        identity(upstreamWork, 'Upstream Dev', 'dev@upstream.invalid')
        commit(upstreamWork, ['app.txt': 'line1\n', 'config.txt': 'a\n'], 'initial')
        tag(upstreamWork, 'v1.0.0')
        commit(upstreamWork, ['other.txt': 'other\n'], 'other change')
        tag(upstreamWork, 'v1.0.1')
        commit(upstreamWork, ['config.txt': 'a\nb\n'], 'upstream adds b')
        tag(upstreamWork, 'v1.0.2')
        commit(upstreamWork, ['app.txt': 'line1 upstream\n'], 'upstream edits app')
        tag(upstreamWork, 'v1.0.3')
        git(upstreamWork, 'push -q ' + new File(root, 'upstream.git').path + ' main --tags')

        forkWork = new File(root, 'fork-work')
        forkWork.mkdirs()
        git(forkWork, 'init -q --initial-branch=main')
        identity(forkWork, 'Raul', 'raul@example.invalid')
        git(forkWork, 'fetch -q ' + new File(root, 'upstream.git').path + ' refs/tags/v1.0.0:refs/tags/v1.0.0')
        git(forkWork, 'checkout -q -b release/v1.0.0 v1.0.0')
        commit(forkWork, ['app.txt': 'line1 patched\n'], 'license unlock')
        commit(forkWork, ['config.txt': 'a\nb\n'], 'Jenkins build')
        git(forkWork, 'push -q ' + new File(root, 'fork.git').path + ' release/v1.0.0')

        environment = [WORKDIR: new File(root, 'job').path,
            REPO_URL: 'file://' + new File(root, 'fork.git').path,
            UPSTREAM_URL: 'file://' + new File(root, 'upstream.git').path,
            COMMITTER_NAME: JenkinsBot.INFISICAL_RELEASE_NAME,
            COMMITTER_EMAIL: JenkinsBot.INFISICAL_RELEASE_EMAIL]
        assertEquals(0, script(InfisicalReleases.FETCH_SCRIPT).exit)
    }

    @AfterEach
    void tearDown() {
        root.deleteDir()
    }

    @Test
    void createsBranchWithOnlyOurCommitsKeepingAuthors() {
        Map result = branch('v1.0.0', 'v1.0.1')
        assertEquals(0, result.exit, result.output)
        File fork = new File(root, 'fork.git')
        List<String> log = git(fork, "log --format='%s|%an|%cn' release/v1.0.1~2..release/v1.0.1").readLines()
        assertEquals(['Jenkins build|Raul|infisical-release-jenkins[bot]',
            'license unlock|Raul|infisical-release-jenkins[bot]'], log)
        assertEquals(git(new File(root, 'upstream.git'), 'rev-parse v1.0.1^{commit}').trim(),
            git(fork, 'rev-parse release/v1.0.1~2').trim())
    }

    @Test
    void dropsCommitsThatUpstreamAlreadyHas() {
        assertEquals(0, branch('v1.0.0', 'v1.0.1').exit)
        Map result = branch('v1.0.1', 'v1.0.2')
        assertEquals(0, result.exit, result.output)
        assertEquals(['license unlock'],
            git(new File(root, 'fork.git'), 'log --format=%s release/v1.0.1~2..release/v1.0.2').readLines()
                .findAll { it != 'upstream adds b' && it != 'other change' })
    }

    @Test
    void conflictStopsWithoutPushing() {
        assertEquals(0, branch('v1.0.0', 'v1.0.1').exit)
        assertEquals(0, branch('v1.0.1', 'v1.0.2').exit)
        Map result = branch('v1.0.2', 'v1.0.3')
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains('Cherry-pick onto v1.0.3 stopped'), result.output)
        assertTrue(result.output.contains('app.txt'), result.output)
        assertEquals('', git(new File(root, 'fork.git'), 'branch --list release/v1.0.3').trim())
    }

    @Test
    void mergeCommitOnSourceStops() {
        git(forkWork, 'checkout -q -b side release/v1.0.0~1')
        commit(forkWork, ['side.txt': 'side\n'], 'side')
        git(forkWork, 'checkout -q release/v1.0.0')
        git(forkWork, 'merge -q --no-ff -m merged side')
        git(forkWork, 'push -q ' + new File(root, 'fork.git').path + ' release/v1.0.0')
        assertEquals(0, script(InfisicalReleases.FETCH_SCRIPT).exit)
        Map result = branch('v1.0.0', 'v1.0.1')
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains('Merge commits after v1.0.0'), result.output)
        assertEquals('', git(new File(root, 'fork.git'), 'branch --list release/v1.0.1').trim())
    }

    @Test
    void neverChangesAnExistingBranch() {
        git(forkWork, 'push -q ' + new File(root, 'fork.git').path + ' release/v1.0.0:refs/heads/release/v1.0.1')
        String before = git(new File(root, 'fork.git'), 'rev-parse release/v1.0.1').trim()
        Map result = branch('v1.0.0', 'v1.0.1')
        assertNotEquals(0, result.exit)
        assertEquals(before, git(new File(root, 'fork.git'), 'rev-parse release/v1.0.1').trim())
    }

    private Map branch(String previous, String tag) {
        script(InfisicalReleases.BRANCH_SCRIPT,
            [PREV: previous, TAG: tag, SOURCE: 'refs/remotes/origin/release/' + previous])
    }

    private Map script(String text, Map<String, String> extra = [:]) {
        ProcessBuilder builder = new ProcessBuilder('sh', '-c', text).directory(root).redirectErrorStream(true)
        builder.environment().putAll(environment + extra)
        Process process = builder.start()
        String output = process.inputStream.text
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        [exit: process.exitValue(), output: output]
    }

    private static String git(File directory, String arguments) {
        Process process = new ProcessBuilder('sh', '-c', 'git ' + arguments).directory(directory)
            .redirectErrorStream(true).start()
        String output = process.inputStream.text
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), 'git ' + arguments + '\n' + output)
        output
    }

    private static void identity(File directory, String name, String email) {
        git(directory, "config user.name '${name}'")
        git(directory, "config user.email '${email}'")
    }

    private static void commit(File directory, Map<String, String> files, String message) {
        files.each { String path, String text -> new File(directory, path).text = text }
        git(directory, 'add -A')
        git(directory, "commit -q -m '${message}'")
    }

    private static void tag(File directory, String name) {
        git(directory, "tag -a ${name} -m ${name}")
    }
}
