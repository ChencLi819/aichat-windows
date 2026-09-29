package com.jev.probe.desktop.llm;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ChatContext;
import com.jev.probe.desktop.kb.LogEntry;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Choice;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.model.Score;
import com.jev.probe.desktop.net.ApiException;
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

    /**
     * 「问 AI」开启思考所需的最小输出上限（tokens）。
     *
     * <p>思考过程与正文共用这个上限，实测一次真实的 30 条对话思考就要 3364
     * tokens；上限低于 4096 时先开思考必然把正文挤空（`finish_reason=length`），
     * 所以这种配置下直接不思考，先保证有答案。
     */
    private static final int THINKING_FLOOR = 4096;

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
            ChatOutcome out = chatAdaptive(
                JudgePrompts.renderSystemPrompt(),
                userMessage(snapshot, relationship, ctx),
                0.0,
                null,
                true,
                onDelta
            );
            String content = out.stream().content();
            JSONObject o = extractJsonObject(content);
            if (o == null) throw new IllegalStateException("模型没有返回 JSON：" + take(content, 80));
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
                reasoningOf(out)
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
     * <p>Why the thinking pass is optional here, and why there is a retry. On a
     * reasoning model the output cap counts the thinking tokens too. Measured
     * 2026-09-29 against Bailian `deepseek-v4.1-flash`, a 30-message conversation
     * and a ordinary question: 3364 reasoning tokens, `finish_reason=length`, and
     * an EMPTY answer under the default 2048 cap — the reported 「问 AI 返回内容
     * 为空」. Two defences, in order:
     *
     * <ol>
     *   <li>a cap below [THINKING_FLOOR] means the budget cannot hold a thinking
     *       pass plus an answer, so thinking is switched off for this call
     *       (the judge route keeps thinking — it has no cap at all);</li>
     *   <li>if the response still carries no content, ask again with thinking off,
     *       which is what makes the answer arrive even on a host whose thinking
     *       pass is longer than expected.</li>
     * </ol>
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
        String user = userMessage(snapshot, relationship, ctx) + "\n\n# 我的问题\n" + question.trim();
        int cap = prefs.maxOutputTokens();
        boolean thinking = cap >= THINKING_FLOOR;
        try {
            ChatOutcome out = chatAdaptive(ASK_SYSTEM, user, 0.3, cap, thinking, onDelta);
            return new ChatAiClient.AskResult(out.stream().content().trim(), reasoningOf(out));
        } catch (Exception e) {
            LOGGER.warning("ask failed: " + e.getMessage());
            return failed(e);
        }
    }

    /** 一次调用的结果：流 + 降级重试前已经流给用户看过的思考过程。 */
    private record ChatOutcome(HttpJson.ChatStream stream, String reasoningBefore) {
    }

    /**
     * 带「思考降级重试」的一次 chat 调用，判断与问 AI 共用。
     *
     * <p>为什么两条路由都要它：2026-09-29 实测到提供方 **HTTP 200 + 空响应**
     * （既没有正文也没有思考），应用当时报的就是用户看到的
     * 「判断接口 HTTP 200：响应里没有消息内容」；另一种是思考把输出额度吃光
     * （实测 30 条对话思考 3364 tokens > 2048 上限）。这两种都属于“内容不可用”，
     * 重发一次并顺手**关掉思考**即可拿到答案，代价比让用户看到报错小得多。
     *
     * <p>只对“内容不可用”（[ApiException#retryable] 为 false）降级重试；传输类失败
     * 交给 HTTP 层自己的重试，不在这里再等一遍。
     */
    private ChatOutcome chatAdaptive(
        String system,
        String user,
        double temperature,
        Integer maxTokens,
        boolean thinking,
        BiConsumer<String, String> onDelta
    ) {
        StringBuilder seen = new StringBuilder();
        BiConsumer<String, String> tap = (kind, chunk) -> {
            if ("reasoning".equals(kind)) seen.append(chunk);
            if (onDelta != null) onDelta.accept(kind, chunk);
        };
        try {
            return new ChatOutcome(chat(system, user, temperature, maxTokens, tap, thinking), null);
        } catch (RuntimeException first) {
            if (!thinking || !contentFailure(first)) throw first;
            LOGGER.warning("内容不可用，关闭思考重试一次：" + first.getMessage());
            // 面板上正在显示第一轮的思考过程，重试前先清掉，免得两次思考叠在一起。
            if (onDelta != null) onDelta.accept("reset", "");
            String before = seen.toString();
            return new ChatOutcome(chat(system, user, temperature, maxTokens, tap, false), before);
        }
    }

    /** 内容类失败（空正文 / 只有思考 / 被上限截断），与传输类失败相对。 */
    private static boolean contentFailure(Exception e) {
        return e instanceof ApiException ae && !ae.retryable();
    }

    /**
     * 思考过程：优先本轮流出来的，其次降级重试之前那一轮已经给用户看过的
     * （重试不能让它凭空消失），最后才用响应体自带的字段。
     */
    private static String reasoningOf(ChatOutcome out) {
        String streamed = out.stream().reasoning();
        if (streamed != null && !streamed.isBlank()) return streamed.trim();
        String before = out.reasoningBefore();
        return before != null && !before.isBlank() ? before.trim() : null;
    }

    private static ChatAiClient.AskResult failed(Exception e) {
        return new ChatAiClient.AskResult(
            "问 AI 失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), null);
    }

    /** One chat-completions round trip; returns the full streamed message. */
    private HttpJson.ChatStream chat(
        String system,
        String user,
        double temperature,
        Integer maxTokens,
        BiConsumer<String, String> onDelta,
        boolean thinking
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
        HttpJson.applyJudgeRouteOptions(body, url, thinking);
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
