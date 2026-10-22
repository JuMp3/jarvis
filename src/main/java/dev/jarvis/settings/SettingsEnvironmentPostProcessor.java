package dev.jarvis.settings;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Puts the saved settings right after the environment variables: they override application.yml and .env,
 * while an environment variable or a command-line argument still wins (handy in development).
 * Disabled with {@code jarvis.settings.enabled=false} (tests).
 */
public class SettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Always, tests included: application.yml has no value for the settings
        if (!environment.getPropertySources().contains(SettingsDefaultsSource.NAME)) {
            environment.getPropertySources().addLast(new SettingsDefaultsSource());
        }
        if (!environment.getProperty("jarvis.settings.enabled", Boolean.class, true)
                || environment.getPropertySources().contains(DatabaseSettingsSource.NAME)) {
            return;
        }
        DatabaseSettingsSource settings = new DatabaseSettingsSource(environment);
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, settings);
        } else {
            environment.getPropertySources().addFirst(settings);
        }
    }

    /** After application.yml and .env are read, so the datasource properties are known. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }
}
