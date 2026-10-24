package dev.raulstechtips.jenkins.deploy

import java.nio.file.Files
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class HelmInstallCommandsTest {
    private static final String DIGEST = 'sha256:' + ('b' * 64)

    private Map cfg(Map extra = [:]) {
        [
            'project-name': 'demo',
            'project-namespace': 'apps',
            'helm-overrides': [],
            'helm-image-tag-path': '',
            version: '1.2.3'
        ] + extra
    }

    /** Runs commands in a throwaway directory under a POSIX shell; helm and yq are stubs that print argv, one per line. */
    private Map execute(List<String> commands) {
        File dir = Files.createTempDirectory('helm-install-commands').toFile()
        try {
            String stubs = 'helm() { for a in "$@"; do printf \'%s\\n\' "$a"; done; }\n' +
                'yq() { for a in "$@"; do printf \'%s\\n\' "$a"; done; }\n'
            Process process = new ProcessBuilder('/bin/sh', '-c', stubs + commands.join('\n'))
                .directory(dir).redirectErrorStream(true).start()
            String output = process.inputStream.text
            int exit = process.waitFor()
            return [exit: exit, lines: output.readLines(), files: dir.list().toList().sort()]
        } finally {
            dir.deleteDir()
        }
    }

    @Test
    void shellQuoteWrapsAndEscapesSingleQuotes() {
        HelmInstallCommands h = new HelmInstallCommands()
        assertEquals("'plain'", h.shellQuote('plain'))
        assertEquals("''", h.shellQuote(''))
        assertEquals("'it'\\''s'", h.shellQuote("it's"))
    }

    @Test
    void overridePathWithSpaceIsOneArgument() {
        Map r = execute([new HelmInstallCommands().install(cfg('helm-overrides': ['my values.yaml']), [:], null).last()])
        assertEquals(0, r.exit)
        assertEquals(['upgrade', '--install', 'demo', 'charts', '-n', 'apps', '-f', 'my values.yaml', '--wait', '--timeout', '10m'], r.lines)
    }

    @Test
    void overridePathWithMetacharactersIsInert() {
        List<String> paths = ['v.yaml; touch PWNED #', 'a$(touch PWNED2).yaml', 'q\'uote"x`touch PWNED3`.yaml', 'b.yaml']
        Map r = execute([new HelmInstallCommands().install(cfg('helm-overrides': paths), [:], null).last()])
        assertEquals(0, r.exit)
        assertEquals(['upgrade', '--install', 'demo', 'charts', '-n', 'apps'] +
            paths.collect { ['-f', it] }.flatten() + ['--wait', '--timeout', '10m'], r.lines)
        assertEquals([], r.files)
    }

    @Test
    void tagExpressionWithQuoteIsOneInertArgument() {
        String path = ".a'; touch PWNED4; '"
        Map r = execute([new HelmInstallCommands().install(cfg('helm-image-tag-path': path), [:], DIGEST).first()])
        assertEquals(0, r.exit)
        assertEquals(['-i', path + ' = strenv(TAG)', 'charts/values.yaml'], r.lines)
        assertEquals([], r.files)
    }

    @Test
    void quotedKeyTagPathWorksWithRealYq() {
        Process probe = null
        try {
            probe = new ProcessBuilder('yq', '--version').redirectErrorStream(true).start()
            probe.inputStream.text
        } catch (IOException ignored) {
            // yq is not installed here
        }
        Assumptions.assumeTrue(probe != null && probe.waitFor() == 0, 'yq is not installed')
        File dir = Files.createTempDirectory('helm-install-yq').toFile()
        try {
            new File(dir, 'charts').mkdirs()
            new File(dir, 'charts/values.yaml').text = 'image:\n  tag.name: old\n'
            String command = new HelmInstallCommands().install(cfg('helm-image-tag-path': '.image["tag.name"]'), [:], DIGEST).first()
            Process process = new ProcessBuilder('/bin/sh', '-c', command).directory(dir).redirectErrorStream(true).start()
            process.inputStream.text
            assertEquals(0, process.waitFor())
            assertEquals('image:\n  tag.name: 1.2.3@' + DIGEST + '\n', new File(dir, 'charts/values.yaml').text)
        } finally {
            dir.deleteDir()
        }
    }
}
