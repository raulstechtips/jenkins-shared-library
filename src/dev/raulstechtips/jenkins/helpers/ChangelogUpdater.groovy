package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Pure text transform for Keep a Changelog 1.1.0 files (E10).
 *
 * Decisions:
 *  - CRLF input is rejected (IllegalArgumentException); the repositories use LF and a silent
 *    rewrite of every line ending would break "no other line changed".
 *  - A trailing newline is preserved as it is (present stays present, absent stays absent).
 *  - A "### " subsection without any bullet line ("- " or "* ", after optional indentation)
 *    is removed from the released section (E10); subsections with bullets are kept verbatim.
 *  - hasUnreleasedEntries counts only lines that start with "- " (E10).
 */
class ChangelogUpdater implements Serializable {

    private static final long serialVersionUID = 1L

    private static final String FRESH_UNRELEASED =
        '## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n'

    /**
     * Moves the Unreleased entries into a new release section and returns the new changelog text.
     *
     * It writes a fresh, empty Unreleased section (Added, Changed, Fixed), then "## [version] -
     * isoDate" with the old Unreleased subsections that have bullets. It replaces the [Unreleased]
     * link with a compare link from version to HEAD and adds a link for version: a compare link
     * from previousVersion, or a release tag link when there is no previous version. When there is
     * no [Unreleased] link, both links are appended at the end. No other line changes.
     *
     * @param text the current CHANGELOG.md content
     * @param version the release version, X.Y.Z
     * @param isoDate the release date, YYYY-MM-DD
     * @param repoSlug the GitHub repository, owner/repo
     * @param previousVersion the previous release, X.Y.Z, or null or empty for the first release
     * @return the updated changelog text
     * @throws IllegalArgumentException when an argument has the wrong format, when text uses CRLF
     *     line endings, or when there is no "## [Unreleased]" heading
     */
    @NonCPS
    String release(String text, String version, String isoDate, String repoSlug, String previousVersion) {
        if (!(version ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
            throw new IllegalArgumentException("version must be X.Y.Z, got: ${version}")
        }
        if (!(isoDate ==~ /[0-9]{4}-[0-9]{2}-[0-9]{2}/)) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD, got: ${isoDate}")
        }
        if (!(repoSlug ==~ /[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+/)) {
            throw new IllegalArgumentException("repoSlug must be owner/repo, got: ${repoSlug}")
        }
        boolean hasPrevious = previousVersion != null && previousVersion != ''
        if (hasPrevious && !(previousVersion ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
            throw new IllegalArgumentException("previousVersion must be X.Y.Z or empty, got: ${previousVersion}")
        }
        checkLineEndings(text)

        boolean endsWithNewline = text.endsWith('\n')
        List<String> lines = splitLines(text)
        int h = unreleasedIndex(lines)
        if (h < 0) {
            throw new IllegalArgumentException('CHANGELOG has no "## [Unreleased]" heading')
        }
        int e = sectionEnd(lines, h)
        List<String> body = keepFilledSubsections(lines.subList(h + 1, e))
        List<String> rest = new ArrayList<String>(lines.subList(e, lines.size()))

        String base = "https://github.com/${repoSlug}"
        String unreleasedLink = "[Unreleased]: ${base}/compare/${version}...HEAD"
        String versionLink = hasPrevious ?
            "[${version}]: ${base}/compare/${previousVersion}...${version}" :
            "[${version}]: ${base}/releases/tag/${version}"

        boolean linkReplaced = false
        for (int i = 0; i < rest.size(); i++) {
            if (rest.get(i) ==~ /^\[Unreleased\]:\s.*/) {
                rest.set(i, unreleasedLink + '\n' + versionLink)
                linkReplaced = true
                break
            }
        }
        if (!linkReplaced) {
            if (!rest.isEmpty() && rest.get(rest.size() - 1) != '') {
                rest.add('')
            }
            rest.add(unreleasedLink + '\n' + versionLink)
        }

        List<String> out = new ArrayList<String>(lines.subList(0, h))
        out.addAll(FRESH_UNRELEASED.split('\n', -1).toList())
        out.add("## [${version}] - ${isoDate}")
        out.add('')
        if (!body.isEmpty()) {
            out.addAll(body)
            out.add('')
        }
        out.addAll(rest)
        String result = out.join('\n')
        return endsWithNewline ? result + '\n' : result
    }

    /**
     * Tells whether the Unreleased section has at least one entry.
     *
     * Only lines that start with "- " count as entries.
     *
     * @param text the CHANGELOG.md content
     * @return true when there is an entry; false when there is none or there is no Unreleased
     *     heading
     * @throws IllegalArgumentException when text uses CRLF line endings
     */
    @NonCPS
    boolean hasUnreleasedEntries(String text) {
        checkLineEndings(text)
        List<String> lines = splitLines(text)
        int h = unreleasedIndex(lines)
        if (h < 0) {
            return false
        }
        int e = sectionEnd(lines, h)
        for (int i = h + 1; i < e; i++) {
            if (lines.get(i).startsWith('- ')) {
                return true
            }
        }
        return false
    }

    @NonCPS
    private static void checkLineEndings(String text) {
        if (text.contains('\r')) {
            throw new IllegalArgumentException('CHANGELOG uses CRLF line endings; only LF is supported')
        }
    }

    /** Lines without the artificial empty element a trailing newline would add. */
    @NonCPS
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>(text.split('\n', -1).toList())
        if (text.endsWith('\n')) {
            lines.remove(lines.size() - 1)
        }
        return lines
    }

    @NonCPS
    private static int unreleasedIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) ==~ /^## \[Unreleased\]\s*$/) {
                return i
            }
        }
        return -1
    }

    /** Index of the first line after the Unreleased body: next "## " heading or first link definition. */
    @NonCPS
    private static int sectionEnd(List<String> lines, int h) {
        for (int i = h + 1; i < lines.size(); i++) {
            String line = lines.get(i)
            if (line.startsWith('## ') || line ==~ /^\[[^\]]+\]:\s.*/) {
                return i
            }
        }
        return lines.size()
    }

    /** Drops leading/trailing blank lines and every "### " block without a bullet line; blocks are separated by one blank line. */
    @NonCPS
    private static List<String> keepFilledSubsections(List<String> raw) {
        List<List<String>> blocks = new ArrayList<List<String>>()
        List<String> current = new ArrayList<String>()
        for (String line : raw) {
            if (line.startsWith('### ') && !current.isEmpty()) {
                blocks.add(current)
                current = new ArrayList<String>()
            }
            current.add(line)
        }
        blocks.add(current)

        List<String> result = new ArrayList<String>()
        for (List<String> block : blocks) {
            List<String> trimmed = trimBlank(block)
            if (trimmed.isEmpty()) {
                continue
            }
            if (trimmed.get(0).startsWith('### ') && !hasBullet(trimmed.subList(1, trimmed.size()))) {
                continue
            }
            if (!result.isEmpty()) {
                result.add('')
            }
            result.addAll(trimmed)
        }
        return result
    }

    /** A bullet line starts with "- " or "* " after optional indentation. */
    @NonCPS
    private static boolean hasBullet(List<String> lines) {
        for (String line : lines) {
            if (line ==~ /^\s*[-*] .*/) {
                return true
            }
        }
        return false
    }

    @NonCPS
    private static List<String> trimBlank(List<String> block) {
        int start = 0
        int end = block.size()
        while (start < end && block.get(start).trim() == '') {
            start++
        }
        while (end > start && block.get(end - 1).trim() == '') {
            end--
        }
        return new ArrayList<String>(block.subList(start, end))
    }
}
