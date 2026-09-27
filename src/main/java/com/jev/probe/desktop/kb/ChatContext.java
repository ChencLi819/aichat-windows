package com.jev.probe.desktop.kb;

import java.util.List;

/**
 * What one analysis gets to see beyond the on-screen messages: who the other
 * party is, older history for them, the knowledge notes that matched, and —
 * when the user injected one — this conversation's own context buffer.
 *
 * [session] is per chat window and independent of every other window (see
 * `ContextSessions`): it is the only field here the user switches on by hand.
 */
public final class ChatContext {

    private final Contact contact;
    private final List<LogEntry> history;
    private final List<Note> notes;
    private final String session;

    public ChatContext(Contact contact, List<LogEntry> history, List<Note> notes) {
        this(contact, history, notes, null);
    }

    public ChatContext(Contact contact, List<LogEntry> history, List<Note> notes, String session) {
        this.contact = contact;
        this.history = history;
        this.notes = notes;
        this.session = session;
    }

    public Contact contact() {
        return contact;
    }

    public List<LogEntry> history() {
        return history;
    }

    public List<Note> notes() {
        return notes;
    }

    public String session() {
        return session;
    }

    /** True when there is nothing extra to inject (then no field is sent at all). */
    public boolean isEmpty() {
        return history.isEmpty() && notes.isEmpty() && (session == null || session.isBlank()) &&
            (contact == null || (contact.relationship().isBlank() &&
                contact.notes().isBlank() && contact.autoSummary().isBlank()));
    }

    /**
     * The `background` string injected into the judgment and reply prompts:
     * contact notes + auto-summary + each matched note as "title: content", then
     * the session buffer. Blank when there is nothing to say — callers must then
     * omit the block entirely rather than send an empty one.
     *
     * It deliberately does NOT emit a `关系：` line any more. The relationship is
     * resolved once by the caller (see `kb/Relations`) and injected once by
     * `JudgePrompts.renderUserMessage`; emitting it here as well meant a contact
     * whose relationship was set had it printed twice, once from each side
     * (R-3 / P-21).
     */
    public String background() {
        StringBuilder sb = new StringBuilder();
        if (contact != null) {
            if (!contact.notes().isBlank()) {
                sb.append("关于").append(contact.name()).append("：")
                    .append(contact.notes().trim()).append('\n');
            }
            if (!contact.autoSummary().isBlank()) {
                sb.append("过往摘要：").append(contact.autoSummary().trim()).append('\n');
            }
        }
        for (Note n : notes) {
            sb.append(n.title().trim()).append(": ").append(n.content().trim()).append('\n');
        }
        if (session != null && !session.isBlank()) sb.append(session.trim()).append('\n');
        return sb.toString().trim();
    }
}
