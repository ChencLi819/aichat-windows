package com.jev.probe.desktop.llm;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ChatContext;
import com.jev.probe.desktop.kb.LogEntry;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.model.RankedReply;
import com.jev.probe.desktop.net.HttpJson;
import com.jev.probe.desktop.net.Route;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Config].
 */
public class ReplyClient {

    private final Config prefs;

    public ReplyClient(Config prefs) {
        this.prefs = prefs;
    }

    public List<RankedReply> draft(ChatSnapshot snapshot, String relationship) {
        return draft(snapshot, relationship, null);
    }

    /**
     * Exactly 3 candidate replies in Chinese, each with the model's own score for
     * how well it fits.
     *
     * v1.5 folds the old separate ranking call into this one. Ranking used to be
     * a third round trip (judge, draft, then rank) that cost a full request just
     * to order three lines the drafting model had written a moment earlier — the
     * biggest single latency win available. The trade-off is that the ordering is
     * now the drafting model's own judgement rather than the judge model's; the
     * candidates themselves are unchanged.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    public List<RankedReply> draft(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        List<Msg> msgs = ChatSnapshot.takeLast(snapshot.messages(), prefs.analysisWindow());
        StringBuilder convo = new StringBuilder();
        for (int i = 0; i < msgs.size(); i++) {
            if (i > 0) convo.append('\n');
            Msg it = msgs.get(i);
            convo.append("me".equals(it.side()) ? "我" : "对方").append("：").append(it.text());
        }
        String sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 个对象，" +
            "每个对象形如 {\"text\":\"候选回复\",\"score\":0.0}。" +
            "text 是回复正文，不超过 40 字，口语、自然、像真人在聊天软件里发消息；" +
            "score 是 0~1，表示这条作为下一条消息的合适程度，三条要有区分。" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "不要解释，不要输出 JSON 以外的任何内容。";
        String user = knowledgeBlock(relationship, ctx) +
            "关系：" + relationship + "\n\n最近对话：\n" + convo + "\n\n请给出 3 条候选回复及其 score。";
        return parseThree(chat(sys, user, 0.8));
    }

    /** The background + history preamble; empty string when there is no context. */
    private String knowledgeBlock(String relationship, ChatContext ctx) {
        if (ctx == null) return "";
        String background = ctx.background();
        List<LogEntry> history = ctx.history();
        if (background.isBlank() && history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n");
        if (!background.isBlank()) sb.append(background).append('\n');
        if (!history.isEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n");
            int n = clamp(prefs.contextHistoryCount(), 0, 100);
            for (LogEntry it : ChatSnapshot.takeLast(history, n)) {
                sb.append("me".equals(it.side()) ? "我：" : "对方：").append(it.text()).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    public String ping() {
        return chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", 0.0).trim();
    }

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    public String summarize(String text) {
        if (text.isBlank()) return "";
        String sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。";
        return chat(sys, text, 0.2).trim();
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private String chat(String system, String user, double temperature) {
        String url = prefs.replyEndpoint();
        JSONArray messages = new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject()
            .put("model", prefs.replyModel())
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", prefs.maxOutputTokens());
        // Streamed (see [HttpJson.postChat]) and with the thinking pass skipped
        // on this route (see applyReplyRouteOptions): three short casual lines
        // come back in ~1-2s instead of ~15s.
        HttpJson.applyReplyRouteOptions(body, url);
        return HttpJson.postChat(url, prefs.effectiveReplyKey(), body, Route.REPLY, prefs.proxy());
    }

    /**
     * The model's array → 3 scored candidates, best first.
     *
     * Tolerant on purpose: models return `{"text","score"}` objects as asked, but
     * also plain strings, and sometimes wrap the array in prose. A plain string
     * gets a neutral score, so the order then falls back to the order written —
     * which is why the prompt asks for the best first as well as scoring it.
     */
    private static List<RankedReply> parseThree(String content) {
        List<RankedReply> out = new ArrayList<>();
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JSONArray arr = new JSONArray(content.substring(start, end + 1));
                for (int i = 0; i < arr.length(); i++) {
                    Object el = arr.opt(i);
                    if (el instanceof JSONObject jo) {
                        String t = jo.optString("text").trim();
                        if (!t.isEmpty()) out.add(new RankedReply(t, jo.optDouble("score", 0.0)));
                    } else if (el instanceof String s) {
                        if (!s.isBlank()) out.add(new RankedReply(s.trim(), 0.0));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            // Fallback: one reply per line, numbered or bulleted.
            String[] lines = content.split("\n");
            int count = 0;
            for (String line : lines) {
                String t = trimStartChars(line.trim(), "-*123. \"");
                if (!t.isBlank()) {
                    out.add(new RankedReply(t, 0.0));
                    if (++count >= 3) break;
                }
            }
        }
        while (out.size() < 3) out.add(new RankedReply("（稍等，我看下）", 0.0));
        // Stable, so equal scores keep the order the model wrote them in.
        out.sort(Comparator.comparingDouble(RankedReply::prob).reversed());
        return new ArrayList<>(out.subList(0, 3));
    }

    /** Kotlin's `trimStart('-', '*', '1', '2', '3', '.', ' ', '"')`. */
    private static String trimStartChars(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) i++;
        return s.substring(i);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
