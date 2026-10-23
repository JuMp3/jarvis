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
    void aKeyShowsOnlyForTheProviderThatNeedsIt() {
        java.util.Map<String, Object> chosen = new java.util.HashMap<>(java.util.Map.of(
                "JARVIS_PROVIDERS", java.util.List.of("anthropic"), "JARVIS_VOICE_STT", "groq",
                "JARVIS_VOICE_TTS", "local", "jarvis.language", "it"));
        assertEquals(java.util.List.of("ANTHROPIC_API_KEY", "jarvis.voice.groq.api-key"), shownKeys(chosen));

        chosen.put("JARVIS_VOICE_TTS", "azure");
        chosen.put("JARVIS_PROVIDERS", java.util.List.of("anthropic", "openai"));
        assertEquals(java.util.List.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "jarvis.voice.groq.api-key",
                "jarvis.voice.azure.key"), shownKeys(chosen));
        assertEquals("it-IT", azureVoiceShown(chosen).substring(0, 5), "the voice of the chosen language");

        chosen.put("jarvis.language", "en");
        assertEquals("en", azureVoiceShown(chosen).substring(0, 2));
    }

    private static java.util.List<String> shownKeys(java.util.Map<String, Object> chosen) {
        return SettingsCatalog.ALL.stream()
                .filter(definition -> definition.kind() == SettingsCatalog.Kind.SECRET && definition.shown(chosen::get))
                .map(SettingsCatalog.Definition::name).filter(name -> !name.equals("GITHUB_TOKEN")).distinct().toList();
    }

    private static String azureVoiceShown(java.util.Map<String, Object> chosen) {
        return SettingsCatalog.ALL.stream()
                .filter(definition -> definition.name().startsWith("jarvis.voice.azure.voices.") && definition.shown(chosen::get))
                .findFirst().orElseThrow().values().getFirst();
    }

    @Test
    void theWizardAsksTheLanguageFirst() {
        assertEquals("jarvis.language", SettingsCatalog.ALL.getFirst().name());
    }
}
