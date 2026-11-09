package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class InfisicalAuthCommandsTest {
    @Test
    void everyNonEmptyCombinationSelectsOnlyItsFieldsAndPreflightsAllWrites() {
        (1..7).each { int mask ->
            boolean id = (mask & 1) != 0
            boolean secret = (mask & 2) != 0
            boolean url = (mask & 4) != 0
            List<String> commands = new InfisicalAuthCommands().update([
                clientId: id ? 'replacement-id' : '',
                clientSecret: secret ? 'replacement-secret' : '',
                url: url ? 'https://replacement.example.test/api' : '',
                connectionName: 'infisical-prod'
            ])
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
            assertThrows(IllegalArgumentException) { new InfisicalAuthCommands().update(inputs) }
        }
    }

    @Test
    void urlValidationRejectsMalformedAddressesBeforeReturningCommands() {
        ['bad', 'file:///etc/passwd', 'https://user:secret@example.test/api',
            'https://example.test/api?token=x', 'https://example.test/api#x',
            'https://example.test/api\n'].each { String url ->
            assertThrows(IllegalArgumentException) {
                new InfisicalAuthCommands().update([clientId: 'new-id', url: url, connectionName: 'infisical-prod'])
            }
        }
    }

    @Test
    void credentialsTargetIsFixedAndDoesNotRequireAConnectionName() {
        List<String> commands = new InfisicalAuthCommands().update([clientId: 'new-id'])
        assertTrue(commands.every {
            it.contains("--namespace 'infisical' patch secret 'universal-auth-credentials'")
        })
        assertFalse(commands.any { it.contains('infisicalconnections') })
    }

    @Test
    void connectionNameIsSelectableAndQuotedInTheFixedNamespace() {
        ['infisical-prod', 'infisical-dev', "name'; touch UNEXPECTED; '"].each { String name ->
            List<String> commands = new InfisicalAuthCommands().update([
                url: 'https://example.test/api', connectionName: name
            ])
            assertEquals(2, commands.size())
            assertTrue(commands.every {
                it.contains("--namespace 'default' patch infisicalconnections.secrets.infisical.com " +
                    ShellQuote.quote(name))
            })
            assertFalse(commands.any { it.contains('patch secret ') })
        }
    }
}
