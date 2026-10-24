package dev.raulstechtips.jenkins.shell

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ShellQuoteTest {
    /** Runs printf with the quoted value under /bin/sh in a temp dir outside the repo; returns argv, one entry per line, plus a marker file check. */
    private Map run(String value) {
        File dir = Files.createTempDirectory('shell-quote-test').toFile()
        try {
            File script = new File(dir, 'run.sh')
            script.text = 'set -eu\nprintf \'%s\\0\' ' + ShellQuote.quote(value) + ' marker\n'
            Process p = new ProcessBuilder('/bin/sh', script.name).directory(dir).redirectErrorStream(true).start()
            byte[] out = p.inputStream.bytes
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            return [exit: p.exitValue(), args: new String(out, 'UTF-8').split('\u0000', -1).toList(), files: dir.list().toList()]
        } finally {
            dir.deleteDir()
        }
    }

    private void assertInert(String value) {
        Map result = run(value)
        assertEquals(0, result.exit)
        // one argument for the value, one for the marker, one empty tail after the last NUL
        assertEquals([value, 'marker', ''], result.args)
        assertEquals(['run.sh'], result.files)
    }

    @Test void 'wraps a plain value in single quotes'() { assertEquals("'abc'", ShellQuote.quote('abc')) }
    @Test void 'space stays one argument'() { assertInert('a b  c') }
    @Test void 'semicolon is inert'() { assertInert('a; touch pwned') }
    @Test void 'command substitution is inert'() { assertInert('$(touch pwned)') }
    @Test void 'backtick is inert'() { assertInert('`touch pwned`') }
    @Test void 'single quote is escaped'() {
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertInert("it's ' ''; touch pwned")
    }
    @Test void 'empty string is one empty argument'() {
        assertEquals("''", ShellQuote.quote(''))
        assertInert('')
    }
    @Test void 'null throws IllegalArgumentException'() {
        assertThrows(IllegalArgumentException) { ShellQuote.quote(null) }
    }
}
