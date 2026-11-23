package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class BuildArgsTest {
    private static final Map VALUES = [VERSION: 'v0.162.21-release-3e32d04-1',
        GIT_COMMIT: '3e32d0469321c6dc67d7d82976ad965cdfad0e5d',
        GIT_URL: 'https://github.com/raulstechtips/secrets-manager.git']

    @Test
    void replacesEachPlaceholder() {
        assertEquals([
            'INFISICAL_PLATFORM_VERSION=v0.162.21-release-3e32d04-1',
            'DD_GIT_COMMIT_SHA=3e32d0469321c6dc67d7d82976ad965cdfad0e5d',
            'DD_GIT_REPOSITORY_URL=https://github.com/raulstechtips/secrets-manager.git',
            'BOTH=v0.162.21-release-3e32d04-1@3e32d0469321c6dc67d7d82976ad965cdfad0e5d'
        ], BuildArgs.resolve([
            'INFISICAL_PLATFORM_VERSION=${VERSION}',
            'DD_GIT_COMMIT_SHA=${GIT_COMMIT}',
            'DD_GIT_REPOSITORY_URL=${GIT_URL}',
            'BOTH=${VERSION}@${GIT_COMMIT}'
        ], VALUES))
    }

    @Test
    void keepsOtherTextLiteral() {
        List entries = ['STATIC=value', 'OTHER=${HOME}', 'DOLLAR=$VERSION', 'SHELL=$(id)']
        assertEquals(entries, BuildArgs.resolve(entries, VALUES))
    }

    @Test
    void emptyOrMissingListGivesNoArguments() {
        assertEquals([], BuildArgs.resolve(null, VALUES))
        assertEquals([], BuildArgs.resolve([], VALUES))
    }

    @Test
    void placeholderWithoutValueFails() {
        [null, ''].each { Object missing ->
            def failure = assertThrows(IllegalArgumentException) {
                BuildArgs.resolve(['DD_GIT_COMMIT_SHA=${GIT_COMMIT}'], VALUES + [GIT_COMMIT: missing])
            }
            assertTrue(failure.message.contains('${GIT_COMMIT}'))
        }
    }

    @Test
    void unusedPlaceholderNeedsNoValue() {
        assertEquals(['STATIC=value'], BuildArgs.resolve(['STATIC=value'], [:]))
    }
}
