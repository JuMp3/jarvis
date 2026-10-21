package dev.jarvis.settings;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** How a row of the {@code setting} table becomes Spring properties. Pure logic. */
final class SettingsRows {

    static final String TEXT = "text";
    static final String SECRET = "secret";
    static final String LIST = "list";

    private static final Logger log = LoggerFactory.getLogger(SettingsRows.class);

    private SettingsRows() {
    }

    /**
     * Text as is, secrets decrypted, lists (one item per line) as {@code name[0]}, {@code name[1]}…: a list
     * defined here replaces the whole list of application.yml, as Spring does between property sources.
     */
    static void expand(String name, String kind, String value, SecretBox box, Map<String, Object> target) {
        switch (kind) {
            case SECRET -> {
                try {
                    target.put(name, box.decrypt(name, value));
                } catch (IllegalStateException e) {
                    log.error("Setting {} cannot be decrypted and is ignored: enter it again in Settings ({})",
                            name, e.getMessage());
                }
            }
            case LIST -> {
                List<String> items = value.lines().map(String::strip).filter(item -> !item.isEmpty()).toList();
                if (items.isEmpty()) {
                    target.put(name, "");
                }
                for (int i = 0; i < items.size(); i++) {
                    target.put(name + "[" + i + "]", items.get(i));
                }
            }
            default -> target.put(name, value);
        }
    }
}
