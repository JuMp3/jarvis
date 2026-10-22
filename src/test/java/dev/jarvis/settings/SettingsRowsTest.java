package dev.jarvis.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties.DesktopApp;

class SettingsRowsTest {

    @Test
    void appsBecomeCommandsWithTheProgramFirstAndAnIdFromTheName() {
        String json = SettingsRows.appsJson(List.of(
                Map.of("name", "IntelliJ IDEA", "program", "C:/Program Files/IntelliJ/idea64.exe", "arguments", "{target}"),
                Map.of("name", "Teams", "program", "explorer.exe", "arguments", "ms-teams:"),
                Map.of("name", "", "program", "", "arguments", ""), // left empty: ignored
                Map.of("name", "teams", "program", "teams.exe", "arguments", "")));
        Map<String, Object> properties = new HashMap<>(Map.of(SettingsCatalog.APPS, json));

        SettingsRows.expandApps(SettingsCatalog.APPS, properties);

        assertThat(properties).doesNotContainKey(SettingsCatalog.APPS)
                .containsEntry("jarvis.desktop.apps.intellij-idea.description", "IntelliJ IDEA")
                .containsEntry("jarvis.desktop.apps.intellij-idea.command[0]", "C:/Program Files/IntelliJ/idea64.exe")
                .containsEntry("jarvis.desktop.apps.intellij-idea.command[1]", "{target}")
                .containsEntry("jarvis.desktop.apps.teams.command[1]", "ms-teams:")
                .containsEntry("jarvis.desktop.apps.teams-2.command[0]", "teams.exe")
                .doesNotContainKey("jarvis.desktop.apps.teams-2.command[1]");
    }

    @Test
    void anAppNeedsBothNameAndProgram() {
        assertThatThrownBy(() -> SettingsRows.appsJson(List.of(Map.of("name", "Outlook", "program", " "))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Outlook");
        assertThat(SettingsRows.appsJson(null)).isEqualTo("[]");
    }

    @Test
    void appsGoBackToTheFormAsTheyWereTyped() {
        assertThat(SettingsRows.appRows(Map.of("code", new DesktopApp("VS Code", List.of("code", "--new-window", "{target}")))))
                .containsExactly(Map.of("name", "VS Code", "program", "code", "arguments", "--new-window {target}"));
    }
}
