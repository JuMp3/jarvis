package dev.jarvis.agent.tools;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Read-only tools: no approval needed.
 */
@Component
public class SystemTools {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)", Locale.ITALIAN);

    @Tool(description = "Restituisce data, ora e fuso orario correnti del computer dell'utente.")
    public String currentDateTime() {
        return ZonedDateTime.now().format(FORMAT);
    }
}
