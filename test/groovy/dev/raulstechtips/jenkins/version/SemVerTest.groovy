package dev.raulstechtips.jenkins.version

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
