package com.jev.probe.desktop.kb;

import java.util.List;

/** One free-text knowledge note the user maintains by hand. */
public final class Note {

    private final String id;
    private final String title;
    private final String content;
    private final List<String> tags;
    /** Always injected regardless of what the conversation is about. */
    private final boolean alwaysOn;
    private final boolean enabled;
    private final long updatedAt;

    public Note(String id, String title, String content) {
        this(id, title, content, List.of(), false, true, System.currentTimeMillis());
    }

    public Note(
        String id,
        String title,
        String content,
        List<String> tags,
        boolean alwaysOn,
        boolean enabled,
        long updatedAt
    ) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.tags = tags;
        this.alwaysOn = alwaysOn;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public List<String> tags() {
        return tags;
    }

    public boolean alwaysOn() {
        return alwaysOn;
    }

    public boolean enabled() {
        return enabled;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** The `copy(updatedAt = …)` shape used by [KbStore]. */
    public Note withUpdatedAt(long ts) {
        return new Note(id, title, content, tags, alwaysOn, enabled, ts);
    }
}
