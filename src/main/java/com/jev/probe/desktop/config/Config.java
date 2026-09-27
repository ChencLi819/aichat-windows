package com.jev.probe.desktop.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Config store. Holds the three API routes (judge / reply / vision), the
 * relationship description used in the judgment prompt, the conversation
 * whitelist, plus the context (D stage) switches.
 *
 * All three routes are plain OpenAI-compatible chat hosts — no Jev endpoint is
 * involved anywhere.
 *
 * Key handling: stored in a JSON file at &lt;user.home&gt;/.aichat-desktop/config.json
 * (never logged, never in code/git). Only key *lengths* are ever logged. The
 * judge key may also be supplied through the AICHAT_JUDGE_KEY environment
 * variable, so it never has to be written to disk at all.
 *
 * Desktop port of the Android `Prefs` (SharedPreferences) — the legacy
 * SharedPreferences migrations (v1.2→v1.3, v1.4→v1.5) do not apply here: a
 * fresh desktop install has no Android store to carry over.
 */
public class Config {

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    /** `&lt;user.home&gt;/.aichat-desktop/config.json` — the desktop config file. */
    private static final Path DEFAULT_FILE =
        Path.of(System.getProperty("user.home"), ".aichat-desktop", "config.json");

    private static final String K_JUDGE_PROVIDER = "judge_provider";
    private static final String K_JUDGE_BASE = "judge_base_url";
    private static final String K_JUDGE_KEY = "judge_key";
    private static final String K_JUDGE_MODEL = "judge_model";
    private static final String K_REPLY_BASE = "reply_base_url";
    private static final String K_REPLY_KEY = "reply_key";
    private static final String K_REPLY_MODEL = "reply_model";
    private static final String K_VISION_BASE = "vision_base_url";
    private static final String K_VISION_KEY = "vision_key";
    private static final String K_VISION_MODEL = "vision_model";
    private static final String K_CTX_ENABLED = "context_enabled";
    private static final String K_CTX_COUNT = "context_history_count";
    private static final String K_CTX_MAX_TOKENS = "context_max_tokens";
    private static final String K_MAX_OUTPUT = "max_output_tokens";
    private static final String K_ANALYSIS_WINDOW = "analysis_window";
    private static final String K_REL = "relationship";
    private static final String K_ENABLED = "enabled";
    private static final String K_WHITELIST = "whitelist";
    private static final String K_PROXY_ENABLED = "proxy_enabled";
    private static final String K_PROXY_TYPE = "proxy_type";
    private static final String K_PROXY_HOST = "proxy_host";
    private static final String K_PROXY_PORT = "proxy_port";
    private static final String K_WATCH_SECONDS = "watch_interval_seconds";
    private static final String K_CONV_NAME = "conversation_name";
    private static final String K_TARGET = "capture_target";
    private static final String K_UI_THEME = "ui_theme";
    private static final String K_THEME_MODE = "theme_mode";
    private static final String K_FONT_SCALE = "font_scale";
    private static final String K_ONBOARDED = "onboarded";
    private static final String K_EVIDENCE_VISIBLE = "evidence_visible";
    private static final String K_AUTO_ANALYZE = "auto_analyze";
    private static final String K_PARK_OFFSCREEN = "park_offscreen";
    private static final String K_PANEL_OPACITY = "panel_opacity";

    /** UI palette values; "light" is the reference default. */
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    /** Follow the operating system's app-mode preference. */
    public static final String THEME_SYSTEM = "system";

    /** Font-size presets from the 外观 page. */
    public static final String FONT_STANDARD = "standard";
    public static final String FONT_LARGE = "large";

    public static final String PROXY_HTTP = "http";
    public static final String PROXY_SOCKS = "socks";

    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CUSTOM = "custom";

    // LLM presets. Every route speaks plain OpenAI-compatible
    // `/chat/completions`; only host + model differ between them.
    //
    // Bailian (DashScope's compatible mode) is the default: one key covers
    // both text routes and the vision route. `deepseek-v4.1-flash` is the
    // model id Bailian publishes for DeepSeek V4.1 (there is no bare
    // `deepseek-v4.1`; the other V4 ids are -pro / -pro-0813 / -flash-0731).
    public static final String LLM_BASE_BAILIAN = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String LLM_MODEL_BAILIAN_DEEPSEEK = "deepseek-v4.1-flash";
    public static final String LLM_MODEL_BAILIAN_QWEN = "qwen-plus";
    public static final String LLM_BASE_DEEPSEEK = "https://api.deepseek.com/v1";
    /**
     * DeepSeek's current model (2026-09 docs): deepseek-flash = V4.1-Flash,
     * 1M context, native vision. The legacy names deepseek-chat /
     * deepseek-reasoner were deprecated 2026-07-24.
     */
    public static final String LLM_MODEL_DEEPSEEK_FLASH = "deepseek-flash";
    public static final String LLM_BASE_OPENROUTER = "https://openrouter.ai/api/v1";
    public static final String LLM_MODEL_OPENROUTER = "deepseek/deepseek-chat";
    public static final String LLM_BASE_OPENAI = "https://api.openai.com/v1";
    public static final String LLM_MODEL_OPENAI = "gpt-4o-mini";
    /** The vision preset OpenRouter pairs with, which is not its text model. */
    public static final String LLM_MODEL_OPENROUTER_VL = "qwen/qwen2.5-vl-72b-instruct";

    // Vision route preset (multimodal host; user may change). Same Bailian
    // key as the text routes.
    public static final String DEFAULT_VISION_BASE = LLM_BASE_BAILIAN;
    public static final String DEFAULT_VISION_MODEL = "qwen-vl-max";

    /**
     * One selectable provider preset — the pills above each route's fields.
     *
     * @param provider the [PROVIDER_*] value this preset implies, used to tell a
     *        stale pill selection from a hand-typed address (see the settings
     *        form's test buttons).
     * @param base/model what the pill fills in. The custom preset carries blanks
     *        on purpose: guessing a host or a model for it would silently point
     *        somewhere the user never chose.
     */
    public record Preset(String label, String provider, String base, String model) {
    }

    /**
     * Judge and reply routes: all OpenAI-compatible, so they share one list.
     * Copied value-for-value from the Android form's `LLM_PRESETS`.
     */
    public static final List<Preset> TEXT_PRESETS = List.of(
        new Preset("百炼 DeepSeek V4.1", PROVIDER_DASHSCOPE,
            LLM_BASE_BAILIAN, LLM_MODEL_BAILIAN_DEEPSEEK),
        new Preset("DeepSeek 官方", PROVIDER_DEEPSEEK,
            LLM_BASE_DEEPSEEK, LLM_MODEL_DEEPSEEK_FLASH),
        new Preset("百炼 通义千问", PROVIDER_DASHSCOPE,
            LLM_BASE_BAILIAN, LLM_MODEL_BAILIAN_QWEN),
        new Preset("OpenAI", PROVIDER_OPENAI,
            LLM_BASE_OPENAI, LLM_MODEL_OPENAI),
        new Preset("OpenRouter", PROVIDER_OPENROUTER,
            LLM_BASE_OPENROUTER, LLM_MODEL_OPENROUTER),
        new Preset("自定义", PROVIDER_CUSTOM, "", ""));

    /**
     * Vision presets. deepseek-flash has native vision, so the DeepSeek official
     * host is on the list; its v4-pro does not.
     */
    public static final List<Preset> VISION_PRESETS = List.of(
        new Preset("百炼 通义 VL", PROVIDER_DASHSCOPE, DEFAULT_VISION_BASE, DEFAULT_VISION_MODEL),
        new Preset("DeepSeek VL", PROVIDER_DEEPSEEK, LLM_BASE_DEEPSEEK, LLM_MODEL_DEEPSEEK_FLASH),
        new Preset("OpenRouter", PROVIDER_OPENROUTER, LLM_BASE_OPENROUTER, LLM_MODEL_OPENROUTER_VL),
        new Preset("自定义", PROVIDER_CUSTOM, "", ""));

    // Injected-context ceiling: 1M tokens by default, approximated by
    // characters. The floor keeps a stray "0" from silently disabling it.
    public static final int DEFAULT_CONTEXT_MAX_TOKENS = 1_000_000;
    public static final int MIN_CONTEXT_MAX_TOKENS = 1_000;
    public static final int MAX_CONTEXT_MAX_TOKENS = 2_000_000;

    // Output ceiling for the generative routes. On a reasoning model the
    // cap counts the thinking tokens too (a measured run spent ~1000 tokens
    // thinking before writing ~40), so the default leaves room for both.
    // The judgment route sends no cap at all.
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2_048;
    public static final int MIN_MAX_OUTPUT_TOKENS = 256;
    public static final int MAX_MAX_OUTPUT_TOKENS = 32_768;

    public static final String DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的";

    private final Path file;
    private final JSONObject data;
    private final Object lock = new Object();

    public Config() {
        this(DEFAULT_FILE);
    }

    public Config(Path file) {
        this.file = file;
        this.data = load(file);
    }

    private static JSONObject load(Path file) {
        try {
            if (Files.exists(file)) {
                return new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.warning("config load failed: " + e.getClass().getSimpleName());
        }
        return new JSONObject();
    }

    private void put(String key, Object value) {
        synchronized (lock) {
            data.put(key, value);
            save();
        }
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, data.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warning("config save failed: " + e.getClass().getSimpleName());
        }
    }

    private String getString(String key, String def) {
        String v = data.optString(key, def);
        return v == null ? def : v;
    }

    // ---------------------------------------------------------------- judge

    /** Judgment route: any OpenAI-compatible chat host. */
    public String judgeProvider() {
        return getString(K_JUDGE_PROVIDER, PROVIDER_DASHSCOPE);
    }

    public void judgeProvider(String v) {
        put(K_JUDGE_PROVIDER, v.trim());
    }

    /** OpenAI-compatible base, up to and including `/v1`. */
    public String judgeBaseUrl() {
        return getString(K_JUDGE_BASE, LLM_BASE_BAILIAN);
    }

    public void judgeBaseUrl(String v) {
        put(K_JUDGE_BASE, v.trim());
    }

    /**
     * The judge key: the stored value, else the AICHAT_JUDGE_KEY environment
     * variable, else blank — so the key never has to be written to disk.
     */
    public String judgeKey() {
        String k = getString(K_JUDGE_KEY, "");
        if (!k.isBlank()) return k;
        String env = System.getenv("AICHAT_JUDGE_KEY");
        return env != null ? env.trim() : "";
    }

    public void judgeKey(String v) {
        put(K_JUDGE_KEY, v.trim());
    }

    /**
     * The judge key exactly as stored on disk, WITHOUT the environment fallback.
     * The settings form uses this so opening and saving it never copies an
     * env-supplied key into the file.
     */
    public String storedJudgeKey() {
        return getString(K_JUDGE_KEY, "");
    }

    /** The model that answers the 7 judgment questions. */
    public String judgeModel() {
        return getString(K_JUDGE_MODEL, LLM_MODEL_BAILIAN_DEEPSEEK);
    }

    public void judgeModel(String v) {
        put(K_JUDGE_MODEL, v.trim());
    }

    // ---------------------------------------------------------------- reply

    /**
     * OpenAI-compatible base, up to and including `/v1`. Defaults to the same
     * host as the judge route: [replyKey] already falls back to [judgeKey], so
     * one provider and one key are enough to run the whole app.
     */
    public String replyBaseUrl() {
        return getString(K_REPLY_BASE, LLM_BASE_BAILIAN);
    }

    public void replyBaseUrl(String v) {
        put(K_REPLY_BASE, v.trim());
    }

    /** Blank = fall back to [judgeKey]. */
    public String replyKey() {
        return getString(K_REPLY_KEY, "");
    }

    public void replyKey(String v) {
        put(K_REPLY_KEY, v.trim());
    }

    /** Generative model for drafting the 3 candidate replies. */
    public String replyModel() {
        return getString(K_REPLY_MODEL, LLM_MODEL_BAILIAN_DEEPSEEK);
    }

    public void replyModel(String v) {
        put(K_REPLY_MODEL, v.trim());
    }

    // --------------------------------------------------------------- vision

    /**
     * Blank = the OpenRouter vision default. Deliberately does NOT follow
     * [replyBaseUrl]: a reply host like DeepSeek has no vision endpoint, so
     * inheriting it would silently break OCR.
     */
    public String visionBaseUrl() {
        return getString(K_VISION_BASE, DEFAULT_VISION_BASE);
    }

    public void visionBaseUrl(String v) {
        put(K_VISION_BASE, v.trim());
    }

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    public String visionKey() {
        return getString(K_VISION_KEY, "");
    }

    public void visionKey(String v) {
        put(K_VISION_KEY, v.trim());
    }

    public String visionModel() {
        return getString(K_VISION_MODEL, DEFAULT_VISION_MODEL);
    }

    public void visionModel(String v) {
        put(K_VISION_MODEL, v.trim());
    }

    // -------------------------------------------------------- context (D)

    /**
     * How many of the newest on-screen messages one analysis carries. This is
     * the per-analysis window; the per-conversation buffer the user injects
     * from the bubble menu is separate (see `ContextSessions`).
     */
    public int analysisWindow() {
        return clamp(data.optInt(K_ANALYSIS_WINDOW, 10), 1, 100);
    }

    public void analysisWindow(int v) {
        put(K_ANALYSIS_WINDOW, clamp(v, 1, 100));
    }

    /**
     * Ceiling for one conversation's injected context, in tokens (approximated
     * by character count — one CJK character is about one token). Default 1M.
     */
    public int contextMaxTokens() {
        return clamp(data.optInt(K_CTX_MAX_TOKENS, DEFAULT_CONTEXT_MAX_TOKENS),
            MIN_CONTEXT_MAX_TOKENS, MAX_CONTEXT_MAX_TOKENS);
    }

    public void contextMaxTokens(int v) {
        put(K_CTX_MAX_TOKENS, clamp(v, MIN_CONTEXT_MAX_TOKENS, MAX_CONTEXT_MAX_TOKENS));
    }

    /**
     * `max_tokens` for the generative routes (candidate replies and "问 AI"
     * answers), so a long-winded model cannot run away.
     *
     * The judgment route sends NO cap at all: on a reasoning model the cap
     * counts the thinking tokens too, and a small value there truncates the
     * answer mid-thought into unparseable garbage. The same applies here — the
     * default leaves room for thinking plus the answer (a measured run spent
     * ~1000 tokens thinking before writing ~40).
     */
    public int maxOutputTokens() {
        return clamp(data.optInt(K_MAX_OUTPUT, DEFAULT_MAX_OUTPUT_TOKENS),
            MIN_MAX_OUTPUT_TOKENS, MAX_MAX_OUTPUT_TOKENS);
    }

    public void maxOutputTokens(int v) {
        put(K_MAX_OUTPUT, clamp(v, MIN_MAX_OUTPUT_TOKENS, MAX_MAX_OUTPUT_TOKENS));
    }

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    public boolean contextEnabled() {
        return data.optBoolean(K_CTX_ENABLED, false);
    }

    public void contextEnabled(boolean v) {
        put(K_CTX_ENABLED, v);
    }

    /** How many recent history entries to inject. */
    public int contextHistoryCount() {
        return data.optInt(K_CTX_COUNT, 30);
    }

    public void contextHistoryCount(int v) {
        put(K_CTX_COUNT, v);
    }

    // ------------------------------------------------------------ proxy

    /**
     * User-configured proxy (the HTTP/SOCKS port a local VPN or accelerator
     * such as Clash / v2ray exposes). When enabled, EVERY model request —
     * judge, reply, vision — is tunneled through it; when anything is off or
     * malformed, requests go direct. Defaults are Clash's usual 7890.
     */
    public boolean proxyEnabled() {
        return data.optBoolean(K_PROXY_ENABLED, false);
    }

    public void proxyEnabled(boolean v) {
        put(K_PROXY_ENABLED, v);
    }

    /** [PROXY_HTTP] or [PROXY_SOCKS]. */
    public String proxyType() {
        return getString(K_PROXY_TYPE, PROXY_HTTP);
    }

    public void proxyType(String v) {
        put(K_PROXY_TYPE, v.trim());
    }

    public String proxyHost() {
        return getString(K_PROXY_HOST, "");
    }

    public void proxyHost(String v) {
        put(K_PROXY_HOST, v.trim());
    }

    public int proxyPort() {
        return clamp(data.optInt(K_PROXY_PORT, 7890), 1, 65535);
    }

    public void proxyPort(int v) {
        put(K_PROXY_PORT, clamp(v, 1, 65535));
    }

    /** The proxy to dial through, or null for a direct connection. */
    public Proxy proxy() {
        if (!proxyEnabled()) return null;
        String host = proxyHost().trim();
        if (host.isEmpty()) return null;
        Proxy.Type type = proxyType().equals(PROXY_SOCKS) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, new InetSocketAddress(host, proxyPort()));
    }

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into the judge prompt. */
    public String relationship() {
        return getString(K_REL, DEFAULT_REL);
    }

    public void relationship(String v) {
        put(K_REL, v);
    }

    /** Master on/off for showing the overlay + running analysis. */
    public boolean enabled() {
        return data.optBoolean(K_ENABLED, true);
    }

    public void enabled(boolean v) {
        put(K_ENABLED, v);
    }

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations".
     */
    public Set<String> whitelist() {
        JSONArray arr = data.optJSONArray(K_WHITELIST);
        if (arr == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public void whitelist(Set<String> v) {
        put(K_WHITELIST, new JSONArray(v));
    }

    // --------------------------------------------------------------- capture

    /**
     * How often the watcher grabs the region to look for a change, in seconds.
     * The poll itself is cheap (a hash, no model call); the value only bounds how
     * long after a new message the analysis can start.
     */
    public int watchIntervalSeconds() {
        return clamp(data.optInt(K_WATCH_SECONDS, 1), 1, 60);
    }

    public void watchIntervalSeconds(int v) {
        put(K_WATCH_SECONDS, clamp(v, 1, 60));
    }

    /**
     * Analyse as soon as the watched region settles on new content. Off means
     * the watcher still runs but only 「分析当前对话」 starts a round — the
     * switch for a user who wants the panel to stay out of the way until asked.
     */
    public boolean autoAnalyze() {
        return data.optBoolean(K_AUTO_ANALYZE, true);
    }

    public void autoAnalyze(boolean v) {
        put(K_AUTO_ANALYZE, v);
    }

    /**
     * Move the chat window past the screen edge when the user minimizes it or
     * closes it to the tray, instead of giving up on the capture.
     *
     * Windows keeps no render target for a minimized or hidden window — measured,
     * `PrintWindow` answers 100% black for both — but a window parked past the
     * edge of the virtual screen is still fully rendered and still repaints, so
     * the assistant can keep reading it while the user sees nothing. On by
     * default: without it, minimizing the chat silently stops the monitoring.
     *
     * The user is never fought: when the chat is asked for — tray icon, taskbar,
     * Alt-Tab — it is put back exactly where it was and left alone until it is
     * put away again.
     */
    public boolean parkOffScreen() {
        return data.optBoolean(K_PARK_OFFSCREEN, true);
    }

    public void parkOffScreen(boolean v) {
        put(K_PARK_OFFSCREEN, v);
    }

    /**
     * A user-set name for the conversation, used as the snapshot title when the
     * vision reader could not read one off the window header (it is also the
     * identity the knowledge base and the session buffer match on).
     */
    public String conversationName() {
        return getString(K_CONV_NAME, "");
    }

    public void conversationName(String v) {
        put(K_CONV_NAME, v.trim());
    }

    /**
     * The window — and the area inside it — the assistant reads, or null when
     * none has been chosen yet (then the app asks for one on start).
     *
     * A target saved before the window-relative scheme existed carries no
     * `window_title` and is reported as absent: its coordinates were screen
     * pixels from the old, DPI-dependent path and cannot be reinterpreted, so
     * the honest answer is "pick again" rather than a silently wrong crop.
     */
    public CaptureTarget captureTarget() {
        JSONObject o = data.optJSONObject(K_TARGET);
        if (o == null) return null;
        String title = o.optString("window_title", "").trim();
        int w = o.optInt("w", 0);
        int h = o.optInt("h", 0);
        if (title.isEmpty() || w <= 0 || h <= 0) return null;
        return new CaptureTarget(o.optString("window_class", "").trim(), title,
            Math.max(0, o.optInt("x", 0)), Math.max(0, o.optInt("y", 0)), w, h);
    }

    public void captureTarget(CaptureTarget t) {
        if (t == null) {
            synchronized (lock) {
                data.remove(K_TARGET);
                save();
            }
            return;
        }
        put(K_TARGET, new JSONObject()
            .put("window_class", t.windowClass())
            .put("window_title", t.windowTitle())
            .put("x", t.x()).put("y", t.y())
            .put("w", t.width()).put("h", t.height()));
    }

    // ------------------------------------------------------------------- ui

    /**
     * Which palette the desktop UI paints with: [THEME_LIGHT] (default),
     * [THEME_DARK] or [THEME_SYSTEM].
     *
     * A config written before 「跟随系统」 existed only has `ui_theme` with a
     * light/dark value; that choice is honoured rather than silently reset, so an
     * existing user keeps the palette they picked.
     */
    public String themeMode() {
        String mode = getString(K_THEME_MODE, "");
        if (!mode.isBlank()) return mode.trim();
        String legacy = getString(K_UI_THEME, "");
        return legacy.isBlank() ? THEME_LIGHT : legacy.trim();
    }

    public void themeMode(String v) {
        put(K_THEME_MODE, v == null || v.isBlank() ? THEME_LIGHT : v.trim());
    }

    /** 「标准」/「大」 — the appearance page's font-size preset. */
    public String fontScale() {
        return getString(K_FONT_SCALE, FONT_STANDARD);
    }

    public void fontScale(String v) {
        put(K_FONT_SCALE, FONT_LARGE.equals(v) ? FONT_LARGE : FONT_STANDARD);
    }

    public boolean isFontLarge() {
        return FONT_LARGE.equals(fontScale());
    }

    /**
     * Whether the first-run walkthrough has been completed. FR-OB-01 replaces the
     * old "no key yet → dump the settings form on them" behaviour with a
     * three-step introduction, which needs to know it has already happened.
     */
    public boolean onboarded() {
        return data.optBoolean(K_ONBOARDED, false);
    }

    public void onboarded(boolean v) {
        put(K_ONBOARDED, v);
    }

    /**
     * Whether 「本轮依据」 shows the messages the reader saw. A privacy
     * preference (Q-3): the evidence block is proof the advice is grounded, but
     * it also puts the raw conversation on screen, which is not always wanted.
     */
    public boolean evidenceVisible() {
        return data.optBoolean(K_EVIDENCE_VISIBLE, true);
    }

    public void evidenceVisible(boolean v) {
        put(K_EVIDENCE_VISIBLE, v);
    }

    /**
     * How opaque the floating panel is, in percent (60–100). The default leaves
     * a hair of translucency so the chat behind stays faintly readable.
     */
    public int panelOpacity() {
        return clamp(data.optInt(K_PANEL_OPACITY, 96), 60, 100);
    }

    public void panelOpacity(int v) {
        put(K_PANEL_OPACITY, clamp(v, 60, 100));
    }

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    public String effectiveReplyKey() {
        String k = replyKey();
        return k.isBlank() ? judgeKey() : k;
    }

    /** Vision route key, falling back to reply then judge. */
    public String effectiveVisionKey() {
        String k = visionKey();
        return k.isBlank() ? effectiveReplyKey() : k;
    }

    /** Full POST URL for the judgment route; same shape as [replyEndpoint]. */
    public String judgeEndpoint() {
        return chatEndpoint(judgeBaseUrl(), LLM_BASE_BAILIAN);
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    public String replyEndpoint() {
        return chatEndpoint(replyBaseUrl(), LLM_BASE_BAILIAN);
    }

    /** Same shape as [replyEndpoint]; blank falls back to the vision default. */
    public String visionEndpoint() {
        return chatEndpoint(visionBaseUrl(), DEFAULT_VISION_BASE);
    }

    /**
     * `<base>/chat/completions`, tolerating a base that already carries the
     * path (a user pasting the full endpoint from a provider's docs).
     */
    private static String chatEndpoint(String base, String fallback) {
        String b = base.trim();
        if (b.isBlank()) b = fallback;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    public boolean isAllowed(String title) {
        Set<String> wl = whitelist();
        if (wl.isEmpty()) return true;
        if (title == null) return false;
        for (String w : wl) {
            if (title.contains(w)) return true;
        }
        return false;
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    public boolean hasKey() {
        return !judgeKey().isBlank();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
