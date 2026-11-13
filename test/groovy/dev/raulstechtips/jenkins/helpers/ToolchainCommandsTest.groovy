package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class ToolchainCommandsTest {
    @Test
    void pythonRewritesGitHubUrlsFromTheEnvironmentTokenThenTracesTheCommand() {
        String script = ToolchainCommands.script('python', 'uv sync --frozen')
        assertEquals(JenkinsBot.GIT_AUTH + 'set -x\nuv sync --frozen', script)
        assertTrue(script.startsWith('set +x\n'))
        assertTrue(script.contains('url.https://x-access-token:${GH_TOKEN}@github.com/.insteadOf'))
        assertTrue(script.contains('GIT_CONFIG_VALUE_1=git@github.com:'))
        assertTrue(script.contains('GIT_CONFIG_VALUE_2=ssh://git@github.com/'))
        assertFalse(script.contains('git config'))
        assertTrue(ToolchainCommands.usesGitHubToken('python'))
    }

    @Test
    void nodePutsCorepackPnpmUnderHomeFirstOnPath() {
        String script = ToolchainCommands.script('node', 'pnpm build')
        assertEquals(ToolchainCommands.PNPM_SETUP + 'pnpm build', script)
        assertTrue(script.contains('corepack enable --install-directory "$HOME/.local/bin" pnpm'))
        assertTrue(script.contains('PATH="$HOME/.local/bin:$PATH"'))
        assertFalse(script.contains('GH_TOKEN'))
        assertFalse(ToolchainCommands.usesGitHubToken('node'))
    }

    @Test
    void otherTypesRunTheCommandUnchangedWithoutToken() {
        ['java', 'cluster', null].each { String type ->
            assertEquals('./gradlew test', ToolchainCommands.script(type, './gradlew test'))
            assertFalse(ToolchainCommands.usesGitHubToken(type))
        }
    }
}
