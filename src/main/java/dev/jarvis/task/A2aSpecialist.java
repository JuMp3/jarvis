package dev.jarvis.task;

import static dev.jarvis.i18n.Text.text;

import java.io.IOException;
import java.net.URI;

import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.llm.ProviderId;

/**
 * An external agent reached through A2A (Settings → Agenti esterni): Jarvis gives it a task like to any specialist.
 * What it answers is data from outside, read-only as every specialist's: actions stay Jarvis', after approval.
 */
final class A2aSpecialist implements SpecialistRunner {

    private final String name;
    private final URI address;
    private final A2aClient client;
    private final String language;

    A2aSpecialist(String id, Specialist spec, String language) {
        this.name = id;
        this.address = A2aClient.address(spec.url());
        this.client = new A2aClient(spec.token());
        this.language = language;
    }

    @Override
    public ProviderId provider() {
        return ProviderId.ANTHROPIC; // not a model of Jarvis': its failures are told as TaskFailure
    }

    @Override
    public void validate(String workspace) {
        // no workspace
    }

    @Override
    public Specialists.Result run(AgentTask task, TaskContext context) {
        context.progress(text("Lo chiede all'agente %s", "Asks the agent %s").in(language, name));
        String prompt = task.instructions() + (task.expectedOutput() == null || task.expectedOutput().isBlank() ? ""
                : "\n\nRisultato atteso:\n" + task.expectedOutput());
        try {
            URI endpoint = client.card(address).map(A2aClient.Card::endpoint).orElse(address);
            String answer = client.send(endpoint, prompt);
            if (answer.isBlank()) {
                throw new TaskFailure(text("L'agente %s non ha risposto nulla.", "The agent %s answered nothing.")
                        .in(language, name));
            }
            return new Specialists.Result(answer, null, null, null);
        } catch (IOException | IllegalArgumentException e) {
            throw new TaskFailure(text("Non riesco a lavorare con l'agente %s: %s", "I can't work with the agent %s: %s")
                    .in(language, name, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure(AgentTask.Status.CANCELLED, text("Fermato.", "Stopped.").in(language));
        }
    }
}
