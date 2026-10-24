package dev.raulstechtips.jenkins.release

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

class ChangelogUpdaterTest {

    private static final String DIR = 'test/resources/fixtures/changelog/'
    private static final String DATE = '2026-10-24'
    private static final String HEAD = '# Changelog\n\n'

    private static String fixture(String name) {
        return new String(new File(DIR + name).bytes, 'UTF-8')
    }

    private static void assertBytesEqual(String expectedName, String actual) {
        byte[] expected = new File(DIR + expectedName).bytes
        assertTrue(Arrays.equals(expected, actual.getBytes('UTF-8')),
            "output differs from ${expectedName}:\n${actual}")
    }

    private final ChangelogUpdater updater = new ChangelogUpdater()

    @Test
    void argocdDraftMatchesExpectedBytes() {
        String out = updater.release(fixture('argocd-draft.md'), '0.1.0', DATE, 'raulstechtips/argocd', null)
        assertBytesEqual('argocd-draft.expected.md', out)
    }

    @Test
    void jenkinsDraftMatchesExpectedBytes() {
        String out = updater.release(fixture('jenkins-draft.md'), '0.1.0', DATE, 'raulstechtips/jenkins', '')
        assertBytesEqual('jenkins-draft.expected.md', out)
    }

    @Test
    void emptyUnreleasedProducesHeadingOnlyRelease() {
        String out = updater.release(fixture('empty-unreleased.md'), '0.1.0', DATE, 'raulstechtips/demo', null)
        assertBytesEqual('empty-unreleased.expected.md', out)
    }

    @Test
    void previousReleaseUsesCompareLinkAndKeepsOlderSections() {
        String out = updater.release(fixture('previous-release.md'), '1.1.0', DATE, 'raulstechtips/demo', '1.0.0')
        assertBytesEqual('previous-release.expected.md', out)
    }

    @Test
    void hasUnreleasedEntriesOnFixtures() {
        assertTrue(updater.hasUnreleasedEntries(fixture('argocd-draft.md')))
        assertTrue(updater.hasUnreleasedEntries(fixture('jenkins-draft.md')))
        assertTrue(updater.hasUnreleasedEntries(fixture('previous-release.md')))
        assertFalse(updater.hasUnreleasedEntries(fixture('empty-unreleased.md')))
    }

    @Test
    void hasUnreleasedEntriesIgnoresBulletsOutsideUnreleased() {
        // the only bullet belongs to the 1.0.0 section
        String text = HEAD + '## [Unreleased]\n\n### Added\n\n## [1.0.0] - 2026-09-01\n\n- old\n'
        assertFalse(updater.hasUnreleasedEntries(text))
    }

    @Test
    void hasUnreleasedEntriesRequiresDashSpaceBullets() {
        String star = HEAD + '## [Unreleased]\n\n### Added\n\n* starred\n'
        String dashOnly = HEAD + '## [Unreleased]\n\n### Added\n\n-nospace\n'
        assertFalse(updater.hasUnreleasedEntries(star))
        assertFalse(updater.hasUnreleasedEntries(dashOnly))
    }

    @Test
    void asteriskBulletsAreKeptVerbatimInTheReleasedSection() {
        String text = HEAD + '## [Unreleased]\n\n### Added\n\n* starred\n\n### Fixed\n\n[Unreleased]: https://github.com/o/r/commits/main\n'
        String out = updater.release(text, '2.0.0', DATE, 'o/r', null)
        assertTrue(out.contains('## [2.0.0] - 2026-10-24\n\n### Added\n\n* starred\n\n[Unreleased]'))
        assertFalse(out.substring(out.indexOf('## [2.0.0]')).contains('### Fixed'))
    }

    @Test
    void missingUnreleasedHeadingIsAnError() {
        String text = HEAD + '## [1.0.0] - 2026-09-01\n\n- old\n'
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            updater.release(text, '1.1.0', DATE, 'o/r', '1.0.0')
        }
        assertTrue(e.message.contains('## [Unreleased]'))
        assertFalse(updater.hasUnreleasedEntries(text))
    }

    @Test
    void crlfLineEndingsAreRejected() {
        String text = fixture('argocd-draft.md').replace('\n', '\r\n')
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            updater.release(text, '0.1.0', DATE, 'o/r', null)
        }
        assertTrue(e.message.contains('CRLF'))
    }

    @Test
    void trailingNewlinePresenceIsPreserved() {
        String withNl = fixture('argocd-draft.md')
        String withoutNl = withNl.substring(0, withNl.length() - 1)
        assertTrue(updater.release(withNl, '0.1.0', DATE, 'o/r', null).endsWith('0.1.0\n'))
        String out = updater.release(withoutNl, '0.1.0', DATE, 'o/r', null)
        assertTrue(out.endsWith('0.1.0'))
        assertFalse(out.endsWith('\n'))
    }

    @Test
    void unreleasedAsLastSectionWithoutLinkLinesGetsLinksAppended() {
        String text = HEAD + '## [Unreleased]\n\n### Added\n\n- thing\n'
        String expected = HEAD + '## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n\n' +
            '## [1.0.0] - 2026-10-24\n\n### Added\n\n- thing\n\n' +
            '[Unreleased]: https://github.com/o/r/compare/1.0.0...HEAD\n' +
            '[1.0.0]: https://github.com/o/r/releases/tag/1.0.0\n'
        assertEquals(expected, updater.release(text, '1.0.0', DATE, 'o/r', null))
    }

    @Test
    void unreleasedAsLastSectionWithLinkLinesRewritesThem() {
        String text = HEAD + '## [Unreleased]\n\n### Fixed\n\n- bug\n\n[Unreleased]: https://github.com/o/r/commits/main\n'
        String out = updater.release(text, '1.0.1', DATE, 'o/r', '1.0.0')
        assertTrue(out.endsWith('\n[Unreleased]: https://github.com/o/r/compare/1.0.1...HEAD\n' +
            '[1.0.1]: https://github.com/o/r/compare/1.0.0...1.0.1\n'))
    }

    private static final String FRESH = '## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n\n'

    @Test
    void proseOnlySubsectionIsRemovedFromTheReleasedSection() {
        String text = HEAD + '## [Unreleased]\n\n### Added\n\n- real entry\n\n### Fixed\n\nNo fixes this cycle.\n\n[Unreleased]: old\n'
        String expected = HEAD + FRESH + '## [2.0.0] - 2026-10-24\n\n### Added\n\n- real entry\n\n' +
            '[Unreleased]: https://github.com/o/r/compare/2.0.0...HEAD\n' +
            '[2.0.0]: https://github.com/o/r/releases/tag/2.0.0\n'
        assertEquals(expected, updater.release(text, '2.0.0', DATE, 'o/r', null))
    }

    @Test
    void subsectionWithOnlyAsteriskBulletsIsKept() {
        String text = HEAD + '## [Unreleased]\n\n### Added\n\n- real entry\n\n### Fixed\n\n  * indented star\n\n[Unreleased]: old\n'
        String expected = HEAD + FRESH + '## [2.0.0] - 2026-10-24\n\n### Added\n\n- real entry\n\n' +
            '### Fixed\n\n  * indented star\n\n' +
            '[Unreleased]: https://github.com/o/r/compare/2.0.0...HEAD\n' +
            '[2.0.0]: https://github.com/o/r/releases/tag/2.0.0\n'
        assertEquals(expected, updater.release(text, '2.0.0', DATE, 'o/r', null))
    }

    @Test
    void freshUnreleasedSectionKeepsAllThreeEmptySubsectionsWhenProseSubsectionsAreRemoved() {
        String text = HEAD + '## [Unreleased]\n\n### Added\n\nnothing yet\n\n### Changed\n\nsee docs\n\n[Unreleased]: old\n'
        String out = updater.release(text, '2.0.0', DATE, 'o/r', null)
        assertTrue(out.startsWith(HEAD + FRESH + '## [2.0.0] - 2026-10-24\n\n[Unreleased]:'))
    }

    @Test
    void invalidArgumentsAreRejected() {
        String text = fixture('argocd-draft.md')
        assertThrows(IllegalArgumentException) { updater.release(text, '1.0', DATE, 'o/r', null) }
        assertThrows(IllegalArgumentException) { updater.release(text, '1.0.0', '24-10-2026', 'o/r', null) }
        assertThrows(IllegalArgumentException) { updater.release(text, '1.0.0', DATE, 'no-slash', null) }
        assertThrows(IllegalArgumentException) { updater.release(text, '1.0.0', DATE, 'o/r', 'v0.9') }
    }
}
