package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

class SemVer implements Serializable, Comparable<SemVer> {
    final BigInteger major
    final BigInteger minor
    final BigInteger patch

    private SemVer(BigInteger major, BigInteger minor, BigInteger patch) {
        this.major = major
        this.minor = minor
        this.patch = patch
    }

    @NonCPS
    static SemVer parse(String value) {
        if (value == null || !(value ==~ /^[0-9]+[.][0-9]+[.][0-9]+$/)) {
            throw new IllegalArgumentException('Version must be X.Y.Z')
        }
        String[] parts = value.split(/[.]/)
        new SemVer(new BigInteger(parts[0]), new BigInteger(parts[1]), new BigInteger(parts[2]))
    }

    @NonCPS
    SemVer bumpPatch() {
        new SemVer(major, minor, patch + BigInteger.ONE)
    }

    @Override
    @NonCPS
    int compareTo(SemVer other) {
        int result = major.compareTo(other.major)
        if (result == 0) {
            result = minor.compareTo(other.minor)
        }
        result == 0 ? patch.compareTo(other.patch) : result
    }

    @Override
    @NonCPS
    String toString() {
        "${major}.${minor}.${patch}".toString()
    }
}
