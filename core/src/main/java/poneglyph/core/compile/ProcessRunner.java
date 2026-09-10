package poneglyph.core.compile;

import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external process with a hard timeout, cooperative cancellation and bounded output capture.
 * Output beyond {@code maxOutputChars} per stream is drained and discarded so a runaway test binary
 * cannot exhaust memory.
 */
public final class ProcessRunner {

    private static final Map<Integer, String> SIGNAL_NAMES = Map.of(
            4, "SIGILL", 5, "SIGTRAP", 6, "SIGABRT", 7, "SIGBUS", 8, "SIGFPE",
            9, "SIGKILL", 10, "SIGBUS", 11, "SIGSEGV", 13, "SIGPIPE", 15, "SIGTERM");

    /** Captured result of one process run. */
    public record Output(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs) {

        /** The JVM reports signal deaths on Unix as 128 + signal number. */
        public boolean killedBySignal() {
            return !timedOut && exitCode > 128 && exitCode < 128 + 64;
        }

        public int signal() {
            return killedBySignal() ? exitCode - 128 : 0;
        }

        public String signalName() {
            int sig = signal();
            return SIGNAL_NAMES.getOrDefault(sig, "signal " + sig);
        }

        public String combinedOutput() {
            if (stderr.isBlank()) {
                return stdout;
            }
            if (stdout.isBlank()) {
                return stderr;
            }
            return stdout + "\n--- stderr ---\n" + stderr;
        }
    }

    private ProcessRunner() {
    }

    /**
     * @throws IOException        if the executable cannot be started (typically: not found)
     * @throws CancelledException if {@code cancel} fires while the process is running
     */
    public static Output run(List<String> command, Path workDir, Duration timeout, CancelToken cancel, int maxOutputChars)
            throws IOException {
        CancelToken token = cancel == null ? CancelToken.NONE : cancel;
        token.throwIfCancelled();

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectErrorStream(false);
        // Make compiler diagnostics stable and free of ANSI colour codes.
        pb.environment().put("LC_ALL", "C");
        pb.environment().put("LANG", "C");
        pb.environment().remove("CLICOLOR_FORCE");
        pb.environment().put("NO_COLOR", "1");

        long start = System.nanoTime();
        Process process = pb.start();
        process.getOutputStream().close();
        StreamCollector out = new StreamCollector(process.getInputStream(), maxOutputChars);
        StreamCollector err = new StreamCollector(process.getErrorStream(), maxOutputChars);
        out.start();
        err.start();

        long deadline = start + timeout.toNanos();
        boolean timedOut = false;
        try {
            while (true) {
                if (token.isCancelled()) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new CancelledException();
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    break;
                }
            }
            out.join(2000);
            err.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        int exit = process.isAlive() ? -1 : process.exitValue();
        return new Output(exit, out.text(), err.text(), timedOut, durationMs);
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final int max;
        private final StringBuilder sb = new StringBuilder();
        private boolean truncated;

        StreamCollector(InputStream in, int max) {
            this.in = in;
            this.max = max;
            setDaemon(true);
            setName("poneglyph-stream");
        }

        @Override
        public void run() {
            char[] buf = new char[4096];
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(buf)) >= 0) {
                    synchronized (sb) {
                        if (sb.length() < max) {
                            sb.append(buf, 0, Math.min(n, max - sb.length()));
                        } else {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed by process death; whatever we have is enough
            }
        }

        String text() {
            synchronized (sb) {
                return truncated ? sb + "\n... [output truncated]" : sb.toString();
            }
        }
    }
}
