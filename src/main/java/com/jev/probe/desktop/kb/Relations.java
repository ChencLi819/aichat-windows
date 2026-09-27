package com.jev.probe.desktop.kb;

import com.jev.probe.desktop.config.Config;

import java.util.List;

/**
 * The relationship model from the requirements doc §5.6: a relationship belongs
 * to the PERSON you are talking to, not to the app.
 *
 * Before this, the relation lived in one global setting, so chatting with a
 * colleague was judged as if it were the partner — the tone and the advice were
 * both wrong (P-20). The stored value now lives on the contact, and the global
 * field is demoted to a fallback that only applies when the person could not be
 * recognised.
 *
 * The four-step fallback is deliberate and is surfaced in the UI, because "which
 * relationship is in force right now" changes what the model is told:
 *
 * <pre>
 *   1. a contact was recognised AND has a relationship   -> use it
 *   2. a contact was recognised but has none             -> fall back to default, mark the source
 *   3. no contact was recognised (stranger / unknown group) -> fall back to default, mark it
 *   4. the default is empty too                          -> the prompt's own 「未说明」
 * </pre>
 *
 * Sending an empty string at step 4 is what the judge prompt already handles, so
 * nothing downstream needed a new "absent" case.
 */
public final class Relations {

    /**
     * The built-in candidates offered by the relation pill and the contact card's
     * select. Free text is allowed on top of these (Q-6), because real life has
     * 「前同事」 and 「房东」 in it.
     */
    public static final List<String> CANDIDATES = List.of(
        "伴侣", "家人", "朋友", "同事", "上级", "下属", "客户", "同学", "陌生人");

    /** Where the value that will be injected into the prompt came from. */
    public enum Source {
        /** Stored on the recognised contact — the "own" relationship. */
        CONTACT,
        /** The contact was recognised but has no relationship; the default is used. */
        FALLBACK_CONTACT,
        /** No contact was recognised; the default is used. */
        FALLBACK_UNKNOWN
    }

    /**
     * The relationship to inject for this conversation.
     *
     * @param value what goes into the prompt; blank means the prompt's own
     *        「未说明」 wording applies.
     * @param source why this value, for the UI to label it honestly.
     * @param contactName the recognised contact's display name, or null.
     */
    public record Resolved(String value, Source source, String contactName) {

        /** True when this is the person's own relationship rather than the fallback. */
        public boolean isOwn() {
            return source == Source.CONTACT;
        }

        public boolean isBlank() {
            return value == null || value.isBlank();
        }
    }

    private Relations() {
    }

    public static Resolved resolve(String sessionTitle, Config config) {
        return resolve(sessionTitle, config, KbStore.get());
    }

    /**
     * Resolve against an explicit store — the self-check runs on a scratch one so
     * it can never read or write the user's real knowledge base.
     */
    public static Resolved resolve(String sessionTitle, Config config, KbStore store) {
        String fallback = config.relationship() == null ? "" : config.relationship().trim();
        Contact contact = sessionTitle == null || sessionTitle.isBlank()
            ? null
            : store.findContact(sessionTitle, "");
        if (contact == null) {
            return new Resolved(fallback, Source.FALLBACK_UNKNOWN, null);
        }
        String own = contact.relationship() == null ? "" : contact.relationship().trim();
        if (own.isEmpty()) {
            return new Resolved(fallback, Source.FALLBACK_CONTACT, contact.name());
        }
        return new Resolved(own, Source.CONTACT, contact.name());
    }

    /** True when [value] is one of the built-in candidates. */
    public static boolean isBuiltIn(String value) {
        return value != null && CANDIDATES.contains(value.trim());
    }
}
