package dev.jarvis.settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

/**
 * The settings saved by the wizard and the Settings section (table {@code setting}), as a property source
 * that overrides application.yml and .env, but not environment variables or command-line arguments.
 *
 * <p>Loaded on first use with plain JDBC, since it is read before the DataSource exists. In development the
 * database may still be starting (Docker Compose): until it answers, lookups find nothing and a new attempt
 * is made at most once a second. A missing table (first start) counts as "no settings".
 */
final class DatabaseSettingsSource extends EnumerablePropertySource<Object> {

    static final String NAME = "jarvisSettings";
    private static final Logger log = LoggerFactory.getLogger(DatabaseSettingsSource.class);
    private static final long RETRY_MS = 1000;
    private static final String UNDEFINED_TABLE = "42P01";

    private final Environment environment;
    private volatile Map<String, Object> values;
    private long nextAttempt;
    private boolean loading;

    DatabaseSettingsSource(Environment environment) {
        super(NAME, new Object());
        this.environment = environment;
    }

    @Override
    public Object getProperty(String name) {
        return values().get(name);
    }

    @Override
    public String[] getPropertyNames() {
        return values().keySet().toArray(String[]::new);
    }

    private Map<String, Object> values() {
        Map<String, Object> loaded = values;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (values != null) {
                return values;
            }
            // Reading the datasource properties below goes through this source again
            if (loading || System.currentTimeMillis() < nextAttempt) {
                return Map.of();
            }
            loading = true;
            try {
                values = load();
                return values;
            } catch (SQLException e) {
                if (UNDEFINED_TABLE.equals(e.getSQLState())) {
                    values = Map.of();
                    return values;
                }
                nextAttempt = System.currentTimeMillis() + RETRY_MS;
                log.debug("Settings database not reachable yet: {}", e.getMessage());
                return Map.of();
            } finally {
                loading = false;
            }
        }
    }

    private Map<String, Object> load() throws SQLException {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null) {
            return Map.of();
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", environment.getProperty("spring.datasource.username", ""));
        credentials.setProperty("password", environment.getProperty("spring.datasource.password", ""));
        credentials.setProperty("connectTimeout", "2");
        SecretBox box = new SecretBox(MasterKey.load(environment.getProperty(MasterKey.ENVIRONMENT_VARIABLE),
                Path.of(environment.getProperty("jarvis.settings.key-file",
                        System.getProperty("user.home") + "/.jarvis/master.key"))));
        Map<String, Object> settings = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, credentials);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name, kind, value FROM setting")) {
            while (rows.next()) {
                SettingsRows.expand(rows.getString("name"), rows.getString("kind"), rows.getString("value"), box, settings);
            }
        }
        SettingsRows.expandApps(SettingsCatalog.APPS, settings);
        // Under the configuration keys too: application.yml no longer refers to the names
        for (SettingsCatalog.Definition definition : SettingsCatalog.ALL) {
            Object value = settings.get(definition.name());
            if (value != null) {
                SettingsCatalog.properties(definition).forEach(property -> settings.putIfAbsent(property, value));
            }
        }
        log.info("Loaded {} settings from the database", settings.size());
        return Map.copyOf(settings);
    }
}
