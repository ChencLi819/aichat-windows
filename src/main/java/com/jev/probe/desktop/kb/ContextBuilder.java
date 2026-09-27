package com.jev.probe.desktop.kb;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Turns "what is on screen right now" into the extra context one analysis gets:
 * which contact this is, their older history, and which knowledge notes apply.
 *
 * Deliberately dumb and cheap (v1.3 revision): exact name/alias matching, plain
 * substring note matching, a hard character budget. No scoring, no embeddings,
 * no auto-created contacts, no network.
 */
public final class ContextBuilder {

    private ContextBuilder() {
    }

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    /** Notes + history together may not exceed this many characters. */
    public static final int BUDGET_CHARS = 1500;

    /** At most this many matched (non-always-on) notes. */
    public static final int MAX_HIT_NOTES = 5;

    /** Only the last few messages are searched for note keywords. */
    private static final int MATCH_WINDOW = 6;

    /** Identical on-screen copies this short are not worth de-duplicating. */
    private static final int DEDUPE_MIN_LEN = 4;

    /**
     * @param app package name of the foreground chat app (may be blank).
     * @return context for this snapshot; every field may be empty, which is the
     *         normal state before the user has built a knowledge base.
     */
    public static ChatContext build(ChatSnapshot snapshot, String app, Config prefs) {
        return build(snapshot, app, prefs, KbStore.get());
    }

    /**
     * The same, against a named store. The settings screen's self-check runs this
     * over a scratch directory, so a smoke test can never read or write the
     * user's own knowledge base.
     */
    public static ChatContext build(ChatSnapshot snapshot, String app, Config prefs, KbStore store) {
        String title = snapshot.title() != null ? snapshot.title() : "";

        // 1. Contact — matched only, never created here.
        Contact contact = store.findContact(title, app);

        // 2. History — recorded and injected only with the user's opt-in.
        List<LogEntry> history = (prefs.contextEnabled() && contact != null)
            ? historyFor(store, contact, snapshot, app, prefs) : List.of();

        // 3. Notes — always-on ones plus keyword hits.
        List<Note> enabled = new ArrayList<>();
        for (Note n : store.notes()) {
            if (n.enabled()) enabled.add(n);
        }
        List<Note> alwaysOn = new ArrayList<>();
        List<Note> nonAlwaysOn = new ArrayList<>();
        for (Note n : enabled) {
            if (n.alwaysOn()) alwaysOn.add(n);
            else nonAlwaysOn.add(n);
        }
        List<Note> hits = matchNotes(nonAlwaysOn, snapshot);

        // 4. Budget: always-on notes are exempt; the rest share BUDGET_CHARS,
        //    dropping oldest history first, then whole notes (never half a note).
        List<LogEntry> trimmedHistory = new ArrayList<>(history);
        List<Note> trimmedHits = new ArrayList<>(hits);
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && !trimmedHistory.isEmpty()) {
            trimmedHistory.remove(0);
        }
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && !trimmedHits.isEmpty()) {
            trimmedHits.remove(trimmedHits.size() - 1);
        }

        LOGGER.fine("context: contact=" + (contact != null) + " notes=" + (alwaysOn.size() + trimmedHits.size()) +
            " history=" + trimmedHistory.size());
        List<Note> notes = new ArrayList<>(alwaysOn);
        notes.addAll(trimmedHits);
        return new ChatContext(contact, trimmedHistory, notes);
    }

    /**
     * Record the visible messages, then read back the recent window minus the
     * copies already on screen (only for lines long enough that an exact repeat
     * is certainly the same message).
     */
    private static List<LogEntry> historyFor(
        KbStore store,
        Contact contact,
        ChatSnapshot snapshot,
        String app,
        Config prefs
    ) {
        long now = System.currentTimeMillis();
        List<LogEntry> toAppend = new ArrayList<>();
        for (Msg m : snapshot.messages()) {
            toAppend.add(new LogEntry(m.side(), m.text(), now, app));
        }
        store.appendLog(contact.id(), toAppend);
        int n = clamp(prefs.contextHistoryCount(), 0, 100);
        if (n == 0) return List.of();
        Set<String> onScreen = new HashSet<>();
        for (Msg m : snapshot.messages()) {
            if (m.text().length() >= DEDUPE_MIN_LEN) onScreen.add(m.side() + " " + m.text());
        }
        // Filter the widest window FIRST, then take n. The other order lets the
        // messages currently on screen eat into the quota — ask for 30 lines of
        // history, get 30 minus however many are already visible.
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry e : store.recentLog(contact.id(), KbStore.MAX_LOG)) {
            if (!onScreen.contains(e.side() + " " + e.text())) filtered.add(e);
        }
        return ChatSnapshot.takeLast(filtered, n);
    }

    /**
     * A note matches when any of its tags, or its title, appears in the
     * conversation title or in the last few messages. Newest notes win the
     * [MAX_HIT_NOTES] cap.
     */
    private static List<Note> matchNotes(List<Note> candidates, ChatSnapshot snapshot) {
        if (candidates.isEmpty()) return List.of();
        StringBuilder hay = new StringBuilder();
        hay.append(KbStore.normalizeText(snapshot.title()));
        for (Msg m : ChatSnapshot.takeLast(snapshot.messages(), MATCH_WINDOW)) {
            hay.append('\n').append(KbStore.normalizeText(m.text()));
        }
        String haystack = hay.toString();
        if (haystack.isBlank()) return List.of();
        List<Note> hits = new ArrayList<>();
        for (Note note : candidates) {
            List<String> raws = new ArrayList<>(note.tags());
            raws.add(note.title());
            for (String raw : raws) {
                String needle = KbStore.normalizeText(raw);
                if (!needle.isEmpty() && haystack.contains(needle)) {
                    hits.add(note);
                    break;
                }
            }
        }
        hits.sort(Comparator.comparingLong(Note::updatedAt).reversed());
        return hits.size() > MAX_HIT_NOTES ? new ArrayList<>(hits.subList(0, MAX_HIT_NOTES)) : hits;
    }

    private static int cost(List<Note> notes, List<LogEntry> history) {
        int sum = 0;
        for (Note n : notes) sum += n.title().length() + n.content().length() + 2;
        for (LogEntry e : history) sum += e.text().length() + 3;
        return sum;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
