package ua.zentix.incoming.stress;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.Queue;
import java.util.function.Consumer;

/**
 * All WARN/ERROR in the log (except for stand lines) are included in counters; with an exception or from a mod - to the list of problems. Mod lines
 *  of any level (except stand lines) are also in {@code modLines}: from them the director sees the events of projectiles that are not
 *  visible from the outside (the path outside the world reached the surface, did not wait for the area to load).
 */
final class LogWatch {
    private LogWatch() {}

    static void install(Queue<String> problems, Runnable onWarn, Runnable onError) {
        install(problems, onWarn, onError, line -> {});
    }

    static void install(Queue<String> problems, Runnable onWarn, Runnable onError, Consumer<String> modLines) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        AbstractAppender app = new AbstractAppender("incoming-stress", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                String msg = event.getMessage().getFormattedMessage();
                if (msg.startsWith("STRESS")) return;
                if (event.getLoggerName().contains("incoming")) modLines.accept(msg);
                if (!event.getLevel().isMoreSpecificThan(Level.WARN)) return;
                boolean error = event.getLevel().isMoreSpecificThan(Level.ERROR);
                if (error) onError.run();
                else onWarn.run();
                Throwable t = event.getThrown();
                if ((t != null || error || event.getLoggerName().contains("incoming")) && problems.size() < 300) {
                    StringBuilder sb = new StringBuilder(event.getLevel() + " [" + event.getLoggerName() + "] " + msg);
                    for (Throwable c = t; c != null; c = c.getCause()) {
                        sb.append(" :: ").append(c);
                        StackTraceElement[] st = c.getStackTrace();
                        for (int i = 0; i < Math.min(6, st.length); i++) sb.append(" @ ").append(st[i]);
                    }
                    problems.add(sb.toString());
                }
            }
        };
        app.start();
        ctx.getConfiguration().addAppender(app);
        ctx.getRootLogger().addAppender(app);
        ctx.updateLoggers();
    }
}
