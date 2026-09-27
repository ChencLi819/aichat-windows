package com.jev.probe.desktop.kb;

import java.util.List;

/**
 * One person (or group) the user chats with. [aliases] is what makes a contact
 * cross-app: the same person shows up as different conversation titles in
 * WeChat / QQ / Feishu, and each of those titles can be listed here.
 */
public final class Contact {

    private final String id;
    private final String name;
    private final List<String> aliases;
    /** Package names this contact has been seen in, e.g. com.tencent.mm. */
    private final List<String> apps;
    private final String relationship;
    private final String notes;
    /** Reserved for the (deferred) auto-summary; never written in v1.3. */
    private final String autoSummary;
    private final long updatedAt;

    public Contact(String id, String name, List<String> aliases, List<String> apps) {
        this(id, name, aliases, apps, "", "", "", System.currentTimeMillis());
    }

    public Contact(
        String id,
        String name,
        List<String> aliases,
        List<String> apps,
        String relationship,
        String notes,
        String autoSummary,
        long updatedAt
    ) {
        this.id = id;
        this.name = name;
        this.aliases = aliases;
        this.apps = apps;
        this.relationship = relationship;
        this.notes = notes;
        this.autoSummary = autoSummary;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> aliases() {
        return aliases;
    }

    public List<String> apps() {
        return apps;
    }

    public String relationship() {
        return relationship;
    }

    public String notes() {
        return notes;
    }

    public String autoSummary() {
        return autoSummary;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** The `copy(updatedAt = …)` shape used by [KbStore]. */
    public Contact withUpdatedAt(long ts) {
        return new Contact(id, name, aliases, apps, relationship, notes, autoSummary, ts);
    }

    /** The `copy(apps = …, aliases = …)` shape used by [KbStore]. */
    public Contact withAppsAndAliases(List<String> newApps, List<String> newAliases) {
        return new Contact(id, name, newAliases, newApps, relationship, notes, autoSummary, updatedAt);
    }

    /**
     * The relationship belongs to the person, so changing it is a per-contact
     * edit — that is the whole point of the v1.1 relation model. `updatedAt` is
     * bumped here (rather than left to [KbStore.saveContact]) so a caller that
     * only changes the relationship still records that the contact changed.
     */
    public Contact withRelationship(String rel) {
        return new Contact(id, name, aliases, apps, rel == null ? "" : rel.trim(),
            notes, autoSummary, System.currentTimeMillis());
    }

    /** The `copy(notes = …)` shape used by the editor. */
    public Contact withNotes(String value) {
        return new Contact(id, name, aliases, apps, relationship,
            value == null ? "" : value, autoSummary, System.currentTimeMillis());
    }
}
