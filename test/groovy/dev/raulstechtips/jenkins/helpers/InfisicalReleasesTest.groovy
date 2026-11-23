package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class InfisicalReleasesTest {
    private static final String HEAD = '3e32d0469321c6dc67d7d82976ad965cdfad0e5d'
    private static final List<String> UPSTREAM = ['v0.162.21', 'v0.162.22', 'v0.162.24', 'v0.162.23',
        'v0.163.0', 'v0.162.10', 'v0.163.0-nightly-20260826', 'infisical/v0.163.1-postgres',
        'infisical-cli/v0.45.4', 'v0.164.0', 'v0.164.0']

    @Test
    void readsTagOfReleaseBranch() {
        assertEquals('v0.162.21', InfisicalReleases.tagOf('release/v0.162.21'))
        ['main', 'release/0.162.21', 'release/v0.162', 'release/v0.162.21-fix',
            'feature/release/v0.162.21', 'release/DEVSECOPS-1-x', null].each {
            assertNull(InfisicalReleases.tagOf(it), String.valueOf(it))
        }
    }

    @Test
    void buildsImageVersionFromBranchHeadAndBuild() {
        assertEquals('v0.162.21-release-3e32d04-1', InfisicalReleases.imageVersion('release/v0.162.21', HEAD, '1'))
    }

    @Test
    void imageVersionRejectsOtherBranchesAndBadCommits() {
        def branch = assertThrows(IllegalArgumentException) { InfisicalReleases.imageVersion('main', HEAD, '1') }
        assertTrue(branch.message.contains('release/vX.Y.Z'))
        ['3e32d04', HEAD.toUpperCase(), null].each { String head ->
            assertThrows(IllegalArgumentException) { InfisicalReleases.imageVersion('release/v0.162.21', head, '1') }
        }
    }

    @Test
    void nextReturnsOnlyTheNextNewerPlatformTag() {
        assertEquals([base: 'v0.162.21', tags: ['v0.162.22']],
            InfisicalReleases.plan(UPSTREAM, ['release/v0.162.21'], 'next'))
    }

    @Test
    void allReturnsEveryNewerPlatformTagInVersionOrder() {
        assertEquals([base: 'v0.162.21', tags: ['v0.162.22', 'v0.162.23', 'v0.162.24', 'v0.163.0', 'v0.164.0']],
            InfisicalReleases.plan(UPSTREAM, ['release/v0.162.21'], 'all'))
    }

    @Test
    void baseIsTheNewestReleaseBranchByVersion() {
        assertEquals([base: 'v0.162.24', tags: ['v0.163.0']], InfisicalReleases.plan(UPSTREAM,
            ['release/v0.162.21', 'release/v0.162.24', 'release/v0.162.9', 'main', 'sync/v0.167.14'], 'next'))
    }

    @Test
    void nothingNewGivesNoTags() {
        assertEquals([base: 'v0.164.0', tags: []], InfisicalReleases.plan(UPSTREAM, ['release/v0.164.0'], 'all'))
    }

    @Test
    void noReleaseBranchOrUnknownModeFails() {
        def none = assertThrows(IllegalArgumentException) { InfisicalReleases.plan(UPSTREAM, ['main', 'dev'], 'all') }
        assertTrue(none.message.contains('Create the first one by hand'))
        assertThrows(IllegalArgumentException) { InfisicalReleases.plan(UPSTREAM, ['release/v0.162.21'], 'latest') }
    }
}
