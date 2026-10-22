package dev.jarvis.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Text-to-speech with Piper, kept running: loading the voice takes seconds, a sentence then about 0.2 s.
 *
 * <p>With {@code --json-input} Piper reads one JSON object per line, writes the WAV file it names and prints its
 * path when done. One sentence at a time: turns already speak their sentences in order. A Piper that ended is
 * started again at the next sentence.
 */
final class PiperTts implements TextToSpeech, AutoCloseable {

    private final Path executable;
    private final Path voice;
    private final Path log;
    private final Path output;
    private Process process;
    private Writer input;
    private BufferedReader done;
    private long next;

    PiperTts(Path executable, Path voice, Path log) {
        this.executable = executable;
        this.voice = voice;
        this.log = log;
        try {
            this.output = Files.createTempDirectory("jarvis-piper-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the voice now, so the first answer does not wait for it. */
    void warmUp() {
        synthesize("Pronto.");
    }

    @Override
    public synchronized SpeechAudio synthesize(String text) {
        try {
            if (process == null || !process.isAlive()) {
                start();
            }
            Path file = output.resolve("sentence-" + next++ + ".wav");
            input.write("{\"text\":" + Json.quote(text.replace('\n', ' ')) + ",\"output_file\":" + Json.quote(file.toString()) + "}\n");
            input.flush();
            String written = done.readLine();
            if (written == null) {
                throw new IOException("Piper ended (see ~/.jarvis/logs/piper.log)");
            }
            byte[] audio = Files.readAllBytes(file);
            Files.deleteIfExists(file);
            return new SpeechAudio(audio, "audio/wav");
        } catch (IOException e) {
            close();
            throw new UncheckedIOException("Piper non ha prodotto l'audio: " + e.getMessage(), e);
        }
    }

    private void start() throws IOException {
        if (!Files.isRegularFile(voice)) {
            throw new IOException(voice + " missing: prepare the local voice (desktop/prepare-voice.ps1)");
        }
        process = new ProcessBuilder(List.of(executable.toString(), "--model", voice.toString(), "--json-input"))
                .redirectError(log.toFile())
                .start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        done = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
