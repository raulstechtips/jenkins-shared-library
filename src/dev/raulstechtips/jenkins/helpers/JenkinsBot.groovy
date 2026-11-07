package dev.raulstechtips.jenkins.helpers

/**
 * The identity of the Jenkins GitHub App bot, and the shell prefix that authenticates git calls
 * to GitHub with its token.
 */
class JenkinsBot implements Serializable {
    static final String NAME = 'raulstechtips-jenkins[bot]'
    static final String EMAIL = 'raulstechtips-jenkins[bot]@users.noreply.github.com'

    /**
     * Rewrites HTTPS and SSH GitHub origins to use GH_TOKEN, in the current process only. The
     * token is read from the environment and is never written to the git config. Use it inside
     * withCredentials that sets GH_TOKEN.
     */
    static final String GIT_AUTH = '''set +x
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0="url.https://x-access-token:${GH_TOKEN}@github.com/.insteadOf"
export GIT_CONFIG_VALUE_0=https://github.com/
export GIT_CONFIG_KEY_1="$GIT_CONFIG_KEY_0"
export GIT_CONFIG_VALUE_1=git@github.com:
export GIT_CONFIG_KEY_2="$GIT_CONFIG_KEY_0"
export GIT_CONFIG_VALUE_2=ssh://git@github.com/
'''
}
