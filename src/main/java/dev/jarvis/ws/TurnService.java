package dev.jarvis.ws;

import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.agent.AgentService;
import dev.jarvis.agent.ApprovalService;
import dev.jarvis.llm.ProviderId;
import dev.jarvis.voice.SentenceChunker;
import dev.jarvis.voice.TextToSpeech;
import dev.jarvis.voice.VoiceServices;

/**
 * Runs one turn: streams tokens to the client and, when the answer is spoken, turns each completed
 * sentence into audio on a single-threaded queue so clips arrive in order.
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final AgentService agent;
    private final VoiceServices voice;
    private final SessionRegistry sessions;
    private final ApprovalService approvals;

    public TurnService(AgentService agent, VoiceServices voice, SessionRegistry sessions, ApprovalService approvals) {
        this.agent = agent;
        this.voice = voice;
        this.sessions = sessions;
        this.approvals = approvals;
    }

    /** Queues an automatic message for Jarvis (e.g. a task result) and runs it once the session is idle. */
    void notify(SessionState session, String notice) {
        session.pendingNotices().add(notice);
        deliverPendingNotices(session);
    }

    private void deliverPendingNotices(SessionState session) {
        synchronized (session) {
            if (!session.idle() || session.pendingNotices().isEmpty()) {
                return;
            }
            StringBuilder notices = new StringBuilder();
            for (String notice; (notice = session.pendingNotices().poll()) != null; ) {
                notices.append(notices.isEmpty() ? "" : "\n\n").append(notice);
            }
            start(session, notices.toString(), session.lastTurnSpoken());
        }
    }

    void start(SessionState session, String userText, boolean speak) {
        String sessionId = session.id();
        session.lastTurnSpoken(speak);
        ProviderId provider = session.provider();
        Optional<TextToSpeech> tts = speak ? voice.textToSpeech() : Optional.empty();
        ExecutorService speechQueue = tts.isPresent()
                ? Executors.newSingleThreadExecutor(Thread.ofVirtual().name("tts-" + sessionId + "-", 0).factory())
                : null;

        StringBuffer streamed = new StringBuffer(); // appended on the stream thread, read on cancel
        Turn turn = new Turn(speechQueue, () -> {
            approvals.denyAll(sessionId);
            agent.closeInterruptedTurn(session.conversationId(), streamed.toString());
        });
        session.replaceTurn(turn);
        sessions.send(sessionId, Protocol.state("thinking"));

        SentenceChunker chunker = new SentenceChunker();
        AtomicInteger seq = new AtomicInteger();
        long startedAt = System.nanoTime();

        turn.attach(agent.stream(provider, session.conversationId(), sessionId, userText, speak)
                .subscribe(
                        token -> {
                            streamed.append(token);
                            sessions.send(sessionId, Protocol.token(token));
                            if (speechQueue != null) {
                                chunker.append(token).forEach(sentence ->
                                        speak(turn, speechQueue, tts.get(), sessionId, sentence, seq));
                            }
                        },
                        error -> {
                            log.atWarn().setMessage("Turn failed").setCause(error)
                                    .addKeyValue("provider", provider.id())
                                    .addKeyValue("conversationId", session.conversationId())
                                    .addKeyValue("durationMs", elapsedMs(startedAt))
                                    .log();
                            String message = ErrorMessages.forProvider(provider, error);
                            sessions.send(sessionId, Protocol.error(message));
                            if (speechQueue != null) {
                                speak(turn, speechQueue, tts.get(), sessionId, message, seq);
                            }
                            finish(turn, speechQueue, session);
                        },
                        () -> {
                            if (speechQueue != null) {
                                chunker.flush().forEach(sentence ->
                                        speak(turn, speechQueue, tts.get(), sessionId, sentence, seq));
                            }
                            log.atInfo().setMessage("Turn completed")
                                    .addKeyValue("provider", provider.id())
                                    .addKeyValue("conversationId", session.conversationId())
                                    .addKeyValue("spoken", speak)
                                    .addKeyValue("durationMs", elapsedMs(startedAt))
                                    .log();
                            finish(turn, speechQueue, session);
                        }));
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void speak(Turn turn, ExecutorService queue, TextToSpeech tts, String sessionId,
                       String sentence, AtomicInteger seq) {
        submit(queue, () -> {
            if (turn.cancelled() || turn.speechFailed()) {
                return;
            }
            try {
                TextToSpeech.SpeechAudio audio = tts.synthesize(sentence);
                if (!turn.cancelled()) {
                    sessions.send(sessionId, Protocol.audio(seq.getAndIncrement(), audio.mimeType(),
                            Base64.getEncoder().encodeToString(audio.data())));
                }
            } catch (RuntimeException e) {
                log.warn("Speech synthesis failed for session {}", sessionId, e);
                turn.markSpeechFailed();
                sessions.send(sessionId, Protocol.error(ErrorMessages.forVoice(e)));
            }
        });
    }

    /** "done" goes through the speech queue, so the client receives it after the last clip. */
    private void finish(Turn turn, ExecutorService queue, SessionState session) {
        Runnable done = () -> {
            if (!turn.cancelled()) {
                sessions.send(session.id(), Protocol.done());
            }
            turn.markFinished();
            // On a fresh thread: the next turn cancels this one, which would interrupt the speech queue
            Thread.ofVirtual().start(() -> deliverPendingNotices(session));
        };
        if (queue == null) {
            done.run();
            return;
        }
        submit(queue, done);
        queue.shutdown();
    }

    private static void submit(ExecutorService queue, Runnable task) {
        try {
            queue.submit(task);
        } catch (RejectedExecutionException e) {
            // turn already cancelled or finished
        }
    }
}
