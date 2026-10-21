package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.jarvis.BaseTest;
import dev.jarvis.agent.ApprovalChannel;
import dev.jarvis.agent.ApprovalRequest;
import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.task.AgentTask.Status;

/** Real repository on PostgreSQL, simulated specialist. */
class TaskServiceTest extends BaseTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    TaskRepository repository;

    private final Specialists specialists = mock(Specialists.class);
    private final List<AgentTask> published = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final BlockingQueue<ApprovalRequest> approvalRequests = new LinkedBlockingQueue<>();
    private final ApprovalService approvals = new ApprovalService(new ApprovalChannel() {
        @Override
        public void send(ApprovalRequest request) {
            approvalRequests.add(request);
        }

        @Override
        public void closed(ApprovalRequest request) {
        }
    }, Duration.ofSeconds(5), Duration.ofSeconds(5));
    private TaskService service;
    private String conversation;

    @BeforeEach
    void setUp() {
        doThrow(new IllegalArgumentException("Specialista sconosciuto: hacker"))
                .when(specialists).validate(eq("hacker"), any());
        doReturn(ProviderId.ANTHROPIC).when(specialists).provider(anyString());
        conversation = "conv-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (service != null) {
            service.destroy();
        }
    }

    private TaskService service(int maxParallel, Duration timeout) {
        JarvisProperties properties = new JarvisProperties(null, null, null,
                new JarvisProperties.Tasks(maxParallel, timeout, Duration.ofSeconds(5)), null);
        service = new TaskService(specialists, repository,
                event -> published.add(((TaskService.TaskUpdated) event).task()), approvals, properties);
        return service;
    }

    /** The specialist waits until the test releases it, then answers. */
    private void specialistWaitsForRelease() {
        doAnswer(invocation -> {
            release.await();
            return new Specialists.Result("Fatto.", 120, 30, null);
        }).when(specialists).run(any(), any());
    }

    private Status statusOf(AgentTask task) {
        return repository.find(task.id()).orElseThrow().status();
    }

    @Test
    void runsAtMostMaxParallelTasksAndQueuesTheRest() {
        specialistWaitsForRelease();
        TaskService tasks = service(2, Duration.ofMinutes(1));

        List<AgentTask> started = List.of(
                tasks.start(conversation, "researcher", "A", "a", null, null),
                tasks.start(conversation, "researcher", "B", "b", null, null),
                tasks.start(conversation, "researcher", "C", "c", null, null));

        await().atMost(WAIT).until(() -> started.stream().filter(t -> statusOf(t) == Status.RUNNING).count() == 2);
        assertThat(started).filteredOn(t -> statusOf(t) == Status.QUEUED).hasSize(1);

        release.countDown();
        await().atMost(WAIT).until(() -> started.stream().allMatch(t -> statusOf(t) == Status.COMPLETED));
        AgentTask done = repository.find(started.getFirst().id()).orElseThrow();
        assertThat(done.result()).isEqualTo("Fatto.");
        assertThat(done.inputTokens()).isEqualTo(120);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(tasks.recent(conversation)).extracting(AgentTask::title).containsExactly("A", "B", "C");
    }

    @Test
    void cancelStopsARunningTaskAndIgnoresItsLateResult() {
        specialistWaitsForRelease();
        TaskService tasks = service(2, Duration.ofMinutes(1));
        AgentTask task = tasks.start(conversation, "researcher", "A", "a", null, null);
        await().atMost(WAIT).until(() -> statusOf(task) == Status.RUNNING);

        assertThat(tasks.cancel("another-conversation", task.id())).isFalse();
        assertThat(tasks.cancel(conversation, task.id())).isTrue();

        assertThat(statusOf(task)).isEqualTo(Status.CANCELLED);
        assertThat(tasks.cancel(conversation, task.id())).isFalse();
    }

    @Test
    void timeoutFailsTheTask() {
        specialistWaitsForRelease();
        AgentTask task = service(2, Duration.ofMillis(300)).start(conversation, "researcher", "A", "a", null, null);

        await().atMost(WAIT).until(() -> statusOf(task) == Status.FAILED);
        assertThat(repository.find(task.id()).orElseThrow().error()).startsWith("Tempo scaduto");
    }

    @Test
    void failingSpecialistGivesAReadableReason() {
        doThrow(new IllegalStateException("boom")).when(specialists).run(any(), any());
        AgentTask task = service(2, Duration.ofMinutes(1)).start(conversation, "researcher", "A", "a", null, null);

        await().atMost(WAIT).until(() -> statusOf(task) == Status.FAILED);
        assertThat(repository.find(task.id()).orElseThrow().error()).isEqualTo("Qualcosa è andato storto. Riprova.");
        assertThat(published).extracting(AgentTask::status)
                .containsExactly(Status.QUEUED, Status.RUNNING, Status.FAILED);
    }

    /** The specialist asks to approve a plan, like the coder does, and gives up if declined. */
    private void specialistAsksForApproval() {
        doAnswer(invocation -> {
            TaskContext context = invocation.getArgument(1);
            context.progress("Prepara il piano");
            if (!context.requestApproval("Piano: aggiungere README")) {
                throw new TaskFailure(Status.CANCELLED, "Piano non approvato.");
            }
            return new Specialists.Result("Fatto.", 10, 5, 0.42);
        }).when(specialists).run(any(), any());
    }

    @Test
    void approvedTaskWaitsWithItsTimeoutPausedThenCompletes() throws Exception {
        specialistAsksForApproval();
        AgentTask task = service(2, Duration.ofMillis(400)).start(conversation, "coder", "README", "a", null, null);

        ApprovalRequest request = approvalRequests.poll(5, TimeUnit.SECONDS);
        assertThat(request.agent()).isEqualTo("coder");
        assertThat(request.taskTitle()).isEqualTo("README");
        assertThat(statusOf(task)).isEqualTo(Status.WAITING_APPROVAL);
        Thread.sleep(800); // longer than the task timeout: waiting for the user does not count

        assertThat(approvals.resolve(conversation, request.id(), true)).isTrue();
        await().atMost(WAIT).until(() -> statusOf(task) == Status.COMPLETED);
        assertThat(repository.find(task.id()).orElseThrow().costUsd()).isEqualTo(0.42);
        assertThat(published).extracting(AgentTask::progress).contains("Prepara il piano");
    }

    @Test
    void declinedPlanCancelsTheTask() throws Exception {
        specialistAsksForApproval();
        AgentTask task = service(2, Duration.ofMinutes(1)).start(conversation, "coder", "README", "a", null, null);

        ApprovalRequest request = approvalRequests.poll(5, TimeUnit.SECONDS);
        approvals.resolve(conversation, request.id(), false);

        await().atMost(WAIT).until(() -> statusOf(task) == Status.CANCELLED);
        assertThat(repository.find(task.id()).orElseThrow().error()).isEqualTo("Piano non approvato.");
    }

    @Test
    void cancellingAWaitingTaskWithdrawsItsApproval() throws Exception {
        specialistAsksForApproval();
        TaskService tasks = service(2, Duration.ofMinutes(1));
        AgentTask task = tasks.start(conversation, "coder", "README", "a", null, null);
        approvalRequests.poll(5, TimeUnit.SECONDS);

        assertThat(tasks.cancel(conversation, task.id())).isTrue();

        await().atMost(WAIT).until(() -> approvals.pendingTaskRequests(conversation).isEmpty());
        assertThat(statusOf(task)).isEqualTo(Status.CANCELLED);
    }

    @Test
    void unknownSpecialistIsRejected() {
        assertThatThrownBy(() -> service(2, Duration.ofMinutes(1)).start(conversation, "hacker", "A", "a", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hacker");
    }

    @Test
    void unfinishedTasksAreMarkedInterruptedAtStartup() {
        AgentTask left = AgentTask.queued(UUID.randomUUID().toString(), conversation, "researcher", "A", "a", null, null)
                .running();
        repository.save(left);

        service(2, Duration.ofMinutes(1));

        AgentTask after = repository.find(left.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(after.error()).contains("riavviato");
    }
}
