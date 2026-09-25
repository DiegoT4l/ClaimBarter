package io.github.diegot4l.claimbarter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Captures every LogRecord a Logger produces - level, formatted message,
 * thrown, and the thrown's suppressed array - so every expect_log_contains
 * and forbidden-substring assertion runs against the real formatted text
 * rather than a guess at it.
 */
final class RecordingHandler extends Handler
{
    static final class Entry
    {
        final Level level;
        final String message;
        final Throwable thrown;
        final Throwable[] suppressed;

        Entry(Level level, String message, Throwable thrown)
        {
            this.level = level;
            this.message = message == null ? "" : message;
            this.thrown = thrown;
            this.suppressed = thrown == null ? new Throwable[0] : thrown.getSuppressed();
        }
    }

    final List<Entry> entries = new ArrayList<>();

    @Override
    public void publish(LogRecord record)
    {
        entries.add(new Entry(record.getLevel(), record.getMessage(), record.getThrown()));
    }

    @Override
    public void flush()
    {
    }

    @Override
    public void close()
    {
    }
}
