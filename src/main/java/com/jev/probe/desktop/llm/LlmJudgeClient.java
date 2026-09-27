package com.jev.probe.desktop.llm;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ChatContext;
import com.jev.probe.desktop.kb.LogEntry;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Choice;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.model.Score;
import com.jev.probe.desktop.net.HttpJson;
import com.jev.probe.desktop.net.Route;
import com.jev.probe.desktop.prompt.JudgePrompts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The judgment route, on any OpenAI-compatible `/chat/completions` endpoint.
 *
 * v1.5 replaced the Jev `state` + `questions` -> `answers` protocol with one
 * ordinary chat call: the 7 calibrated questions are rendered into a system
 * prompt ([JudgePrompts.renderSystemPrompt]) and the model is asked to answer
 * them as a single JSON object, which [parseAnalysis] turns back into the same
 * [Analysis] the UI has always rendered. No Jev host, key or model is involved.
 *
 * Reads judgeBaseUrl / judgeKey / judgeModel from [Config].
 */
public class LlmJudgeClient {

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    /** "问 AI" persona: a blunt, practical advisor that reads the thread first. */
    private static final String ASK_SYSTEM =
        "你是中文聊天参谋。你会先读到我和对方的聊天记录（可能还带背景和知识库），" +
            "然后回答我关于这段聊天的问题。\n" +
            "要求：先看聊天记录再下结论；说人话、给可执行的建议；" +
            "不确定的地方直说不确定，不要编造聊天里没有的事实；不要复述我的问题。";

    /** The 10 danger levels in JudgePrompts are scored 0..9. */
    private static final int MAX_DANGER_LEVEL = 9;

    /** Used when the model omits `confidence` (it is not part of its task). */
    private static final double DEFAULT_CONFIDENCE = 0.5;

    private final Config prefs;

    public LlmJudgeClient(Config prefs) {
        this.prefs = prefs;
    }

    public Analysis judge(ChatSnapshot snapshot, String relationship) {
        return judge(snapshot, relationship, null, null);
    }

    public Analysis judge(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        return judge(snapshot, relationship, ctx, null);
    }

    /**
     * The 7 judgment questions in one call. Errors are returned inside
     * [Analysis.error], never thrown.
     *
     * @param onDelta streaming progress (see [HttpJson.postChatFull]); the panel
     *        uses it to show the thinking pass live. Null = collect silently.
     * @param ctx knowledge context (contact, history, notes, session) — null or
     *        empty means the prompt carries only the on-screen conversation.
     */
    public Analysis judge(
        ChatSnapshot snapshot,
        String relationship,
        ChatContext ctx,
        BiConsumer<String, String> onDelta
    ) {
        long start = System.currentTimeMillis();
        try {
            HttpJson.ChatStream out = chat(
                JudgePrompts.renderSystemPrompt(),
                userMessage(snapshot, relationship, ctx),
                0.0,
                null,
                onDelta
            );
            String content = out.content();
            JSONObject o = extractJsonObject(content);
            if (o == null) throw new IllegalStateException("模型没有返回 JSON：" + take(content, 80));
            String reasoning = out.reasoning() != null && !out.reasoning().isBlank() ? out.reasoning() : null;
            return new Analysis(
                parseChoice(o.optJSONObject("true_intent")),
                parseScore(o.optJSONObject("danger_level")),
                parseChoice(o.optJSONObject("she_needs")),
                parseUnit(o, "should_reply_now"),
                parseChoice(o.optJSONObject("best_action")),
                parseUnit(o, "tension_resolved"),
                parseUnit(o, "literal_question"),
                List.of(),
                System.currentTimeMillis() - start,
                null,
                reasoning
            );
        } catch (Exception e) {
            LOGGER.warning("judge failed: " + e.getMessage());
            return new Analysis(null, null, null, null, null, null, null, List.of(),
                System.currentTimeMillis() - start,
                e.getMessage() != null ? e.getMessage() : "判断接口请求失败", null);
        }
    }

    /**
     * One judgment round trip for the settings connectivity test, over a
     * two-line synthetic conversation — the route can be checked without a real
     * chat being on screen. The Android form reports the same three things.
     *
     * @return a one-line 「成功 …」/「失败 …」 report; never throws.
     */
    public String ping() {
        long start = System.currentTimeMillis();
        ChatSnapshot snapshot = new ChatSnapshot("连通性测试", List.of(
            new Msg("other", "你在干嘛呢？"),
            new Msg("me", "在忙，晚点说")));
        Analysis a = judge(snapshot, Config.DEFAULT_REL);
        long ms = System.currentTimeMillis() - start;
        if (a.error() != null) return "失败（" + ms + "ms）：" + a.error();
        Choice intent = a.trueIntent();
        String choice = intent != null ? intent.choice() : "（未返回）";
        String pct = intent != null ? Math.round(intent.confidence() * 100) + "%" : "-";
        return "成功 " + ms + "ms · 意图=" + choice + "（置信 " + pct + "）";
    }

    /** The shared user message: background + history + session + on-screen chat. */
    private String userMessage(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        return JudgePrompts.renderUserMessage(
            snapshot,
            relationship,
            ctx != null ? ctx.background() : "",
            ctx != null ? ctx.history() : List.<LogEntry>of(),
            prefs.analysisWindow()
        );
    }

    /**
     * Free-form "问 AI": answer the user's own question about this conversation.
     * Unlike [judge] this is generative, so it is capped by the user's output
     * limit and is allowed to be as long as they asked for.
     *
     * @return the answer plus the thinking pass; the answer starts with
     *         "问 AI 失败" on error — the panel shows whatever comes back, so a
     *         failure has to be text.
     */
    public ChatAiClient.AskResult ask(
        String question,
        ChatSnapshot snapshot,
        String relationship,
        ChatContext ctx,
        BiConsumer<String, String> onDelta
    ) {
        try {
            HttpJson.ChatStream out = chat(
                ASK_SYSTEM,
                userMessage(snapshot, relationship, ctx) + "\n\n# 我的问题\n" + question.trim(),
                0.3,
                prefs.maxOutputTokens(),
                onDelta
            );
            String reasoning = out.reasoning() != null && !out.reasoning().isBlank() ? out.reasoning() : null;
            return new ChatAiClient.AskResult(out.content().trim(), reasoning);
        } catch (Exception e) {
            LOGGER.warning("ask failed: " + e.getMessage());
            return new ChatAiClient.AskResult(
                "问 AI 失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), null);
        }
    }

    /** One chat-completions round trip; returns the full streamed message. */
    private HttpJson.ChatStream chat(
        String system,
        String user,
        double temperature,
        Integer maxTokens,
        BiConsumer<String, String> onDelta
    ) {
        String url = prefs.judgeEndpoint();
        JSONArray messages = new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject()
            .put("model", prefs.judgeModel())
            .put("messages", messages)
            .put("temperature", temperature);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        // Explicitly request the thinking pass on hosts that default it off
        // (Bailian; DeepSeek official selects it via the model name instead).
        HttpJson.applyJudgeRouteOptions(body, url);
        // Streamed: with thinking on, the silent wait can exceed any sane read
        // timeout; streaming turns it into continuous chunk traffic.
        return HttpJson.postChatFull(url, prefs.judgeKey(), body, Route.JUDGE, prefs.proxy(), onDelta);
    }

    /**
     * The model's answer, as JSON. Tolerates the two shapes models actually
     * produce: a fenced ```json block, and prose wrapped around the object.
     */
    private static JSONObject extractJsonObject(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            t = removePrefix(t, "```json");
            t = removePrefix(t, "```JSON");
            t = removePrefix(t, "```");
            t = t.trim();
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence).trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(t.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String removePrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static Choice parseChoice(JSONObject o) {
        if (o == null) return null;
        String name = o.optString("choice");
        if (name.isBlank()) name = o.optString("answer");
        if (name.isBlank()) return null;
        Map<String, Double> probs = new HashMap<>();
        JSONObject p = o.optJSONObject("probabilities");
        if (p != null) {
            for (String k : p.keySet()) probs.put(k, p.optDouble(k));
        }
        return new Choice(name, confidence(o), probs);
    }

    private static Score parseScore(JSONObject o) {
        if (o == null) return null;
        double level = o.has("score") ? o.optDouble("score", 0.0) : o.optDouble("choice", 0.0);
        return new Score(level, confidence(o), MAX_DANGER_LEVEL);
    }

    /** A 0..1 answer; accepts `true`/`false` because models like to use them. */
    private static Double parseUnit(JSONObject o, String key) {
        if (!o.has(key)) return null;
        Object v = o.opt(key);
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            String t = s.trim();
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException nfe) {
                switch (t.toLowerCase(Locale.ROOT)) {
                    case "true":
                    case "yes":
                    case "是":
                        return 1.0;
                    case "false":
                    case "no":
                    case "否":
                        return 0.0;
                    default:
                        return null;
                }
            }
        }
        return null;
    }

    /** `confidence` is optional; a missing one must not read as "0% sure". */
    private static double confidence(JSONObject o) {
        double c = o.optDouble("confidence", Double.NaN);
        return Double.isNaN(c) ? DEFAULT_CONFIDENCE : Math.max(0.0, Math.min(1.0, c));
    }

    private static String take(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
