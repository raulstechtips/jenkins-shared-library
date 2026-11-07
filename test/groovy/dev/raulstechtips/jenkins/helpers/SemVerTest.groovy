package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class SemVerTest {
    @Test
    void parsesThreeNumericComponents() {
        def version = SemVer.parse('1.2.3')
        assertEquals('1.2.3', version.toString())
        assertEquals(1G, version.major)
        assertEquals(2G, version.minor)
        assertEquals(3G, version.patch)
        assertTrue(version instanceof Serializable)
    }

    @Test
    void comparesNumerically() {
        assertTrue(SemVer.parse('1.10.0') > SemVer.parse('1.9.0'))
        assertTrue(SemVer.parse('2.0.0') > SemVer.parse('1.99.99'))
        assertTrue(SemVer.parse('1.0.10') > SemVer.parse('1.0.9'))
        assertEquals(0, SemVer.parse('1.2.3').compareTo(SemVer.parse('1.2.3')))
    }

    @Test
    void bumpsPatchWithoutMutatingOriginal() {
        def version = SemVer.parse('1.2.9')
        assertEquals('1.2.10', version.bumpPatch().toString())
        assertEquals('1.2.9', version.toString())
    }

    @Test
    void bumpsMinorAndResetsPatchWithoutMutatingOriginal() {
        def version = SemVer.parse('1.9.7')
        assertEquals('1.10.0', version.bumpMinor().toString())
        assertEquals('0.1.0', SemVer.parse('0.0.0').bumpMinor().toString())
        assertEquals('1.9.7', version.toString())
    }

    @Test
    void highestReleaseTagIgnoresPrefixPrereleaseAndPeeledTags() {
        String output = 'a\trefs/tags/1.2.0\nb\trefs/tags/1.9.0\nc\trefs/tags/1.9.0^{}\n' +
            'd\trefs/tags/1.9.9-rc1\ne\trefs/tags/v9.0.0\nf\trefs/tags/1.3.0\n' +
            'g\trefs/tags/1.9.10\nh\trefs/tags/1.9.9\n'
        assertEquals('1.9.10', SemVer.highestReleaseTag(output))
    }

    @Test
    void highestReleaseTagIsNullWithoutRelease() {
        assertNull(SemVer.highestReleaseTag(''))
        assertNull(SemVer.highestReleaseTag(null))
        assertNull(SemVer.highestReleaseTag('a\trefs/tags/v1.0.0\nb\trefs/tags/1.0.0-rc1\n'))
    }

    @Test
    void rejectsMissingPatch() {
        assertThrows(IllegalArgumentException) { SemVer.parse('1.0') }
    }

    @Test
    void rejectsTagPrefix() {
        assertThrows(IllegalArgumentException) { SemVer.parse('v1.0.0') }
    }

    @Test
    void rejectsPrereleaseSuffix() {
        assertThrows(IllegalArgumentException) { SemVer.parse('1.0.0-rc1') }
    }

    @Test
    void supportsUnboundedNumericComponents() {
        assertEquals('1.0.100000000000000000000',
            SemVer.parse('1.0.99999999999999999999').bumpPatch().toString())
    }
}
