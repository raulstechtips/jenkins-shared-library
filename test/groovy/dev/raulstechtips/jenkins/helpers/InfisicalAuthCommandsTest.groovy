package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class InfisicalAuthCommandsTest {
    private final Map targets = [
        credentialsNamespace: 'infisical', credentialsSecretName: 'universal-auth-credentials',
        connectionNamespace: 'default', connectionName: 'infisical-prod'
    ]

    @Test
    void everyNonEmptyCombinationSelectsOnlyItsFieldsAndPreflightsAllWrites() {
        (1..7).each { int mask ->
            boolean id = (mask & 1) != 0
            boolean secret = (mask & 2) != 0
            boolean url = (mask & 4) != 0
            List<String> commands = new InfisicalAuthCommands().update([
                clientId: id ? 'replacement-id' : '',
                clientSecret: secret ? 'replacement-secret' : '',
                url: url ? 'https://replacement.example.test/api' : ''
            ], targets)
            int resources = (id || secret ? 1 : 0) + (url ? 1 : 0)
            assertEquals(resources * 2, commands.size())
            assertTrue(commands.take(resources).every { it.contains('--dry-run=server') })
            assertTrue(commands.drop(resources).every { !it.contains('--dry-run=server') })
            assertEquals(id, commands.any { it.contains('env.INFISICAL_CLIENT_ID') })
            assertEquals(secret, commands.any { it.contains('env.INFISICAL_CLIENT_SECRET') })
            assertEquals(url, commands.any { it.contains('env.INFISICAL_URL') })
            assertFalse(commands.any { it.contains('replacement') })
        }
    }

    @Test
    void allMissingOrBlankInputsFail() {
        [[:], [clientId: '', clientSecret: '', url: ''],
            [clientId: ' \t', clientSecret: '\n', url: ' ']].each { Map inputs ->
            assertThrows(IllegalArgumentException) { new InfisicalAuthCommands().update(inputs, targets) }
        }
    }

    @Test
    void urlValidationRejectsMalformedAddressesBeforeReturningCommands() {
        ['bad', 'file:///etc/passwd', 'https://user:secret@example.test/api',
            'https://example.test/api?token=x', 'https://example.test/api#x',
            'https://example.test/api\n'].each { String url ->
            assertThrows(IllegalArgumentException) {
                new InfisicalAuthCommands().update([clientId: 'new-id', url: url], targets)
            }
        }
    }

    @Test
    void resourceNamesAreQuotedAndUnselectedResourceConfigurationIsNotRequired() {
        List<String> commands = new InfisicalAuthCommands().update([clientId: 'new-id'], [
            credentialsNamespace: 'namespace',
            credentialsSecretName: "name'; touch UNEXPECTED; '"
        ])
        assertTrue(commands.every { it.contains(ShellQuote.quote("name'; touch UNEXPECTED; '")) })
        assertFalse(commands.any { it.contains('infisicalconnections') })
        assertEquals(2, new InfisicalAuthCommands().update([url: 'https://example.test/api'], [
            connectionNamespace: 'default', connectionName: 'infisical-prod'
        ]).size())
    }
}
