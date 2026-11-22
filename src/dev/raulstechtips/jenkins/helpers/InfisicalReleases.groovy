package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Names and versions for the Infisical release branches.
 *
 * A release branch is release/vX.Y.Z, where vX.Y.Z is exactly an upstream Infisical platform tag.
 */
class InfisicalReleases implements Serializable {
    static final String BRANCH_PREFIX = 'release/'
    private static final String TAG = /v[0-9]+[.][0-9]+[.][0-9]+/

    /**
     * Gives only github.com/raulstechtips/ URLs the GH_TOKEN, in the current process only, so
     * upstream is fetched without credentials. Tracing is off, so the token is never printed.
     */
    static final String FORK_AUTH = '''set +x
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="url.https://x-access-token:${GH_TOKEN}@github.com/raulstechtips/.insteadOf"
export GIT_CONFIG_VALUE_0=https://github.com/raulstechtips/
'''

    /**
     * Prepares WORKDIR as a treeless clone with the upstream v* tags and the fork release branches.
     * Reads REPO_URL (the fork) and UPSTREAM_URL from the environment. Upstream is fetched without
     * credentials. Trees and blobs are fetched on demand, so only commits are downloaded here.
     */
    static final String FETCH_SCRIPT = '''set -eu
rm -rf "$WORKDIR"
git init -q "$WORKDIR"
cd "$WORKDIR"
git remote add origin "$REPO_URL"
git remote add upstream "$UPSTREAM_URL"
git fetch -q --filter=tree:0 --no-tags upstream '+refs/tags/v*:refs/tags/v*'
git fetch -q --filter=tree:0 --no-tags origin '+refs/heads/release/*:refs/remotes/origin/release/*'
'''

    /**
     * Creates release/$TAG in WORKDIR from the upstream tag $TAG and the commits of $SOURCE after
     * the tag $PREV, then pushes it. It stops when $SOURCE has a merge commit after $PREV or the
     * cherry-pick does not apply. Commits that become empty are dropped. The original authors stay;
     * the committer is $COMMITTER_NAME <$COMMITTER_EMAIL>. The push only creates the branch: it is
     * refused when release/$TAG already exists.
     */
    static final String BRANCH_SCRIPT = '''set -eu
cd "$WORKDIR"
merges=$(git rev-list --merges "refs/tags/$PREV..$SOURCE")
if [ -n "$merges" ]; then
  echo "Merge commits after $PREV on $SOURCE; release branches must have none:" >&2
  echo "$merges" >&2
  exit 1
fi
git checkout -q -B "release/$TAG" "refs/tags/$TAG"
if ! git -c user.name="$COMMITTER_NAME" -c user.email="$COMMITTER_EMAIL" \\
    cherry-pick --empty=drop "refs/tags/$PREV..$SOURCE"; then
  echo "Cherry-pick onto $TAG stopped. Conflicting files:" >&2
  git diff --name-only --diff-filter=U >&2 || true
  git cherry-pick --abort || true
  exit 1
fi
git log --format='%h %an: %s' "refs/tags/$TAG..HEAD"
git push -q --force-with-lease="refs/heads/release/$TAG:" origin "HEAD:refs/heads/release/$TAG"
git update-ref "refs/remotes/origin/release/$TAG" HEAD
'''

    /**
     * Returns the upstream tag of a release branch.
     *
     * @param branch the branch name, for example release/v0.162.21
     * @return the tag, for example v0.162.21, or null when the name is not release/vX.Y.Z
     */
    @NonCPS
    static String tagOf(String branch) {
        def match = (branch ?: '') =~ ('^' + BRANCH_PREFIX + '(' + TAG + ')$')
        match.matches() ? match.group(1) : null
    }

    /**
     * Returns the image version of a release branch build: TAG-release-HEAD7-BUILD.
     *
     * @param branch the branch name, release/vX.Y.Z
     * @param head the full head commit
     * @param buildNumber the Jenkins build number
     * @return the version, for example v0.162.21-release-3e32d04-1
     * @throws IllegalArgumentException when the branch is not release/vX.Y.Z or head is not a commit
     */
    @NonCPS
    static String imageVersion(String branch, String head, String buildNumber) {
        String tag = tagOf(branch)
        if (tag == null) {
            throw new IllegalArgumentException(
                "Branch \"${branch}\" must be release/vX.Y.Z, where vX.Y.Z is an upstream Infisical tag.".toString())
        }
        if (!(head ==~ /^[0-9a-f]{40}$/)) {
            throw new IllegalArgumentException("Head commit \"${head}\" must be 40 lowercase hex characters.".toString())
        }
        "${tag}-release-${head.take(7)}-${buildNumber}".toString()
    }

    /**
     * Returns the newest release branch tag and the upstream tags to create after it.
     *
     * Only upstream tags vX.Y.Z count. They are sorted by version, and only tags newer than the
     * newest release branch are returned.
     *
     * @param upstreamTags the upstream tag names
     * @param branches the release branch names, for example release/v0.162.21
     * @param mode 'next' for the next tag only, or 'all' for every newer tag
     * @return a map with base (the tag of the newest release branch) and tags (oldest first)
     * @throws IllegalArgumentException when no release branch exists or mode is unknown
     */
    @NonCPS
    static Map plan(List<String> upstreamTags, List<String> branches, String mode) {
        if (!(mode in ['next', 'all'])) {
            throw new IllegalArgumentException("MODE must be 'next' or 'all'.")
        }
        List<String> existing = branches.collect { tagOf(it) }.findAll { it != null }
        if (existing.isEmpty()) {
            throw new IllegalArgumentException(
                'No release/vX.Y.Z branch exists. Create the first one by hand before this job runs.')
        }
        String base = existing.max { version(it) }
        List<String> newer = upstreamTags.findAll { it ==~ ('^' + TAG + '$') }.unique()
            .findAll { version(it) > version(base) }
            .sort { version(it) }
        [base: base, tags: mode == 'next' ? newer.take(1) : newer]
    }

    @NonCPS
    private static SemVer version(String tag) {
        SemVer.parse(tag.substring(1))
    }
}
