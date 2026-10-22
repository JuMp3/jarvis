package dev.jarvis.voice;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.config.JarvisProperties.Voice.Local;

/**
 * The local voice engines, run by Jarvis as its own processes: the server of whisper.cpp for speech-to-text (with
 * the OpenAI audio API, used through {@link OpenAiAudioClient}) and Piper for text-to-speech ({@link PiperTts}).
 *
 * <p>Binaries and models are under {@code jarvis.voice.local.home} ({@code whisper/}, {@code piper/},
 * {@code models/}, prepared by desktop/prepare-voice.ps1). Loading a model takes seconds, so both start in the
 * background with the application; a request that arrives earlier waits for them. They end with the application.
 */
final class LocalVoice implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalVoice.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    /** Whisper models by the value of jarvis.voice.local.stt-model: the small one quantized, as precise and lighter. */
    private static final Map<String, String> WHISPER_MODELS = Map.of(
            "small", "ggml-small-q5_1.bin",
            "base", "ggml-base.bin");
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);

    private final Path home;
    private final Local config;
    private final String language;
    private final CompletableFuture<OpenAiAudioClient> whisper = new CompletableFuture<>();
    private volatile Process whisperProcess;
    private volatile PiperTts piper;
    private volatile boolean closed;

    LocalVoice(Local config, String language) {
        this.home = Path.of(config.home());
        this.config = config;
        this.language = language == null || language.isBlank() ? "it" : language;
    }

    /** Speech-to-text through the whisper.cpp server, started now in the background. */
    SpeechToText speechToText(HttpClient http, OpenAiAudioClient.Settings settings) {
        Thread.ofVirtual().name("whisper-start").start(() -> startWhisper(http, settings));
        return (audio, mimeType) -> ready(whisper, "trascrizione").transcribe(audio, mimeType);
    }

    /** Text-to-speech with Piper, started now in the background. */
    TextToSpeech textToSpeech() {
        CompletableFuture<PiperTts> started = new CompletableFuture<>();
        Thread.ofVirtual().name("piper-start").start(() -> {
            try {
                PiperTts tts = new PiperTts(executable("piper", "piper"),
                        home.resolve("models").resolve(config.ttsVoice() + ".onnx"), logFile("piper.log"));
                piper = tts;
                tts.warmUp();
                log.info("Local text-to-speech ready: Piper {}", config.ttsVoice());
                started.complete(tts);
            } catch (IOException | RuntimeException e) {
                log.warn("Local text-to-speech not available: {}", e.getMessage());
                started.completeExceptionally(e);
            }
        });
        return text -> ready(started, "voce").synthesize(text);
    }

    private void startWhisper(HttpClient http, OpenAiAudioClient.Settings settings) {
        String modelFile = WHISPER_MODELS.getOrDefault(config.sttModel(), WHISPER_MODELS.get("small"));
        try {
            Path model = home.resolve("models").resolve(modelFile);
            requireFile(model);
            int port = freePort();
            // Measured on a hybrid laptop CPU (Core Ultra 7, 14 threads): 4 as fast as 8, 12 slower (the efficiency
            // cores); 4 leaves the rest of the machine free
            int threads = config.threads() == null ? Math.min(4, Runtime.getRuntime().availableProcessors()) : config.threads();
            // The OpenAI path, so OpenAiAudioClient talks to it as to OpenAI
            ProcessBuilder builder = new ProcessBuilder(List.of(executable("whisper", "whisper-server").toString(),
                    "-m", model.toString(), "--host", "127.0.0.1", "--port", String.valueOf(port),
                    "-l", language, "-t", String.valueOf(threads), "--inference-path", "/v1/audio/transcriptions"))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile("whisper.log").toFile());
            withLibraries(builder, home.resolve("whisper"));
            whisperProcess = builder.start();
            waitUntilServing(URI.create("http://127.0.0.1:" + port + "/"));
            OpenAiAudioClient client = new OpenAiAudioClient(http, URI.create("http://127.0.0.1:" + port + "/v1/"), settings);
            // The first transcription after loading takes about twice as long: pay it now, not on the user's first phrase
            client.transcribe(silence(), "audio/wav");
            log.info("Local speech-to-text ready: whisper.cpp {} on port {}, {} threads", modelFile, port, threads);
            whisper.complete(client);
        } catch (IOException | RuntimeException e) {
            log.warn("Local speech-to-text not available: {}", e.getMessage());
            whisper.completeExceptionally(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            whisper.completeExceptionally(e);
        }
    }

    private void waitUntilServing(URI uri) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !closed) {
            if (!whisperProcess.isAlive()) {
                throw new IOException("whisper-server ended with code " + whisperProcess.exitValue()
                        + " (see ~/.jarvis/logs/whisper.log)");
            }
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // still loading the model
            }
            Thread.sleep(300);
        }
        throw new IOException("whisper-server did not start in " + READY_TIMEOUT.toSeconds() + " s");
    }

    /** Waits for an engine still starting; a clear message if it cannot run (not installed, failed). */
    private static <T> T ready(CompletableFuture<T> engine, String what) {
        try {
            return engine.get(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new LocalVoiceException("La " + what + " sul tuo PC non è disponibile (" + e.getCause().getMessage()
                    + "). Scegline un'altra in Impostazioni → Voce.", e);
        } catch (TimeoutException e) {
            throw new LocalVoiceException("La " + what + " sul tuo PC non è ancora pronta: riprova tra poco.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalVoiceException("Interrotto.", e);
        }
    }

    /**
     * Linux and macOS: the libraries next to the program (built without an absolute rpath, prepare-voice.sh). Windows
     * finds the DLLs next to the executable by itself.
     */
    static ProcessBuilder withLibraries(ProcessBuilder builder, Path folder) {
        if (!WINDOWS) {
            String variable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                    ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
            builder.environment().merge(variable, folder.toString(), (old, added) -> added + File.pathSeparator + old);
        }
        return builder;
    }

    private Path executable(String folder, String name) throws IOException {
        Path file = home.resolve(folder).resolve(WINDOWS ? name + ".exe" : name);
        requireFile(file);
        return file;
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + " missing: prepare the local voice (desktop/prepare-voice.ps1) "
                    + "or set jarvis.voice.local.home");
        }
    }

    private static Path logFile(String name) throws IOException {
        Path logs = Path.of(System.getProperty("user.home"), ".jarvis", "logs");
        Files.createDirectories(logs);
        return logs.resolve(name);
    }

    /** One second of silence, 16 kHz mono 16-bit WAV. */
    static byte[] silence() {
        int samples = 16_000;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        return wav.array();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (whisperProcess != null) {
            whisperProcess.destroy();
        }
        if (piper != null) {
            piper.close();
        }
    }
}
