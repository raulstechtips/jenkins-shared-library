package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Builds the shell script that runs a project build-command or test-command for one pipeline type.
 */
class ToolchainCommands implements Serializable {
    /**
     * Puts the pnpm shim from Corepack first on PATH. Corepack runs the pnpm version named in the
     * packageManager field of package.json. The shim goes under HOME because the agent user cannot
     * write to /usr/local/bin.
     */
    static final String PNPM_SETUP = '''export COREPACK_ENABLE_DOWNLOAD_PROMPT=0 PATH="$HOME/.local/bin:$PATH"
mkdir -p "$HOME/.local/bin"
corepack enable --install-directory "$HOME/.local/bin" pnpm
'''

    /**
     * Tells whether the commands of a pipeline type need the github-app token as GH_TOKEN.
     *
     * @param type the pipeline type
     * @return true for python, whose dependencies can come from private GitHub repos
     */
    @NonCPS
    static boolean usesGitHubToken(String type) {
        type == 'python'
    }

    /**
     * Returns the script to pass to sh for a project command.
     *
     * For python it starts with JenkinsBot.GIT_AUTH, so git rewrites HTTPS and SSH GitHub URLs to
     * use GH_TOKEN in that process only, then turns tracing back on. For node it starts with
     * PNPM_SETUP. For any other type it is the command unchanged.
     *
     * @param type the pipeline type
     * @param command the build-command or test-command
     * @return the script
     */
    @NonCPS
    static String script(String type, String command) {
        if (type == 'python') {
            return JenkinsBot.GIT_AUTH + 'set -x\n' + command
        }
        if (type == 'node') {
            return PNPM_SETUP + command
        }
        command
    }
}
