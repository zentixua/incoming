package ua.zentix.stand;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Bench output: {@code events.jsonl} events (commands, windows, shots, hits, server ticks, probes; written
 * by client/server threads), {@code frames.csv} row per frame ({@link FrameClock}), {@code shots/} PNGs.
 * Times are milliseconds since {@link Stand#T0}.
 */
final class Out {
    final Path dir, shots;
    private final Writer events, frames;
    /** Nothing to write after run ends (world exit, entity unloading). */
    private volatile boolean closed;

    Out(Path dir) throws IOException {
        this.dir = dir;
        this.shots = dir.resolve("shots");
        Files.createDirectories(shots);
        events = Files.newBufferedWriter(dir.resolve("events.jsonl"), StandardCharsets.UTF_8);
        frames = new BufferedWriter(Files.newBufferedWriter(dir.resolve("frames.csv"), StandardCharsets.UTF_8), 1 << 16);
        frames.write("frame,t_ms,prev_interval_ms,cpu_ms,cpu_thread_ms,gpu_ms,prev_gpu_period_ms,shot,stages\n");
    }

    /** Event: type, time, key/value pairs (quote strings, numbers and true/false unchanged). */
    void event(String type, long nanos, Object... kv) {
        StringBuilder b = new StringBuilder(96).append("{\"type\":\"").append(type).append("\",\"t_ms\":")
                .append(String.format(Locale.ROOT, "%.3f", Stand.ms(nanos)));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.append(",\"").append(kv[i]).append("\":");
            value(b, kv[i + 1]);
        }
        b.append("}\n");
        synchronized (events) {
            if (closed) return;
            try {
                events.write(b.toString());
            } catch (IOException e) {
                Stand.LOG.warn("STAND: event not written", e);
            }
        }
    }

    private static void value(StringBuilder b, Object v) {
        switch (v) {
            case null -> b.append("null");
            case Double d -> b.append(Double.isFinite(d) ? String.format(Locale.ROOT, "%.4f", d) : "null");
            case Float f -> b.append(Float.isFinite(f) ? String.format(Locale.ROOT, "%.4f", f) : "null");
            case Number n -> b.append(n);
            case Boolean x -> b.append(x);
            case double[] a -> {
                b.append('[');
                for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.3f", a[i]));
                b.append(']');
            }
            default -> quote(b, v.toString());
        }
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** Frame row (render thread). */
    void frame(String line) {
        if (closed) return;
        try {
            frames.write(line);
        } catch (IOException e) {
            Stand.LOG.warn("STAND: frame not written", e);
        }
    }

    /** Flush accumulated data every second and at end (a crashed client loses at most the last second). */
    void flush() {
        if (closed) return;
        try {
            synchronized (events) {
                events.flush();
            }
            frames.flush();
        } catch (IOException e) {
            Stand.LOG.warn("STAND: flush failed", e);
        }
    }

    void close() {
        flush();
        try {
            synchronized (events) {
                closed = true;
                events.close();
            }
            frames.close();
        } catch (IOException e) {
            Stand.LOG.warn("STAND: close failed", e);
        }
    }
}
