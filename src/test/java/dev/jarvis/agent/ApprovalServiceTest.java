package dev.jarvis.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ApprovalServiceTest {

    private final BlockingQueue<ApprovalRequest> sent = new LinkedBlockingQueue<>();
    private final List<String> closed = new CopyOnWriteArrayList<>();

    private final ApprovalChannel channel = new ApprovalChannel() {
        @Override
        public void send(ApprovalRequest request) {
            sent.add(request);
        }

        @Override
        public void closed(ApprovalRequest request) {
            closed.add(request.id());
        }
    };

    private ApprovalService service(Duration timeout) {
        return new ApprovalService(channel, timeout, timeout);
    }

    private static CompletableFuture<Boolean> askInTurn(ApprovalService service) {
        return CompletableFuture.supplyAsync(() -> service.requestApproval("conv", "s1", "Salvare la nota"));
    }

    private static CompletableFuture<Boolean> askFromTask(ApprovalService service) {
        return CompletableFuture.supplyAsync(() ->
                service.requestTaskApproval("conv", "coder", "Aggiungi README", "Eseguire il piano"));
    }

    @Test
    void returnsTheUsersAnswerAndClosesTheRequest() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> result = askInTurn(service);

        ApprovalRequest request = sent.poll(2, TimeUnit.SECONDS);
        assertEquals("s1", request.sessionId());
        assertTrue(service.resolve("conv", request.id(), true));
        assertTrue(result.get(2, TimeUnit.SECONDS));
        assertEquals(0, service.pendingCount());
        assertEquals(List.of(request.id()), closed);
    }

    @Test
    void anyTabOfTheConversationMayAnswerButNoOtherConversation() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> result = askInTurn(service);

        ApprovalRequest request = sent.poll(2, TimeUnit.SECONDS);
        assertFalse(service.resolve("intruder", request.id(), true));
        assertTrue(service.resolve("conv", request.id(), false));
        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void timeoutMeansDenied() {
        assertFalse(service(Duration.ofMillis(50)).requestApproval("conv", "s1", "Salvare la nota"));
    }

    @Test
    void endingTheTurnDeniesItsRequestsButNotThoseOfTasks() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> turn = askInTurn(service);
        CompletableFuture<Boolean> task = askFromTask(service);
        sent.poll(2, TimeUnit.SECONDS);
        sent.poll(2, TimeUnit.SECONDS);

        service.denyAll("s1");

        assertFalse(turn.get(2, TimeUnit.SECONDS));
        assertFalse(task.isDone());
        List<ApprovalRequest> stillOpen = service.pendingTaskRequests("conv");
        assertEquals(1, stillOpen.size());
        assertEquals("coder", stillOpen.getFirst().agent());
        assertTrue(service.resolve("conv", stillOpen.getFirst().id(), true));
        assertTrue(task.get(2, TimeUnit.SECONDS));
    }

    @Test
    void interruptingTheTaskWithdrawsItsRequest() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        Thread asker = Thread.ofVirtual().start(() ->
                service.requestTaskApproval("conv", "coder", "Aggiungi README", "Eseguire il piano"));
        ApprovalRequest request = sent.poll(2, TimeUnit.SECONDS);

        asker.interrupt();
        asker.join(2000);

        assertEquals(0, service.pendingCount());
        assertEquals(List.of(request.id()), closed);
    }

    @Test
    void undeliverableTurnRequestIsDenied() {
        ApprovalService service = new ApprovalService(new ApprovalChannel() {
            @Override
            public void send(ApprovalRequest request) {
                throw new IllegalStateException("closed");
            }

            @Override
            public void closed(ApprovalRequest request) {
            }
        }, Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertFalse(service.requestApproval("conv", "s1", "Salvare la nota"));
        assertFalse(service.requestApproval("conv", null, "Salvare la nota"));
    }
}
