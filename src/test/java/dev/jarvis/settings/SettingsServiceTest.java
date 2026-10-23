package dev.jarvis.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import dev.jarvis.BaseTest;
import dev.jarvis.routine.RoutineRepository;

/** Settings saved on a real PostgreSQL, then read back as Spring properties like at the next start. */
class SettingsServiceTest extends BaseTest {

    @TempDir
    Path tmp;

    @Autowired
    JdbcClient jdbc;

    private MockEnvironment environment;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM setting").update();
        environment = new MockEnvironment()
                .withProperty("spring.datasource.url", POSTGRES.getJdbcUrl())
                .withProperty("spring.datasource.username", POSTGRES.getUsername())
                .withProperty("spring.datasource.password", POSTGRES.getPassword())
                .withProperty("jarvis.settings.key-file", tmp.resolve("master.key").toString());
        jdbc.sql("DELETE FROM routine").update();
        settings = new SettingsService(jdbc, environment, new RoutineRepository(jdbc));
    }

    /** What the next start sees. */
    private DatabaseSettingsSource nextStart() {
        return new DatabaseSettingsSource(environment);
    }

    @Test
    void secretsAreEncryptedInTheDatabaseAndReadBackAtTheNextStart() {
        assertThat(settings.setupRequired()).isTrue();

        settings.save(Map.of("ANTHROPIC_API_KEY", "sk-ant-segreta", "JARVIS_VOICE_TTS", "browser",
                "jarvis.documents.folders", tmp + "\n" + tmp.resolve("altri")), List.of());

        String stored = jdbc.sql("SELECT value FROM setting WHERE name = 'ANTHROPIC_API_KEY'").query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain("segreta");
        DatabaseSettingsSource next = nextStart();
        assertThat(next.getProperty("ANTHROPIC_API_KEY")).isEqualTo("sk-ant-segreta");
        assertThat(next.getProperty("spring.ai.anthropic.api-key")).isEqualTo("sk-ant-segreta");
        assertThat(next.getProperty("JARVIS_VOICE_TTS")).isEqualTo("browser");
        assertThat(next.getProperty("jarvis.documents.folders[1]")).isEqualTo(tmp.resolve("altri").toString());
        assertThat(next.getProperty(SettingsService.SETUP_COMPLETED)).isEqualTo("true");
    }

    @Test
    void emptySecretKeepsTheSavedOneAndClearedRemovesIt() {
        settings.save(Map.of("GITHUB_TOKEN", "github_pat_1"), List.of());
        settings.save(Map.of("GITHUB_TOKEN", ""), List.of());
        assertThat(nextStart().getProperty("GITHUB_TOKEN")).isEqualTo("github_pat_1");
        assertThat(nextStart().getProperty("jarvis.mcp.servers.github.token")).isEqualTo("github_pat_1");

        settings.save(Map.of(), List.of("GITHUB_TOKEN"));
        assertThat(nextStart().getProperty("GITHUB_TOKEN")).isNull();
        assertThat(nextStart().getProperty("jarvis.mcp.servers.github.token")).isNull();
    }

    @Test
    void appsAreSavedAsJsonAndReadBackAsCommands() {
        settings.save(Map.of(SettingsCatalog.APPS, List.of(
                Map.of("name", "Outlook", "program", "C:/Office/OUTLOOK.EXE", "arguments", ""))), List.of());

        DatabaseSettingsSource next = nextStart();
        assertThat(next.getProperty("jarvis.desktop.apps.outlook.command[0]")).isEqualTo("C:/Office/OUTLOOK.EXE");
        assertThat(next.getProperty(SettingsCatalog.APPS)).isNull();

        settings.save(Map.of(SettingsCatalog.APPS, List.of()), List.of());
        assertThat(nextStart().getProperty("jarvis.desktop.apps.outlook.command[0]")).isNull();
    }

    @Test
    void routinesAreEditedInTheirTableAndKeepTheDayTheyLastRan() {
        settings.save(Map.of(SettingsCatalog.ROUTINES, List.of(Map.of("name", "Briefing", "time", "23:59",
                "days", List.of("mon", "fri"), "instructions", "Meteo e posta", "enabled", true))), List.of());
        RoutineRepository routines = new RoutineRepository(jdbc);
        String id = routines.all().getFirst().id();
        routines.markRun(id, java.time.LocalDate.now());

        settings.save(Map.of(SettingsCatalog.ROUTINES, List.of(Map.of("id", id, "name", "Briefing del mattino",
                "time", "23:59", "days", List.of("mon"), "instructions", "Meteo", "enabled", false))), List.of());
        var edited = routines.find(id).orElseThrow();
        assertThat(edited.name()).isEqualTo("Briefing del mattino");
        assertThat(edited.enabled()).isFalse();
        assertThat(edited.lastRun()).as("already run today: not again").isEqualTo(java.time.LocalDate.now());
        assertThat(settings.view("it").stream().filter(view -> view.name().equals(SettingsCatalog.ROUTINES))
                .findFirst().orElseThrow().value()).asString().contains("Briefing del mattino", "23:59", "[mon]");

        assertThatThrownBy(() -> settings.save(Map.of(SettingsCatalog.ROUTINES, List.of(Map.of("name", "Senza giorni",
                "time", "08:00", "days", List.of(), "instructions", "x"))), List.of())).hasMessageContaining("giorno");
        settings.save(Map.of(SettingsCatalog.ROUTINES, List.of()), List.of());
        assertThat(routines.all()).isEmpty();
    }

    @Test
    void theDefaultProviderIsOneOfTheEnabledOnes() {
        assertThatThrownBy(() -> settings.save(Map.of("JARVIS_PROVIDERS", List.of("anthropic"),
                "jarvis.providers.default-provider", "openai", "ANTHROPIC_API_KEY", "sk-ant-1"), List.of()))
                .hasMessageContaining("abilitati");
        settings.save(Map.of("JARVIS_PROVIDERS", List.of("anthropic"), "jarvis.providers.default-provider", "anthropic",
                "ANTHROPIC_API_KEY", "sk-ant-1"), List.of());
    }

    @Test
    void aChosenProviderNeedsItsKeyToBeSaved() {
        assertThatThrownBy(() -> settings.save(Map.of("JARVIS_VOICE_TTS", "azure",
                "jarvis.voice.azure.region", "westeurope"), List.of())).hasMessageContaining("Chiave API di Azure");
        assertThatThrownBy(() -> settings.save(Map.of("JARVIS_VOICE_TTS", "azure", "jarvis.voice.azure.key", "k"),
                List.of())).hasMessageContaining("Regione di Azure");

        settings.save(Map.of("JARVIS_VOICE_TTS", "azure", "jarvis.voice.azure.key", "k",
                "jarvis.voice.azure.region", "westeurope"), List.of());
        assertThat(nextStart().getProperty("jarvis.voice.azure.key")).isEqualTo("k");
        // Not chosen: no key needed
        settings.save(Map.of("JARVIS_VOICE_TTS", "local"), List.of());
    }

    @Test
    void theOpenAiKeyFeedsBothChatAndVoice() {
        settings.save(Map.of("OPENAI_API_KEY", "sk-proj-1"), List.of());
        DatabaseSettingsSource next = nextStart();
        assertThat(next.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-proj-1");
        assertThat(next.getProperty("jarvis.voice.openai.api-key")).isEqualTo("sk-proj-1");
    }

    @Test
    void invalidValuesAreRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> settings.save(Map.of("ANTHROPIC_API_KEY", "sk-ant-x", "JARVIS_VOICE_STT", "sempre"),
                List.of())).hasMessageContaining("scegli tra");
        assertThatThrownBy(() -> settings.save(Map.of("jarvis.agents.coder.workspaces", tmp.resolve("manca").toString()),
                List.of())).hasMessageContaining("non esiste");
        assertThatThrownBy(() -> settings.save(Map.of("spring.datasource.url", "x"), List.of()))
                .hasMessageContaining("sconosciuta");
        assertThat(jdbc.sql("SELECT count(*) FROM setting").query(Integer.class).single()).isZero();
    }

    @Test
    void outlookWritesTheConnectionTheToolsAndTheOrganizerAndOffRemovesThem() {
        settings.save(Map.of(SettingsCatalog.MAIL, "outlook"), List.of());

        DatabaseSettingsSource next = nextStart();
        assertThat(next.getProperty("spring.ai.mcp.client.stdio.connections.mail.args[1]"))
                .isEqualTo(MailTemplates.MS365_PACKAGE);
        assertThat(next.getProperty("spring.ai.mcp.client.stdio.connections.mail.env.MS365_MCP_TENANT_ID")).isEqualTo("consumers");
        assertThat(next.getProperty("jarvis.mcp.servers.mail.write-tools[0]")).isEqualTo("send-mail");
        assertThat(next.getProperty("jarvis.agents.organizer.tools[1]")).isEqualTo("mcp:mail");

        settings.save(Map.of(SettingsCatalog.MAIL, "off"), List.of());
        assertThat(jdbc.sql("SELECT count(*) FROM setting WHERE name LIKE 'jarvis.agents.organizer.%'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void microsoft365UsesTheOrganizationalAuthorityAndImapNeedsNoMcpServer() {
        settings.save(Map.of(SettingsCatalog.MAIL, "microsoft365"), List.of());
        assertThat(nextStart().getProperty("spring.ai.mcp.client.stdio.connections.mail.env.MS365_MCP_TENANT_ID"))
                .isEqualTo("organizations");

        settings.save(Map.of(SettingsCatalog.MAIL, "imap", "jarvis.mail.address", "me@gmail.com",
                "MAIL_PASSWORD", "abcd efgh ijkl mnop"), List.of());
        DatabaseSettingsSource next = nextStart();
        assertThat(next.getProperty("jarvis.mail.enabled")).isEqualTo("true");
        assertThat(next.getProperty("jarvis.agents.organizer.tools[1]")).isEqualTo("mail");
        assertThat(next.getProperty("MAIL_PASSWORD")).isEqualTo("abcd efgh ijkl mnop");
        assertThat(next.getProperty("spring.ai.mcp.client.stdio.connections.mail.command")).isNull();
    }

    @Test
    void anotherMasterKeyCannotReadTheSecrets() {
        settings.save(Map.of("OPENAI_API_KEY", "sk-segreta"), List.of());
        environment.setProperty("jarvis.settings.key-file", tmp.resolve("other.key").toString());

        assertThat(nextStart().getProperty("OPENAI_API_KEY")).isNull();
    }

    @Test
    void unreachableDatabaseMeansNoSettingsYet() {
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/none");
        assertThat(nextStart().getProperty("ANTHROPIC_API_KEY")).isNull();
    }
}
