package dev.raulstechtips.jenkins.registry

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class RegistryCatalogTest {
    private final Map targets = [
        ghcr: [
            host: 'ghcr.io',
            'image-prefix': 'ghcr.io/raulstechtips',
            'chart-repo': 'oci://ghcr.io/raulstechtips/charts',
            'credentials-id': 'ghcr-push'
        ],
        harbor: [
            host: 'harbor.prod.raulstechtips.dev',
            'image-prefix': 'harbor.prod.raulstechtips.dev/raulstechtips',
            'chart-repo': 'oci://harbor.prod.raulstechtips.dev/raulstechtips/charts',
            'credentials-id': 'harbor-push'
        ]
    ]

    @Test
    void resolvesBothPinnedTargets() {
        RegistryCatalog catalog = new RegistryCatalog(targets)
        assertEquals([
            host: 'ghcr.io',
            'image-prefix': 'ghcr.io/raulstechtips',
            'chart-repo': 'oci://ghcr.io/raulstechtips/charts',
            'credentials-id': 'ghcr-push'
        ], catalog.target('ghcr'))
        assertEquals([
            host: 'harbor.prod.raulstechtips.dev',
            'image-prefix': 'harbor.prod.raulstechtips.dev/raulstechtips',
            'chart-repo': 'oci://harbor.prod.raulstechtips.dev/raulstechtips/charts',
            'credentials-id': 'harbor-push'
        ], catalog.target('harbor'))
    }

    @Test
    void rejectsUnknownTarget() {
        def failure = assertThrows(IllegalArgumentException) {
            new RegistryCatalog(targets).target('unknown')
        }
        assertTrue(failure.message.contains('unknown'))
    }

    @Test
    void returnsPlainTargetCopy() {
        def catalog = new RegistryCatalog(targets)
        Map target = catalog.target('ghcr')
        assertEquals(LinkedHashMap, target.getClass())
        target['credentials-id'] = 'changed'
        assertEquals('ghcr-push', catalog.target('ghcr')['credentials-id'])
    }

    @Test
    void emptyCatalogRejectsLookup() {
        assertThrows(IllegalArgumentException) { new RegistryCatalog([:]).target('ghcr') }
    }
}
