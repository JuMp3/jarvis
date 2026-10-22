package dev.jarvis.settings;

import org.springframework.core.env.MapPropertySource;

/** The defaults of the settings (SettingsCatalog.defaults), below every other source, application.yml included. */
final class SettingsDefaultsSource extends MapPropertySource {

    static final String NAME = "jarvisSettingsDefaults";

    SettingsDefaultsSource() {
        super(NAME, SettingsCatalog.defaults());
    }
}
