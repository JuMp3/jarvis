package dev.jarvis.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ApprovalServiceTest {

    private final BlockingQueue<ApprovalRequest> sent = new LinkedBlockingQueue<>();

    private ApprovalService service(Duration timeout) {
        return new ApprovalService(sent::add, timeout);
    }

    private static CompletableFuture<Boolean> ask(ApprovalService service, String sessionId) {
        return CompletableFuture.supplyAsync(() -> service.requestApproval(sessionId, "Salvare la nota"));
    }

    @Test
    void returnsTheUsersAnswer() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> result = ask(service, "s1");

        ApprovalRequest request = sent.poll(2, TimeUnit.SECONDS);
        assertEquals("s1", request.sessionId());
        assertTrue(service.resolve("s1", request.id(), true));
        assertTrue(result.get(2, TimeUnit.SECONDS));
        assertEquals(0, service.pendingCount());
    }

    @Test
    void anotherSessionCannotAnswer() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> result = ask(service, "s1");

        ApprovalRequest request = sent.poll(2, TimeUnit.SECONDS);
        assertFalse(service.resolve("intruder", request.id(), true));
        service.resolve("s1", request.id(), false);
        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void timeoutMeansDenied() {
        assertFalse(service(Duration.ofMillis(50)).requestApproval("s1", "Salvare la nota"));
    }

    @Test
    void cancellingTheSessionDeniesOpenRequests() throws Exception {
        ApprovalService service = service(Duration.ofSeconds(5));
        CompletableFuture<Boolean> result = ask(service, "s1");
        sent.poll(2, TimeUnit.SECONDS);

        service.denyAll("s1");
        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void undeliverableRequestIsDenied() {
        ApprovalService service = new ApprovalService(request -> {
            throw new IllegalStateException("closed");
        }, Duration.ofSeconds(5));
        assertFalse(service.requestApproval("s1", "Salvare la nota"));
        assertFalse(service.requestApproval(null, "Salvare la nota"));
    }
}
