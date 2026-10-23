package dev.jarvis.task;

import static dev.jarvis.i18n.Text.text;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.config.JarvisProperties.Specialist;
import dev.jarvis.i18n.Text;
import dev.jarvis.llm.ProviderId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The coder: drives Claude Code as an external process on an allowed git repository.
 *
 * <p>Flow: read-only plan ({@code --permission-mode plan}) → the user approves it → new branch
 * {@code jarvis/...} → the same session resumes and works, with edits allowed and only the configured shell
 * commands; Claude Code is always denied {@code git push} → Jarvis itself pushes, if the user agrees. {@code --bare} skips the user's hooks and plugins and
 * authenticates only with the API key Jarvis passes; {@code --restricted} ignores every settings file and
 * confines the file tools to the repository.
 */
final class ClaudeCodeSpecialist implements SpecialistRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeSpecialist.class);
    private static final String TOOLS = "Read,Edit,Write,Glob,Grep,Bash";
    private static final List<String> ALWAYS_DENIED = List.of("Bash(git push:*)", "Bash(git remote:*)");
    private static final int STDERR_TAIL = 2000;
    private static final long GIT_TIMEOUT_SECONDS = 60;
    private static final long PUSH_TIMEOUT_SECONDS = 180;
    private static final Text STOPPED = text("Fermato.", "Stopped.");
    private static final Text NONE = text("nessuno", "none");

    private final Specialist spec;
    private final WorkspacePolicy policy;
    private final Map<String, String> environment;
    private final JsonMapper json;
    private final String language;

    /**
     * @param environment variables for the process: the Anthropic key and headers
     * @param language    of the user: what they read (plan, questions, progress, report)
     */
    ClaudeCodeSpecialist(String id, Specialist spec, Map<String, String> environment, JsonMapper json, String language) {
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
        this.language = language;
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
            throw new TaskFailure(text("Il repository %s ha modifiche non salvate. Fai un commit o mettile da parte "
                            + "(git stash), poi riprova: il programmatore parte sempre da uno stato pulito.",
                    "The repository %s has uncommitted changes. Commit them or put them aside (git stash), then try "
                            + "again: the programmer always starts from a clean state.").in(language, repo));
        }
        String base = git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip();
        String branch = "jarvis/" + slug(task.title()) + "-" + task.id().substring(0, 6);
        double budget = spec.maxBudgetUsd();

        context.step(1, 4); // plan, your approval, work, push
        context.progress(text("Studia il repository e prepara un piano", "Studies the repository and prepares a plan")
                .in(language));
        Run plan = claude(repo, List.of("--session-id", UUID.randomUUID().toString(), "--permission-mode", "plan"),
                planPrompt(task, repo), budget, context);
        if (!plan.outcome().success() || plan.planText().isBlank()) {
            throw new TaskFailure(text("Il programmatore non è riuscito a preparare un piano (%s): %s",
                    "The programmer could not prepare a plan (%s): %s")
                    .in(language, plan.outcome().subtype(), plan.outcome().text()));
        }
        double remaining = budget - plan.outcome().costUsd();
        context.step(2, 4);
        if (!context.requestApproval(approvalText(repo, base, branch, plan.planText(), remaining))) {
            throw new TaskFailure(AgentTask.Status.CANCELLED, text("Piano non approvato: il repository non è stato toccato.",
                    "Plan not approved: the repository was not touched.").in(language));
        }

        git(repo, "switch", "-c", branch);
        context.step(3, 4);
        context.progress(text("Lavora sul branch %s", "Works on branch %s").in(language, branch));
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
            text.append(text("Il programmatore si è fermato prima di finire (%s): quello che segue è un risultato parziale.",
                    "The programmer stopped before finishing (%s): what follows is a partial result.")
                    .in(language, work.outcome().subtype())).append("\n\n");
        }
        text.append(text("Branch %s, partito da %s.\n\nCommit:\n%s\n\nFile modificati:\n%s",
                "Branch %s, started from %s.\n\nCommits:\n%s\n\nChanged files:\n%s").in(language, branch, base,
                commits.isBlank() ? NONE.in(language) : commits, files.isBlank() ? NONE.in(language) : files));
        if (!leftover.isBlank()) {
            text.append(text("\n\nModifiche rimaste senza commit:\n%s", "\n\nChanges left uncommitted:\n%s")
                    .in(language, leftover));
        }
        context.step(4, 4);
        text.append("\n\n").append(push(repo, branch, commits, files, context));
        text.append(text("\n\nResoconto del programmatore:\n%s", "\n\nThe programmer's report:\n%s")
                .in(language, work.outcome().text()));
        return new Specialists.Result(text.toString(),
                plan.outcome().inputTokens() + work.outcome().inputTokens(),
                plan.outcome().outputTokens() + work.outcome().outputTokens(),
                plan.outcome().costUsd() + work.outcome().costUsd());
    }

    /**
     * Pushes the branch only after the user says yes. Jarvis runs the push itself, with the user's git
     * credentials: Claude Code is never allowed to.
     */
    private String push(Path repo, String branch, String commits, String files, TaskContext context) {
        if (commits.isBlank()) {
            return text("Nessun push: non ci sono commit.", "No push: there are no commits.").in(language);
        }
        String remote;
        try {
            remote = git(repo, "remote", "get-url", "origin").strip();
        } catch (TaskFailure e) {
            return text("Nessun push: il repository non ha un remote origin.", "No push: the repository has no origin remote.")
                    .in(language);
        }
        String question = text("Il programmatore ha finito sul branch %s.\n\nCommit:\n%s\n\nFile modificati:\n%s\n\n"
                        + "Vuoi che faccia il push del branch su %s?",
                "The programmer has finished on branch %s.\n\nCommits:\n%s\n\nChanged files:\n%s\n\n"
                        + "Do you want me to push the branch to %s?").in(language, branch, commits, files, remote);
        if (!context.requestApproval(question)) {
            return text("Nessun push: il branch resta solo in locale.", "No push: the branch stays local only.").in(language);
        }
        context.progress(text("Push del branch %s", "Pushing branch %s").in(language, branch));
        try {
            git(repo, PUSH_TIMEOUT_SECONDS, "push", "-u", "origin", branch);
            return text("Push fatto: %s su %s.", "Pushed: %s to %s.").in(language, branch, remote);
        } catch (TaskFailure e) {
            return text("Push non riuscito, il branch resta in locale. %s", "Push failed, the branch stays local. %s")
                    .in(language, e.getMessage());
        }
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
            throw new TaskFailure(text("Non riesco ad avviare Claude Code (comando \"%s\"): %s. Installalo o imposta "
                            + "jarvis.agents.coder.command.",
                    "I can't start Claude Code (command \"%s\"): %s. Install it or set jarvis.agents.coder.command.")
                    .in(language, spec.command(), e.getMessage()));
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
                    String progress = ClaudeCodeEvents.progress(event, language).orElse(null);
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
                throw new TaskFailure(text("Claude Code si è chiuso senza risultato (codice %s): %s",
                        "Claude Code ended without a result (code %s): %s").in(language, exit, stderr.toString().strip()));
            }
            String planText = plan.get().isBlank() ? outcome.get().text() : plan.get();
            return new Run(outcome.get(), planText);
        } catch (InterruptedException e) {
            // Task cancelled or timed out: stop Claude Code and whatever it launched (build, tests)
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TaskFailure(AgentTask.Status.CANCELLED, STOPPED.in(language));
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

    private String planPrompt(AgentTask task, Path repo) {
        String prompt = "Compito:\n" + task.instructions();
        if (task.expectedOutput() != null && !task.expectedOutput().isBlank()) {
            prompt += "\n\nRisultato atteso:\n" + task.expectedOutput();
        }
        return prompt + "\n\nLavori nel repository " + repo + ". Studialo quanto serve e proponi un piano conciso: "
                + "file da toccare, passi, come verificherai il risultato (build e test). In questa fase non "
                + "modificare nulla. Il branch di lavoro lo crea Jarvis dopo l'approvazione: non prevederne altri, "
                + "e non prevedere push. Scrivi il piano in " + Text.promptName(language) + ".";
    }

    private String executePrompt(String branch) {
        return "L'utente ha approvato il piano: eseguilo ora, sul branch corrente " + branch + ". Fai commit "
                + "piccoli con messaggi chiari. Non fare push e non cambiare branch. Se il progetto ha build e test, "
                + "lanciali e correggi gli errori. Alla fine riassumi in " + Text.promptName(language)
                + " cosa hai fatto, cosa hai verificato e cosa resta da fare.";
    }

    private String approvalText(Path repo, String base, String branch, String plan, double remainingUsd) {
        return text("Piano per %s:\n\n%s\n\nSe approvi lavorerà sul nuovo branch %s (da %s): potrà modificare file, "
                        + "lanciare build e test e fare commit. Il push, se vorrai, te lo chiederò alla fine. "
                        + "Spesa massima residua: %.2f $.",
                "Plan for %s:\n\n%s\n\nIf you approve, it will work on the new branch %s (from %s): it can edit files, "
                        + "run builds and tests and commit. I will ask you about the push at the end. "
                        + "Remaining spending limit: $%.2f.")
                .in(language, repo, plan.strip(), branch, base, remainingUsd);
    }

    static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[àáâä]", "a").replaceAll("[èéêë]", "e").replaceAll("[ìíîï]", "i")
                .replaceAll("[òóôö]", "o").replaceAll("[ùúûü]", "u")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        slug = slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
        return slug.isEmpty() ? "task" : slug;
    }

    private String git(Path repo, String... args) {
        return git(repo, GIT_TIMEOUT_SECONDS, args);
    }

    private String git(Path repo, long timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0"); // fail instead of waiting for a typed password
            Process process = builder.start();
            // Read on another thread, so the timeout holds even if git never closes its output
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            }, runnable -> Thread.ofVirtual().start(runnable));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new TaskFailure(text("git %s non ha risposto in tempo.", "git %s did not answer in time.")
                        .in(language, String.join(" ", args)));
            }
            String text = output.join();
            if (process.exitValue() != 0) {
                throw new TaskFailure(text("git %s non è riuscito: %s", "git %s failed: %s")
                        .in(language, String.join(" ", args), text.strip()));
            }
            return text;
        } catch (IOException e) {
            throw new TaskFailure(text("Non riesco a eseguire git: %s", "I can't run git: %s").in(language, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure(AgentTask.Status.CANCELLED, STOPPED.in(language));
        }
    }
}
