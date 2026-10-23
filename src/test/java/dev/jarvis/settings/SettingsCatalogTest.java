package dev.jarvis.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class SettingsCatalogTest {

    @Test
    void theDefaultLanguageIsTheSystemsWhenJarvisSpeaksItOtherwiseEnglish() {
        assertEquals("it", SettingsCatalog.language(Locale.ITALY));
        assertEquals("en", SettingsCatalog.language(Locale.UK));
        assertEquals("en", SettingsCatalog.language(Locale.FRANCE));
    }

    @Test
    void theWizardAsksTheLanguageFirst() {
        assertEquals("jarvis.language", SettingsCatalog.ALL.getFirst().name());
    }
}
