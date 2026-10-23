package dev.jarvis.routine;

import static dev.jarvis.i18n.Text.text;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;

/** Jarvis' tools to create, list and remove routines; creating and removing ask the user first. */
@Component
public class RoutineTools {

    private final RoutineService routines;
    private final RoutineRepository repository;
    private final ApprovalService approvals;
    private final String language;

    public RoutineTools(RoutineService routines, RoutineRepository repository, ApprovalService approvals,
                        JarvisProperties properties) {
        this.routines = routines;
        this.repository = repository;
        this.approvals = approvals;
        this.language = properties.language();
    }

    @Tool(description = "Crea una routine: un lavoro che Jarvis fa da solo nei giorni e all'ora indicati, per esempio "
            + "un briefing del mattino con meteo, posta e calendario. Lo esegue uno specialista che non vede la "
            + "conversazione; il risultato arriva come aggiornamento automatico. L'utente deve confermare.")
    public String createRoutine(@ToolParam(description = "Nome breve, mostrato all'utente") String name,
                                @ToolParam(description = "Ora, HH:mm") String time,
                                @ToolParam(description = "Giorni: mon,tue,wed,thu,fri,sat,sun, intervalli come "
                                        + "mon-fri, oppure every day") String days,
                                @ToolParam(description = "Istruzioni complete e autosufficienti: cosa fare e cosa "
                                        + "riferire") String instructions,
                                ToolContext toolContext) {
        Routine routine;
        try {
            routine = routines.prepare(name, time, days, instructions);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String description = text("Creare la routine «%s»: %s alle %s.\n\n%s", "Create the routine “%s”: %s at %s.\n\n%s")
                .in(language, routine.name(), RoutineService.describeDays(routine.days(), language), routine.at(),
                        routine.instructions());
        if (!approvals.requestApproval(toolContext, description)) {
            return "L'utente non ha confermato. Nessuna routine creata.";
        }
        repository.save(routine);
        return "Routine creata: " + routine.name() + ", " + RoutineSchedule.format(routine.days()) + " alle " + routine.at()
                + (routine.lastRun() != null ? " (l'ora di oggi è passata: la prima volta sarà al prossimo giorno previsto)" : "")
                + ".";
    }

    @Tool(description = "Elenca le routine dell'utente: nome, giorni, ora, se è attiva, istruzioni.")
    public String listRoutines() {
        List<Routine> all = repository.all();
        return all.isEmpty() ? "Nessuna routine." : all.stream()
                .map(routine -> "- " + routine.name() + " | " + RoutineSchedule.format(routine.days()) + " " + routine.at()
                        + (routine.enabled() ? "" : " | sospesa") + "\n  " + routine.instructions())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Toglie una routine. L'utente deve confermare.")
    public String deleteRoutine(@ToolParam(description = "Nome della routine, come in listRoutines") String name,
                                ToolContext toolContext) {
        Routine routine = repository.all().stream()
                .filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).equals(
                        name == null ? "" : name.strip().toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (routine == null) {
            return "Nessuna routine con nome " + name + ". " + listRoutines();
        }
        if (!approvals.requestApproval(toolContext, text("Togliere la routine «%s»?", "Remove the routine “%s”?")
                .in(language, routine.name()))) {
            return "L'utente non ha confermato. La routine resta.";
        }
        repository.delete(routine.id());
        return "Routine tolta: " + routine.name() + ".";
    }
}
