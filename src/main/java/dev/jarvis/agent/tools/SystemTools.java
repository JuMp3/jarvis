package dev.jarvis.agent.tools;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.i18n.Text;

/**
 * Read-only tools: no approval needed.
 */
@Component
public class SystemTools {

    private final DateTimeFormatter format;

    public SystemTools(JarvisProperties properties) {
        // day and month in the user's language: the model repeats them as they are
        this.format = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)", Text.locale(properties.language()));
    }

    @Tool(description = "Restituisce data, ora e fuso orario correnti del computer dell'utente.")
    public String currentDateTime() {
        return ZonedDateTime.now().format(format);
    }
}
