package dev.jarvis.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.config.JarvisProperties.DesktopApp;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** How a row of the {@code setting} table becomes Spring properties. Pure logic. */
final class SettingsRows {

    static final String TEXT = "text";
    static final String SECRET = "secret";
    static final String LIST = "list";

    private static final Logger log = LoggerFactory.getLogger(SettingsRows.class);
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };

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

    /**
     * The apps of the Settings form ({@code name}, {@code program}, {@code arguments}) as the JSON saved in the
     * database, after checking them.
     *
     * @throws IllegalArgumentException with a message for the user
     */
    static String appsJson(Object raw) {
        List<Map<String, String>> apps = new ArrayList<>();
        if (raw instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> fields)) {
                    throw new IllegalArgumentException("App non valida.");
                }
                String name = text(fields.get("name"));
                String program = text(fields.get("program"));
                if (name.isEmpty() && program.isEmpty()) {
                    continue; // a row left empty
                }
                if (name.isEmpty() || program.isEmpty()) {
                    throw new IllegalArgumentException("App: servono sia il nome sia il programma ("
                            + (name.isEmpty() ? program : name) + ").");
                }
                apps.add(Map.of("name", name, "program", program, "arguments", text(fields.get("arguments"))));
            }
        } else if (raw != null && !text(raw).isEmpty()) {
            throw new IllegalArgumentException("App non valide.");
        }
        return JSON.writeValueAsString(apps);
    }

    /**
     * The saved apps as Spring properties: {@code jarvis.desktop.apps.<id>.description} and {@code .command[i]},
     * the program first, then the arguments split at spaces (started without a shell). The id is the name in
     * lower case, the one the model passes to launchApp.
     */
    static void expandApps(String name, Map<String, Object> target) {
        Object saved = target.remove(name);
        if (saved == null) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = JSON.readValue(saved.toString(), ROWS);
        } catch (JacksonException e) {
            log.error("Setting {} is not valid JSON and is ignored: save the apps again in Settings", name);
            return;
        }
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String base = id(text(row.get("name")));
            int n = used.merge(base, 1, Integer::sum);
            String id = n == 1 ? base : base + "-" + n;
            target.put(name + "." + id + ".description", text(row.get("name")));
            List<String> command = new ArrayList<>(List.of(text(row.get("program"))));
            String arguments = text(row.get("arguments"));
            if (!arguments.isEmpty()) {
                command.addAll(List.of(arguments.split("\\s+")));
            }
            for (int i = 0; i < command.size(); i++) {
                target.put(name + "." + id + ".command[" + i + "]", command.get(i));
            }
        }
    }

    /** The other way, for the Settings form. */
    static List<Map<String, String>> appRows(Map<String, DesktopApp> apps) {
        return apps.values().stream().map(app -> {
            List<String> command = app.command() == null ? List.of() : app.command();
            return Map.of("name", app.description() == null ? "" : app.description(),
                    "program", command.isEmpty() ? "" : command.getFirst(),
                    "arguments", String.join(" ", command.size() > 1 ? command.subList(1, command.size()) : List.of()));
        }).toList();
    }

    static String id(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return id.isEmpty() ? "app" : id;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }
}
