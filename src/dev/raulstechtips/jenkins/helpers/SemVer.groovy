package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * An immutable X.Y.Z version. Each part is a BigInteger, so large numbers are kept exactly. There
 * is no pre-release or build part.
 */
class SemVer implements Serializable, Comparable<SemVer> {
    final BigInteger major
    final BigInteger minor
    final BigInteger patch

    private SemVer(BigInteger major, BigInteger minor, BigInteger patch) {
        this.major = major
        this.minor = minor
        this.patch = patch
    }

    /**
     * Parses an X.Y.Z version.
     *
     * @param value the text to parse
     * @return the parsed version
     * @throws IllegalArgumentException when value is null or is not three dot-separated numbers
     */
    @NonCPS
    static SemVer parse(String value) {
        if (value == null || !(value ==~ /^[0-9]+[.][0-9]+[.][0-9]+$/)) {
            throw new IllegalArgumentException('Version must be X.Y.Z')
        }
        String[] parts = value.split(/[.]/)
        new SemVer(new BigInteger(parts[0]), new BigInteger(parts[1]), new BigInteger(parts[2]))
    }

    /**
     * Returns a new version with the patch number increased by one.
     *
     * @return the bumped version
     */
    @NonCPS
    SemVer bumpPatch() {
        new SemVer(major, minor, patch + BigInteger.ONE)
    }

    /**
     * Compares by major, then minor, then patch number.
     *
     * @param other the version to compare with
     * @return a negative number, zero or a positive number when this version is lower than, equal
     *     to or higher than other
     */
    @Override
    @NonCPS
    int compareTo(SemVer other) {
        int result = major.compareTo(other.major)
        if (result == 0) {
            result = minor.compareTo(other.minor)
        }
        result == 0 ? patch.compareTo(other.patch) : result
    }

    /**
     * Returns the version as X.Y.Z.
     *
     * @return the version text
     */
    @Override
    @NonCPS
    String toString() {
        "${major}.${minor}.${patch}".toString()
    }
}
