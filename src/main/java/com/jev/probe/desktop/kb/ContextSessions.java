package com.jev.probe.desktop.kb;

import com.jev.probe.desktop.model.Msg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-conversation context buffer ("本会话上下文").
 *
 * One entry per conversation title — the same identity the rest of the app uses
 * for a conversation (whitelist matching, contact filing) — so every chat
 * window keeps its own context and clearing one never touches another. The user
 * starts recording when they open a chat with someone and clears it when they
 * are done with that chat; nothing is recorded unless they ask for it.
 *
 * Counting starts at the moment the user starts recording: whatever was already
 * on screen at that instant is deliberately NOT added, because it already goes
 * out with every analysis as the on-screen window. Only messages that arrive
 * from that point on accumulate here, which is what makes this buffer worth
 * having — it keeps the thread continuous after lines scroll off the screen.
 *
 * In memory only, on purpose: the buffer represents the currently open chat
 * session, so it dies with the service/process exactly like the chat window it
 * belongs to. Nothing is written to disk, and no conversation's buffer is ever
 * mixed into another's.
 *
 * All methods are synchronized: the capture service appends from its worker
 * pool while the overlay menu and settings read from the main thread.
 */
public final class ContextSessions {

    private ContextSessions() {
    }

    /** One open conversation, counting from [injectedAt] onward. */
    public static final class Session {
        private final String title;
        private final long injectedAt = System.currentTimeMillis();
        /** Recorded lines, oldest first. */
        private final List<Msg> entries = new ArrayList<>();
        /** Dedupe keys of every line already accounted for (recorded or dropped). */
        private final Set<String> seen = new HashSet<>();

        Session(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public long injectedAt() {
            return injectedAt;
        }

        public List<Msg> entries() {
            return entries;
        }

        public Set<String> seen() {
            return seen;
        }
    }

    /** One line per open conversation: title, recorded lines, start time. */
    public record SessionSummary(String title, int entries, long injectedAt) {
    }

    private static final Map<String, Session> sessions = new LinkedHashMap<>();

    /** A floor so a tiny configured budget cannot silently disable recording. */
    private static final int MIN_CHARS = 200;

    /**
     * Start (or restart) recording for one conversation.
     *
     * @param onScreen the messages visible at this instant. They are marked as
     *        already seen but not stored, so the count begins here rather than
     *        reaching back into what was on screen before the user chose to start.
     */
    public static synchronized Session inject(String title, List<Msg> onScreen) {
        Session s = new Session(title);
        for (Msg m : onScreen) {
            String key = dedupeKey(m);
            if (key != null) s.seen.add(key);
        }
        sessions.put(title, s);
        return s;
    }

    /** Drop one conversation's context. Returns true when there was one. */
    public static synchronized boolean clear(String title) {
        return sessions.remove(title) != null;
    }

    /** Drop every conversation's context. Returns how many were dropped. */
    public static synchronized int clearAll() {
        int n = sessions.size();
        sessions.clear();
        return n;
    }

    public static synchronized boolean isActive(String title) {
        return title != null && sessions.containsKey(title);
    }

    public static synchronized List<String> titles() {
        return new ArrayList<>(sessions.keySet());
    }

    /** One line per open conversation: title, recorded lines, start time. */
    public static synchronized List<SessionSummary> summary() {
        List<SessionSummary> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            out.add(new SessionSummary(s.title, s.entries.size(), s.injectedAt));
        }
        return out;
    }

    /**
     * Record newly captured messages into this conversation's buffer. No-op when
     * the conversation is not being recorded, so a chat the user never opted
     * into is never stored.
     *
     * Each capture re-reads the visible window, so a batch normally overlaps the
     * buffer's tail. The anchor rule: find the LAST line of the batch that is
     * already accounted for, and record only what comes strictly after it.
     * Genuinely new messages always arrive below the newest seen line; anything
     * at or above the anchor is the same window re-read — or OLDER lines the
     * user scrolled into view — and those must never enter "本次聊天"
     * (they were said before recording started; the reported "分析的是旧
     * 会话的上下文"). A batch that shares nothing with the buffer at all is
     * also history: mark it seen so it can never be recorded later.
     */
    public static synchronized void append(String title, List<Msg> messages, int maxChars) {
        Session s = title != null ? sessions.get(title) : null;
        if (s == null) return;
        if (s.seen.isEmpty()) {
            // First capture of a session that started with an empty window:
            // there is no anchor to find, so this batch is the baseline.
            for (Msg m : messages) {
                String key = dedupeKey(m);
                if (key == null) continue;
                if (s.seen.add(key)) s.entries.add(new Msg(m.side(), m.text().trim()));
            }
            trim(s, maxChars);
            return;
        }
        int anchor = -1;
        for (int i = 0; i < messages.size(); i++) {
            String key = dedupeKey(messages.get(i));
            if (key == null) continue;
            if (s.seen.contains(key)) anchor = i;
        }
        for (int i = anchor + 1; i < messages.size(); i++) {
            Msg m = messages.get(i);
            String key = dedupeKey(m);
            if (key == null) continue;
            if (s.seen.add(key)) s.entries.add(new Msg(m.side(), m.text().trim()));
        }
        for (int i = 0; i <= anchor; i++) {
            String k = dedupeKey(messages.get(i));
            if (k != null) s.seen.add(k);
        }
        if (anchor < 0) {
            for (Msg m : messages) {
                String k = dedupeKey(m);
                if (k != null) s.seen.add(k);
            }
        }
        trim(s, maxChars);
    }

    /**
     * The text to inject into one analysis: everything recorded since the user
     * started, oldest first, trimmed to stay within [maxChars]. Null while
     * nothing has been recorded yet.
     */
    public static synchronized String render(String title, int maxChars) {
        Session s = title != null ? sessions.get(title) : null;
        if (s == null) return null;
        trim(s, maxChars);
        if (s.entries.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("以下是我和对方本次聊天的记录（从我开始记录那一刻起，越靠下越新）：\n");
        for (Msg it : s.entries) {
            sb.append("me".equals(it.side()) ? "我：" : "对方：").append(it.text()).append('\n');
        }
        return sb.toString().trim();
    }

    /** Drop the oldest recorded lines until the buffer fits [maxChars]. */
    private static void trim(Session s, int maxChars) {
        int cap = Math.max(maxChars, MIN_CHARS);
        int used = 0;
        for (Msg m : s.entries) used += m.text().length() + 3;
        while (used > cap && !s.entries.isEmpty()) {
            Msg dropped = s.entries.remove(0);
            used -= dropped.text().length() + 3;
        }
    }

    /** Identity of one line for dedupe; null for blank text. */
    private static String dedupeKey(Msg m) {
        String text = m.text().trim();
        return text.isEmpty() ? null : m.side() + "\u0000" + text;
    }
}
