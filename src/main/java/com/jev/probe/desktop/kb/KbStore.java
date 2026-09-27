package com.jev.probe.desktop.kb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The knowledge-base store: three kinds of JSON file under the base directory.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/&lt;contactId&gt;.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches the log — only counts and lengths.
 *
 * Desktop port: the Android `Context`+`filesDir` is replaced by a
 * `java.nio.file.Path` base directory. [get] with no argument uses
 * `&lt;user.home&gt;/.aichat-desktop/kb`.
 */
public final class KbStore {

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    public static final int MAX_LOG = 300;

    /** `&lt;user.home&gt;/.aichat-desktop/kb` — where the JSON lives on the desktop. */
    private static final Path DEFAULT_ROOT =
        Path.of(System.getProperty("user.home"), ".aichat-desktop", "kb");

    private static final Map<Path, KbStore> INSTANCES = new HashMap<>();

    private final Path root;
    private final Object lock = new Object();

    private List<Note> notesCache;
    private List<Contact> contactsCache;
    private final Map<String, List<LogEntry>> logCache = new HashMap<>();

    /** Per contact: the comparison keys of the last screen written. See [appendLog]. */
    private final Map<String, List<String>> lastScreenCache = new HashMap<>();

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private final Set<String> unreadable = new HashSet<>();

    private KbStore(Path root) {
        this.root = root;
    }

    /** The shared instance for the default desktop base directory. */
    public static KbStore get() {
        return get(DEFAULT_ROOT);
    }

    /** The shared instance for a given base directory. */
    public static KbStore get(Path baseDir) {
        synchronized (KbStore.class) {
            KbStore s = INSTANCES.get(baseDir);
            if (s == null) {
                s = new KbStore(baseDir);
                INSTANCES.put(baseDir, s);
            }
            return s;
        }
    }

    private Path notesFile() {
        return root.resolve("notes.json");
    }

    private Path contactsFile() {
        return root.resolve("contacts.json");
    }

    private Path logFile(String contactId) {
        return root.resolve("logs").resolve(contactId + ".json");
    }

    private Path screenFile(String contactId) {
        return root.resolve("logs").resolve(contactId + ".screen.json");
    }

    // ------------------------------------------------------------------ notes

    public List<Note> notes() {
        synchronized (lock) {
            return List.copyOf(loadNotes());
        }
    }

    public Note note(String id) {
        synchronized (lock) {
            for (Note n : loadNotes()) {
                if (n.id().equals(id)) return n;
            }
            return null;
        }
    }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    public boolean saveNote(Note note) {
        synchronized (lock) {
            List<Note> list = loadNotes();
            int i = indexOfNote(list, note.id());
            Note stamped = note.withUpdatedAt(System.currentTimeMillis());
            if (i >= 0) list.set(i, stamped);
            else list.add(stamped);
            boolean ok = writeAtomic(notesFile(), notesJson(list));
            if (!ok) notesCache = null;   // memory must not claim a write that failed
            return ok;
        }
    }

    public boolean deleteNote(String id) {
        synchronized (lock) {
            List<Note> list = loadNotes();
            if (!list.removeIf(n -> n.id().equals(id))) return true;
            boolean ok = writeAtomic(notesFile(), notesJson(list));
            if (!ok) notesCache = null;
            return ok;
        }
    }

    // --------------------------------------------------------------- contacts

    public List<Contact> contacts() {
        synchronized (lock) {
            return List.copyOf(loadContacts());
        }
    }

    public Contact contact(String id) {
        synchronized (lock) {
            for (Contact c : loadContacts()) {
                if (c.id().equals(id)) return c;
            }
            return null;
        }
    }

    public boolean saveContact(Contact c) {
        synchronized (lock) {
            List<Contact> list = loadContacts();
            int i = indexOfContact(list, c.id());
            Contact stamped = c.withUpdatedAt(System.currentTimeMillis());
            if (i >= 0) list.set(i, stamped);
            else list.add(stamped);
            boolean ok = writeAtomic(contactsFile(), contactsJson(list));
            if (!ok) contactsCache = null;
            return ok;
        }
    }

    /** Removes the contact and its history file. */
    public boolean deleteContact(String id) {
        synchronized (lock) {
            List<Contact> list = loadContacts();
            boolean ok = true;
            if (list.removeIf(c -> c.id().equals(id))) {
                ok = writeAtomic(contactsFile(), contactsJson(list));
                if (!ok) contactsCache = null;
            }
            logCache.remove(id);
            lastScreenCache.remove(id);
            try {
                Files.deleteIfExists(logFile(id));
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(screenFile(id));
            } catch (Exception ignored) {
            }
            return ok;
        }
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    public Contact findContact(String title, String app) {
        synchronized (lock) {
            String want = normalizeName(title);
            if (want.isEmpty()) return null;
            List<Contact> hits = new ArrayList<>();
            for (Contact c : loadContacts()) {
                boolean match = normalizeName(c.name()).equals(want);
                if (!match) {
                    for (String alias : c.aliases()) {
                        if (normalizeName(alias).equals(want)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (match) hits.add(c);
            }
            if (hits.isEmpty()) return null;
            if (app != null && !app.isBlank()) {
                for (Contact c : hits) {
                    if (c.apps().contains(app)) return c;
                }
            }
            return hits.get(0);
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     */
    public String saveOrMergeContact(String title, String app) {
        String display = displayName(title);
        if (display.isEmpty()) return "当前会话没有标题，存不了";
        Contact existing = findContact(title, app);
        if (existing == null) {
            List<String> aliases = !displayName(title).equals(title.trim())
                ? List.of(title.trim()) : List.of();
            saveContact(new Contact(
                newId(),
                display,
                aliases,
                app.isBlank() ? List.of() : List.of(app)
            ));
            return "已存为联系人「" + display + "」";
        }
        List<String> apps = (app.isBlank() || existing.apps().contains(app))
            ? existing.apps() : append(existing.apps(), app);
        String raw = title.trim();
        List<String> known = new ArrayList<>();
        known.add(normalizeName(existing.name()));
        for (String a : existing.aliases()) known.add(normalizeName(a));
        List<String> aliases = (!raw.isEmpty() && !known.contains(normalizeName(raw)))
            ? append(existing.aliases(), raw) : existing.aliases();
        if (apps.equals(existing.apps()) && aliases.equals(existing.aliases())) {
            return "联系人「" + existing.name() + "」已存在";
        }
        saveContact(existing.withAppsAndAliases(apps, aliases));
        return "已并入联系人「" + existing.name() + "」";
    }

    // ---------------------------------------------------------------- history

    /**
     * Append one screenful of messages, keeping only the newest [MAX_LOG].
     *
     * The unit is a SEQUENCE, not a set of lines. A capture gives us the whole
     * visible screen S, top to bottom; P is whatever screen we last wrote for
     * this contact. Two identical short lines on one screen are two positions and
     * get two entries — they are not folded together, and nothing is dropped for
     * being "too short to dedupe on" or for having been said before.
     *
     * The rules, in order:
     *  - S equals P            → the same screen again, write nothing.
     *  - log is empty          → write all of S.
     *  - log tail matches the first k lines of S (k > 0) → the screen scrolled by
     *    (S.size - k) lines; append only that new tail.
     *  - k is 0 and S shares nothing with P → the user scrolled up into old
     *    messages we already hold; this round writes nothing rather than
     *    duplicating history at the end of the file.
     *  - anything else         → append all of S.
     *
     * @param screenBatch true for a capture (the rules above). False for a
     *        deliberate single-entry injection that is NOT a screen read, which
     *        is appended as-is.
     */
    public boolean appendLog(String contactId, List<LogEntry> entries) {
        return appendLog(contactId, entries, true);
    }

    public boolean appendLog(String contactId, List<LogEntry> entries, boolean screenBatch) {
        if (entries.isEmpty()) return true;
        synchronized (lock) {
            List<LogEntry> screen = new ArrayList<>();
            for (LogEntry e : entries) {
                if (!e.text().isBlank()) screen.add(e);
            }
            if (screen.isEmpty()) return true;
            List<LogEntry> list = loadLog(contactId);
            List<String> keys = new ArrayList<>();
            for (LogEntry e : screen) keys.add(key(e.side(), e.text()));
            List<String> prev = screenBatch ? loadLastScreen(contactId) : List.of();

            // Same screen as last time: nothing happened worth recording.
            if (screenBatch && !prev.isEmpty() && prev.equals(keys)) return true;

            // How much of S the log already ends with.
            int k = 0;
            int maxK = Math.min(list.size(), keys.size());
            for (int cand = maxK; cand >= 1; cand--) {
                boolean match = true;
                for (int i = 0; i < cand; i++) {
                    LogEntry e = list.get(list.size() - cand + i);
                    if (!key(e.side(), e.text()).equals(keys.get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    k = cand;
                    break;
                }
            }

            List<LogEntry> tail;
            if (!screenBatch) {
                tail = screen;
            } else if (list.isEmpty()) {
                tail = screen;
            } else if (k > 0) {
                tail = new ArrayList<>(screen.subList(k, screen.size()));
            } else if (!prev.isEmpty() && disjoint(keys, prev)) {
                // Nothing in common with the screen we last wrote → we are looking
                // at older messages, not newer ones. Leave the log alone.
                LOGGER.fine("appendLog contact=" + contactId + " skipped: scrolled off the last screen");
                return true;
            } else {
                tail = screen;
            }
            if (tail.isEmpty()) {
                if (screenBatch) saveLastScreen(contactId, keys);
                return true;
            }

            list.addAll(tail);
            while (list.size() > MAX_LOG) list.remove(0);
            boolean ok = writeAtomic(logFile(contactId), logJson(list));
            if (!ok) logCache.remove(contactId);
            if (ok && screenBatch) saveLastScreen(contactId, keys);
            LOGGER.fine("appendLog contact=" + contactId + " added=" + tail.size() +
                " overlap=" + k + " total=" + list.size() + " ok=" + ok);
            return ok;
        }
    }

    /** The newest [n] entries, oldest first. */
    public List<LogEntry> recentLog(String contactId, int n) {
        if (n <= 0) return List.of();
        synchronized (lock) {
            List<LogEntry> list = loadLog(contactId);
            if (list.size() <= n) return List.copyOf(list);
            return List.copyOf(list.subList(list.size() - n, list.size()));
        }
    }

    public int logSize(String contactId) {
        synchronized (lock) {
            return loadLog(contactId).size();
        }
    }

    public void clearLog(String contactId) {
        synchronized (lock) {
            logCache.remove(contactId);
            lastScreenCache.remove(contactId);
            try {
                Files.deleteIfExists(logFile(contactId));
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(screenFile(contactId));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * The (side, text) sequence of the last screen written for this contact, as
     * comparison keys. Kept on disk as well as in memory so that re-opening a
     * chat that has not moved since does not append the same screen again.
     */
    private List<String> loadLastScreen(String contactId) {
        List<String> cached = lastScreenCache.get(contactId);
        if (cached != null) return cached;
        List<String> out = new ArrayList<>();
        Loaded loaded = readJsonArray(screenFile(contactId));
        if (loaded.arr != null) {
            for (int i = 0; i < loaded.arr.length(); i++) {
                String s = loaded.arr.optString(i);
                if (!s.isEmpty()) out.add(s);
            }
        }
        if (loaded.trustworthy) lastScreenCache.put(contactId, out);
        return out;
    }

    private void saveLastScreen(String contactId, List<String> keys) {
        lastScreenCache.put(contactId, keys);
        JSONArray arr = new JSONArray();
        for (String k : keys) arr.put(k);
        if (!writeAtomic(screenFile(contactId), arr.toString())) lastScreenCache.remove(contactId);
    }

    // ------------------------------------------------------------------ admin

    public KbCounts counts() {
        synchronized (lock) {
            List<Contact> contacts = loadContacts();
            int lines = 0;
            for (Contact c : contacts) lines += loadLog(c.id()).size();
            return new KbCounts(loadNotes().size(), contacts.size(), lines);
        }
    }

    /**
     * Wipe every knowledge-base file. Deletes only the `kb` base directory —
     * API keys, whitelist and every other config value are untouched.
     */
    public void clearAll() {
        synchronized (lock) {
            notesCache = null;
            contactsCache = null;
            logCache.clear();
            lastScreenCache.clear();
            deleteRecursively(root);
            LOGGER.info("kb cleared");
        }
    }

    // ------------------------------------------------------------------ io

    private List<Note> loadNotes() {
        if (notesCache != null) return notesCache;
        List<Note> list = new ArrayList<>();
        Loaded loaded = readJsonArray(notesFile());
        if (loaded.arr != null) {
            for (int i = 0; i < loaded.arr.length(); i++) {
                JSONObject o = loaded.arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                list.add(new Note(
                    id.isBlank() ? newId() : id,
                    o.optString("title"),
                    o.optString("content"),
                    strList(o.optJSONArray("tags")),
                    o.optBoolean("alwaysOn", false),
                    o.optBoolean("enabled", true),
                    o.optLong("updatedAt", 0L)
                ));
            }
        }
        if (loaded.trustworthy) notesCache = list;
        return list;
    }

    private List<Contact> loadContacts() {
        if (contactsCache != null) return contactsCache;
        List<Contact> list = new ArrayList<>();
        Loaded loaded = readJsonArray(contactsFile());
        if (loaded.arr != null) {
            for (int i = 0; i < loaded.arr.length(); i++) {
                JSONObject o = loaded.arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                list.add(new Contact(
                    id.isBlank() ? newId() : id,
                    o.optString("name"),
                    strList(o.optJSONArray("aliases")),
                    strList(o.optJSONArray("apps")),
                    o.optString("relationship"),
                    o.optString("notes"),
                    o.optString("autoSummary"),
                    o.optLong("updatedAt", 0L)
                ));
            }
        }
        if (loaded.trustworthy) contactsCache = list;
        return list;
    }

    private List<LogEntry> loadLog(String contactId) {
        List<LogEntry> cached = logCache.get(contactId);
        if (cached != null) return cached;
        List<LogEntry> list = new ArrayList<>();
        Loaded loaded = readJsonArray(logFile(contactId));
        if (loaded.arr != null) {
            for (int i = 0; i < loaded.arr.length(); i++) {
                JSONObject o = loaded.arr.optJSONObject(i);
                if (o == null) continue;
                list.add(new LogEntry(
                    o.optString("side", "other"),
                    o.optString("text"),
                    o.optLong("ts", 0L),
                    o.optString("app")
                ));
            }
        }
        if (loaded.trustworthy) logCache.put(contactId, list);
        return list;
    }

    private static String notesJson(List<Note> list) {
        JSONArray arr = new JSONArray();
        for (Note n : list) {
            arr.put(new JSONObject()
                .put("id", n.id())
                .put("title", n.title())
                .put("content", n.content())
                .put("tags", new JSONArray(n.tags()))
                .put("alwaysOn", n.alwaysOn())
                .put("enabled", n.enabled())
                .put("updatedAt", n.updatedAt()));
        }
        return arr.toString();
    }

    private static String contactsJson(List<Contact> list) {
        JSONArray arr = new JSONArray();
        for (Contact c : list) {
            arr.put(new JSONObject()
                .put("id", c.id())
                .put("name", c.name())
                .put("aliases", new JSONArray(c.aliases()))
                .put("apps", new JSONArray(c.apps()))
                .put("relationship", c.relationship())
                .put("notes", c.notes())
                .put("autoSummary", c.autoSummary())
                .put("updatedAt", c.updatedAt()));
        }
        return arr.toString();
    }

    private static String logJson(List<LogEntry> list) {
        JSONArray arr = new JSONArray();
        for (LogEntry e : list) {
            arr.put(new JSONObject()
                .put("side", e.side())
                .put("text", e.text())
                .put("ts", e.ts())
                .put("app", e.app()));
        }
        return arr.toString();
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private static final class Loaded {
        final JSONArray arr;
        final boolean trustworthy;

        Loaded(JSONArray arr, boolean trustworthy) {
            this.arr = arr;
            this.trustworthy = trustworthy;
        }
    }

    private Loaded readJsonArray(Path f) {
        if (!Files.exists(f)) {
            unreadable.remove(f.toAbsolutePath().toString());
            return new Loaded(null, true);
        }
        try {
            JSONArray arr = new JSONArray(Files.readString(f, StandardCharsets.UTF_8));
            unreadable.remove(f.toAbsolutePath().toString());
            return new Loaded(arr, true);
        } catch (Exception e) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            Path backup = f.resolveSibling(f.getFileName().toString() + ".corrupt." + System.currentTimeMillis());
            boolean kept = false;
            try {
                Files.move(f, backup);
                kept = true;
            } catch (Exception ignored) {
            }
            if (kept) unreadable.remove(f.toAbsolutePath().toString());
            else unreadable.add(f.toAbsolutePath().toString());
            LOGGER.warning("unreadable " + f.getFileName() + ": " + e.getClass().getSimpleName() + " preserved=" + kept);
            return new Loaded(null, kept);
        }
    }

    /**
     * Temp file + rename, so a crash never leaves a half-written document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. Returns false when the data did not reach disk; callers
     * drop their cache so the next read goes back to the file.
     */
    private boolean writeAtomic(Path f, String text) {
        if (unreadable.contains(f.toAbsolutePath().toString())) {
            LOGGER.warning("refusing to overwrite unparsable " + f.getFileName());
            return false;
        }
        Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
        try {
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception moveEx) {
                // Same-directory rename should not fail. If it somehow does, an
                // in-place overwrite is the only way left — not atomic, so say so.
                LOGGER.warning("rename failed, overwriting " + f.getFileName() + " in place");
                Files.writeString(f, text, StandardCharsets.UTF_8);
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
                return true;
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            LOGGER.warning("write failed " + f.getFileName() + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void deleteRecursively(Path p) {
        try {
            if (!Files.exists(p)) return;
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path child : ds) deleteRecursively(child);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    private static List<String> strList(JSONArray arr) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String key(String side, String text) {
        return side + "\u0000" + text;
    }

    private static int indexOfNote(List<Note> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static int indexOfContact(List<Contact> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> out = new ArrayList<>(list);
        out.add(item);
        return out;
    }

    private static boolean disjoint(List<String> keys, List<String> prev) {
        for (String k : keys) {
            if (prev.contains(k)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ ids

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * Compile a pattern without ever taking the class down with it. A
     * `Pattern.compile(...)` straight in a `static final` runs during
     * `&lt;clinit&gt;`, so one bad pattern turns into `ExceptionInInitializerError` and
     * every call into [KbStore] dies with it — which is exactly what happened on
     * device (Android's ICU engine rejected the old member-count pattern). A null
     * here only means that one cleanup step is skipped.
     */
    private static Pattern safeRegex(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (Exception e) {
            LOGGER.warning("regex init failed: " + e.getClass().getSimpleName() + " " +
                (e.getMessage() == null ? "" : e.getMessage()));
            return null;
        }
    }

    private static final Pattern ZERO_WIDTH = safeRegex("[\\u200B-\\u200D\\uFEFF]");

    /**
     * Trailing group member count. Written as an alternation of escaped code
     * points rather than a character class holding brackets: ICU on device
     * read `[(...)]` as an unterminated class ("missing closing bracket").
     * No literal full-width bracket in the source, on purpose.
     */
    private static final Pattern TRAILING_COUNT =
        safeRegex("\\s*(?:\\(|\\uFF08)\\s*\\d+\\s*(?:\\)|\\uFF09)\\s*$");

    private static String stripZeroWidth(String s) {
        return ZERO_WIDTH == null ? s : ZERO_WIDTH.matcher(s).replaceAll("");
    }

    private static String stripTrailingCount(String s) {
        return TRAILING_COUNT == null ? s : TRAILING_COUNT.matcher(s).replaceAll("").trim();
    }

    /**
     * Name key for matching: trimmed, zero-width characters removed, the
     * group member count `(12)` (half- or full-width) dropped, lower-cased.
     * If the patterns failed to compile this degrades to trim + lowercase.
     */
    public static String normalizeName(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = stripTrailingCount(stripZeroWidth(s).trim());
        return t.trim().toLowerCase(Locale.ROOT);
    }

    /** Same cleanup as [normalizeName] but keeps the original casing, for display. */
    public static String displayName(String s) {
        if (s == null || s.isEmpty()) return "";
        return stripTrailingCount(stripZeroWidth(s).trim()).trim();
    }

    /** Loose key for substring matching (no member-count stripping). */
    public static String normalizeText(String s) {
        if (s == null || s.isEmpty()) return "";
        return stripZeroWidth(s).trim().toLowerCase(Locale.ROOT);
    }
}
