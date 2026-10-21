package dev.jarvis.task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.llm.ProviderId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The coder: drives Claude Code as an external process on an allowed git repository.
 *
 * <p>Flow: read-only plan ({@code --permission-mode plan}) → the user approves it → new branch
 * {@code jarvis/...} → the same session resumes and works, with edits allowed and only the configured shell
 * commands; {@code git push} is always denied. {@code --bare} skips the user's hooks and plugins and
 * authenticates only with the API key Jarvis passes; {@code --restricted} ignores every settings file and
 * confines the file tools to the repository.
 */
final class ClaudeCodeSpecialist implements SpecialistRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeSpecialist.class);
    private static final String TOOLS = "Read,Edit,Write,Glob,Grep,Bash";
    private static final List<String> ALWAYS_DENIED = List.of("Bash(git push:*)", "Bash(git remote:*)");
    private static final int STDERR_TAIL = 2000;
    private static final long GIT_TIMEOUT_SECONDS = 60;

    private final Specialist spec;
    private final WorkspacePolicy policy;
    private final Map<String, String> environment;
    private final JsonMapper json;

    /** @param environment variables for the process: the Anthropic key and headers */
    ClaudeCodeSpecialist(String id, Specialist spec, Map<String, String> environment, JsonMapper json) {
        if (spec.maxBudgetUsd() == null || spec.maxBudgetUsd() <= 0) {
            throw new IllegalStateException("jarvis.agents." + id + ".max-budget-usd is required for claude-code");
        }
        this.spec = spec;
        this.policy = new WorkspacePolicy(spec.workspaces());
        if (policy.roots().isEmpty()) {
            throw new IllegalStateException("jarvis.agents." + id + ".workspaces: no existing folder configured");
        }
        this.environment = environment;
        this.json = json;
    }

    @Override
    public ProviderId provider() {
        return ProviderId.ANTHROPIC;
    }

    @Override
    public void validate(String workspace) {
        policy.resolve(workspace);
    }

    List<Path> repositories() {
        return policy.repositories();
    }

    @Override
    public Specialists.Result run(AgentTask task, TaskContext context) {
        Path repo = policy.resolve(task.workspace());
        if (!git(repo, "status", "--porcelain").isBlank()) {
            throw new TaskFailure("Il repository " + repo + " ha modifiche non salvate. Fai un commit o mettile da "
                    + "parte (git stash), poi riprova: il programmatore parte sempre da uno stato pulito.");
        }
        String base = git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip();
        String branch = "jarvis/" + slug(task.title()) + "-" + task.id().substring(0, 6);
        double budget = spec.maxBudgetUsd();

        context.progress("Studia il repository e prepara un piano");
        Run plan = claude(repo, List.of("--session-id", UUID.randomUUID().toString(), "--permission-mode", "plan"),
                planPrompt(task, repo), budget, context);
        if (!plan.outcome().success() || plan.planText().isBlank()) {
            throw new TaskFailure("Il programmatore non è riuscito a preparare un piano (" + plan.outcome().subtype()
                    + "): " + plan.outcome().text());
        }
        double remaining = budget - plan.outcome().costUsd();
        if (!context.requestApproval(approvalText(repo, base, branch, plan.planText(), remaining))) {
            throw new TaskFailure(AgentTask.Status.CANCELLED, "Piano non approvato: il repository non è stato toccato.");
        }

        git(repo, "switch", "-c", branch);
        context.progress("Lavora sul branch " + branch);
        List<String> mode = new ArrayList<>(List.of("--resume", plan.outcome().sessionId(),
                "--permission-mode", "acceptEdits"));
        List<String> allowed = spec.allowedCommands() == null ? List.of() : spec.allowedCommands();
        if (!allowed.isEmpty()) {
            mode.add("--allowedTools");
            mode.addAll(allowed);
        }
        mode.add("--disallowedTools");
        mode.addAll(ALWAYS_DENIED);
        Run work = claude(repo, mode, executePrompt(branch), Math.max(remaining, 0.01), context);

        String commits = git(repo, "log", "--oneline", base + ".." + branch).strip();
        String files = git(repo, "diff", "--stat", base + "..." + branch).strip();
        String leftover = git(repo, "status", "--porcelain").strip();
        StringBuilder text = new StringBuilder();
        if (!work.outcome().success()) {
            text.append("Il programmatore si è fermato prima di finire (").append(work.outcome().subtype())
                    .append("): quello che segue è un risultato parziale.\n\n");
        }
        text.append("Branch ").append(branch).append(", partito da ").append(base).append(". Nessun push.\n\n")
                .append("Commit:\n").append(commits.isBlank() ? "nessuno" : commits).append("\n\n")
                .append("File modificati:\n").append(files.isBlank() ? "nessuno" : files);
        if (!leftover.isBlank()) {
            text.append("\n\nModifiche rimaste senza commit:\n").append(leftover);
        }
        text.append("\n\nResoconto del programmatore:\n").append(work.outcome().text());
        return new Specialists.Result(text.toString(),
                plan.outcome().inputTokens() + work.outcome().inputTokens(),
                plan.outcome().outputTokens() + work.outcome().outputTokens(),
                plan.outcome().costUsd() + work.outcome().costUsd());
    }

    private record Run(ClaudeCodeEvents.Outcome outcome, String planText) {
    }

    private Run claude(Path repo, List<String> modeArgs, String prompt, double budgetUsd, TaskContext context) {
        Path systemPrompt = null;
        try {
            systemPrompt = Files.createTempFile("jarvis-coder-", ".md");
            Files.writeString(systemPrompt, systemPrompt(repo), StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(spec.command() == null ? "claude" : spec.command(),
                    "-p", "--bare", "--restricted", "--tools", TOOLS,
                    "--output-format", "stream-json", "--verbose",
                    "--max-budget-usd", String.format(Locale.ROOT, "%.2f", budgetUsd),
                    "--append-system-prompt-file", systemPrompt.toString()));
            if (spec.model() != null && !spec.model().isBlank()) {
                command.addAll(List.of("--model", spec.model()));
            }
            command.addAll(modeArgs);

            ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
            builder.environment().remove("CLAUDECODE"); // not a nested session of the Claude Code that started Jarvis
            builder.environment().putAll(environment);
            Process process = builder.start();
            return follow(process, prompt, context);
        } catch (IOException e) {
            throw new TaskFailure("Non riesco ad avviare Claude Code (comando \"" + spec.command() + "\"): "
                    + e.getMessage() + ". Installalo o imposta jarvis.agents.coder.command.");
        } finally {
            if (systemPrompt != null) {
                try {
                    Files.deleteIfExists(systemPrompt);
                } catch (IOException ignored) {
                    // temp file, removed by the OS eventually
                }
            }
        }
    }

    /** Sends the prompt on stdin, turns the event stream into progress, waits for the result. */
    private Run follow(Process process, String prompt, TaskContext context) throws IOException {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        AtomicReference<ClaudeCodeEvents.Outcome> outcome = new AtomicReference<>();
        AtomicReference<String> plan = new AtomicReference<>("");
        StringBuilder stderr = new StringBuilder();
        Thread errors = Thread.ofVirtual().start(() -> tail(process.getErrorStream(), stderr));
        Thread events = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String last = null;
                for (String line; (line = reader.readLine()) != null; ) {
                    JsonNode event;
                    try {
                        event = json.readTree(line);
                    } catch (RuntimeException e) {
                        continue; // not an event
                    }
                    String progress = ClaudeCodeEvents.progress(event).orElse(null);
                    if (progress != null && !progress.equals(last)) {
                        context.progress(progress);
                        last = progress;
                    }
                    ClaudeCodeEvents.plan(event).ifPresent(plan::set);
                    ClaudeCodeEvents.outcome(event).ifPresent(outcome::set);
                }
            } catch (IOException e) {
                log.debug("Claude Code output closed: {}", e.getMessage());
            }
        });
        try {
            int exit = process.waitFor();
            events.join();
            errors.join();
            if (outcome.get() == null) {
                throw new TaskFailure("Claude Code si è chiuso senza risultato (codice " + exit + "): "
                        + stderr.toString().strip());
            }
            String planText = plan.get().isBlank() ? outcome.get().text() : plan.get();
            return new Run(outcome.get(), planText);
        } catch (InterruptedException e) {
            // Task cancelled or timed out: stop Claude Code and whatever it launched (build, tests)
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TaskFailure(AgentTask.Status.CANCELLED, "Fermato.");
        }
    }

    private static void tail(InputStream stream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                synchronized (target) {
                    target.append(line).append('\n');
                    if (target.length() > STDERR_TAIL) {
                        target.delete(0, target.length() - STDERR_TAIL);
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private String systemPrompt(Path repo) throws IOException {
        StringBuilder prompt = new StringBuilder(spec.systemPrompt() == null ? "" : spec.systemPrompt());
        // --bare skips CLAUDE.md discovery: pass the project's own instructions explicitly
        Path projectInstructions = repo.resolve("CLAUDE.md");
        if (Files.isRegularFile(projectInstructions)) {
            prompt.append("\n\nIstruzioni del progetto (CLAUDE.md):\n")
                    .append(Files.readString(projectInstructions, StandardCharsets.UTF_8));
        }
        return prompt.toString();
    }

    private static String planPrompt(AgentTask task, Path repo) {
        String prompt = "Compito:\n" + task.instructions();
        if (task.expectedOutput() != null && !task.expectedOutput().isBlank()) {
            prompt += "\n\nRisultato atteso:\n" + task.expectedOutput();
        }
        return prompt + "\n\nLavori nel repository " + repo + ". Studialo quanto serve e proponi un piano conciso: "
                + "file da toccare, passi, come verificherai il risultato (build e test). In questa fase non "
                + "modificare nulla. Il branch di lavoro lo crea Jarvis dopo l'approvazione: non prevederne altri, "
                + "e non prevedere push. Scrivi il piano in italiano.";
    }

    private static String executePrompt(String branch) {
        return "L'utente ha approvato il piano: eseguilo ora, sul branch corrente " + branch + ". Fai commit "
                + "piccoli con messaggi chiari. Non fare push e non cambiare branch. Se il progetto ha build e test, "
                + "lanciali e correggi gli errori. Alla fine riassumi in italiano cosa hai fatto, cosa hai "
                + "verificato e cosa resta da fare.";
    }

    private static String approvalText(Path repo, String base, String branch, String plan, double remainingUsd) {
        return "Piano per " + repo + ":\n\n" + plan.strip() + "\n\nSe approvi lavorerà sul nuovo branch " + branch
                + " (da " + base + "): potrà modificare file, lanciare build e test e fare commit, mai push. "
                + String.format(Locale.ITALIAN, "Spesa massima residua: %.2f $.", remainingUsd);
    }

    static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[àáâä]", "a").replaceAll("[èéêë]", "e").replaceAll("[ìíîï]", "i")
                .replaceAll("[òóôö]", "o").replaceAll("[ùúûü]", "u")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        slug = slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
        return slug.isEmpty() ? "task" : slug;
    }

    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TaskFailure("git " + String.join(" ", args) + " non ha risposto in tempo.");
            }
            if (process.exitValue() != 0) {
                throw new TaskFailure("git " + String.join(" ", args) + " non è riuscito: " + output.strip());
            }
            return output;
        } catch (IOException e) {
            throw new TaskFailure("Non riesco a eseguire git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure(AgentTask.Status.CANCELLED, "Fermato.");
        }
    }
}
