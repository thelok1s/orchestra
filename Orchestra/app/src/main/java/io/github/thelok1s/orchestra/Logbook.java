package io.github.thelok1s.orchestra;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-process ring buffer of recent events, shown on the in-app Debug screen (the app can't read
 * logcat without READ_LOGS). Key RFCOMM / provider events call {@link #add}; the UI reads
 * {@link #lines}. Lives in the app process where the services run.
 */
public final class Logbook {
    private static final int MAX = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Logbook() {}

    public static synchronized void add(String msg) {
        LINES.addLast(TS.format(new Date()) + "  " + msg);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    /** Most-recent-last. */
    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
