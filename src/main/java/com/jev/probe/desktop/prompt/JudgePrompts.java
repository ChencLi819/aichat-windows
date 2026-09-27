package com.jev.probe.desktop.prompt;

import com.jev.probe.desktop.kb.LogEntry;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The judgment question set, ported verbatim from tools/jev/questions.py (the
 * wording that passed calibration). Instructions/criteria stay in English; chat
 * text stays Chinese.
 *
 * v1.5: these definitions are no longer sent to a Jev endpoint as a `questions`
 * payload — they are rendered into a system prompt for any OpenAI-compatible
 * LLM (see [renderSystemPrompt]). Keeping one source of truth for the criteria
 * wording means the calibrated definitions are not duplicated as loose prose.
 */
public final class JudgePrompts {

    private JudgePrompts() {
    }

    /**
     * A JSONObject whose key iteration follows insertion order.
     *
     * Android ships an org.json whose JSONObject is backed by a LinkedHashMap and
     * therefore preserves insertion order; the JVM `org.json:json` dependency is
     * backed by a HashMap and scrambles it. [renderSystemPrompt] walks the
     * question set — and each `choice` question's criteria — by key, so without
     * this the rendered prompt (the calibrated product) would come out in a
     * different order on the desktop than on Android.
     */
    private static final class OrderedJson extends JSONObject {
        private final List<String> order = new ArrayList<>();

        @Override
        public JSONObject put(String key, Object value) {
            if (!has(key)) order.add(key);
            return super.put(key, value);
        }

        @Override
        public Set<String> keySet() {
            return new LinkedHashSet<>(order);
        }
    }

    /**
     * Appended to every question so the `background` field (relationship,
     * contact notes, knowledge-base hits, session context) reads as given
     * context rather than as an off-topic digression that should be penalized.
     */
    public static final String BACKGROUND_NOTE =
        " Facts given in background are provided context, not off-topic.";

    private static JSONObject noul(String instructions, String t, String f) {
        JSONObject o = new JSONObject();
        o.put("type", "noul");
        o.put("instructions", instructions + BACKGROUND_NOTE);
        o.put("criteria", new JSONObject().put("true", t).put("false", f));
        return o;
    }

    private static JSONObject choice(String instructions, Map<String, String> criteria) {
        JSONObject o = new JSONObject();
        o.put("type", "choice");
        o.put("instructions", instructions + BACKGROUND_NOTE);
        JSONObject c = new OrderedJson();
        for (Map.Entry<String, String> e : criteria.entrySet()) {
            c.put(e.getKey(), e.getValue());
        }
        o.put("criteria", c);
        return o;
    }

    private static JSONObject score(String instructions, List<String> levels) {
        JSONObject o = new JSONObject();
        o.put("type", "score");
        o.put("instructions", instructions + BACKGROUND_NOTE);
        JSONArray a = new JSONArray();
        for (String level : levels) {
            a.put(level);
        }
        o.put("criteria", a);
        return o;
    }

    /** The 7 judgment questions. Returns a fresh JSONObject each call. */
    public static JSONObject judge() {
        JSONObject o = new OrderedJson();
        o.put("literal_question", noul(
            "Is the other person's latest message meant purely literally, with no subtext? " +
                "Judge from the whole thread, not one sentence in isolation.",
            "The latest message is a straightforward statement, question, or plan " +
                "with no implied accusation, test, sarcasm, hint, or unsaid request.",
            "There is subtext: a test of whether you remember or care, sarcasm, " +
                "an implied complaint, a hint they will not say outright, a trap question, " +
                "an accusation dressed as a question, or a cold/short line that really means blame."
        ));
        o.put("true_intent", choice(
            "What is the other person's true intent in the latest message, given the full conversation? " +
                "Prefer tone and context over surface wording. " +
                "If they are checking whether you remember something or still care, choose confirm_you_care " +
                "even if the words look like a request to 'say it' or to do something. " +
                "If they already accepted and closed the matter peacefully, choose close_topic. " +
                "Ending the relationship, deleting you, or 'don't talk to me' is vent_anger, never close_topic.",
            linkedMapOf(
                "confirm_you_care", "They are testing whether you remember, pay attention, or still care. " +
                    "Signals: 'did you forget again', 'then say it', 'you better', sarcastic 'busy person', " +
                    "asking you to prove you know a past conversation. " +
                    "If they mainly want a new deliverable or a yes on a time, do not use this.",
                "vent_anger", "They are angry or hurt and mainly want the feeling acknowledged. " +
                    "They are blaming or raising the temperature; a specific plan is not the main point yet.",
                "request_action", "They want a concrete action, time, deliverable, or commitment from you now, " +
                    "and this is a real ask, not a loyalty test.",
                "seek_explanation", "They want a factual explanation of why something happened. " +
                    "They asked why or what is going on, not mainly for an apology or a new plan.",
                "casual_chat", "Light talk, banter, sharing, teasing with a laugh, or friendly logistics " +
                    "with no emotional test and no conflict. A friend suggesting a meal time can be this " +
                    "if the thread is warm.",
                "close_topic", "Peaceful wrap-up only: they accepted an apology, confirmed a happy plan, said thanks, " +
                    "or clearly signaled they need nothing more. " +
                    "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'."
            )
        ));
        o.put("danger_level", score(
            "How close is this conversation to a fight or to hurting the relationship? " +
                "Match the current scene. " +
                "If they genuinely accepted an apology or confirmed a happy plan, score the cooled-down present, " +
                "not an earlier complaint. " +
                "If an ultimatum (break up, report to the boss, stop covering for you) is still in force " +
                "and has not been withdrawn, stay in that high bin even if the latest line names a specific task.",
            List.of(
                "Light chat or joking; no complaint, no test, no deadline.",
                "Mild tease or a small reminder that is easy to laugh off; a clumsy reply would only feel slightly awkward.",
                "A mild complaint or 'please remember next time' said without heat; they still send warm or practical follow-ups.",
                "Noticeable unhappiness; they mention being forgotten, ignored, or kept waiting, but still give you a chance to make it right.",
                "Sarcasm, cold short replies, or 'you better'; they are testing you, and a sloppy or fake-confident reply will escalate.",
                "Openly upset; they accuse you of not listening or not caring; they expect a real response, not a joke.",
                "Clearly angry and blaming you; a wrong reply will turn this into a fight.",
                "Last-chance warning. They will not cover for you, do not want to keep talking unless this changes, " +
                    "or tell you to finish a named checklist yourself because trust is almost gone.",
                "An ultimatum is already on the table even if they also give a practical next step: " +
                    "break up if you forget again, report you tonight, or stop working together if you miss this.",
                "Active rupture: they said it is over, told you not to reply, deleted you, or are exploding."
            )
        ));
        o.put("should_reply_now", noul(
            "Should your next message contain substantive content? " +
                "Substantive means: admitting a specific known fault, giving a concrete time/plan/deliverable, " +
                "explaining facts you actually know, or reciting the recalled content they asked you to say. " +
                "This is NOT 'should you send any message'. Timing is irrelevant. " +
                "Answer FALSE if the thing they want you to recite or prove is not present in this snippet " +
                "(you would be guessing). 'Then say it' / 'you better' while you are stalling is FALSE. " +
                "Answer FALSE if they already accepted and closed the topic. " +
                "Answer true only if the needed fact, plan, or named fault is already in this snippet.",
            "The needed fact, named fault, or named time/place is already in this snippet, " +
                "and they are waiting for that substance now.",
            "Do not put substance in the next message: the recalled content is not in this snippet, " +
                "they are testing whether you remember, a holding line is enough, " +
                "saying less is safer, or they already closed the topic."
        ));
        o.put("best_action", choice(
            "What type of next action is best? Do not decide whether to send a message immediately. " +
                "Ignore timing. Choose only the action type. " +
                "If they asked you to recall a specific past message or event and you have not shown that you actually remember it, " +
                "choose check_history - do not apologize or invent a plan instead.",
            linkedMapOf(
                "check_history", "Look up prior chat or facts before taking a position. " +
                    "Use when they ask you to repeat, recall, or prove you remember something specific.",
                "apologize", "Lead with a sincere apology for a real mistake or hurt already identified. " +
                    "Not for an unnamed forgotten thing when you should first find out what it was.",
                "give_commitment", "Give a concrete promise, deadline, or arrangement they asked for " +
                    "in a conflict or work-pressure setting.",
                "explain", "Explain what happened or why, without leading with apology or a new plan.",
                "acknowledge", "Show you heard them and care, without new facts, an apology, or a plan. " +
                    "Use for light chat or when they mainly need to feel seen.",
                "say_less", "Keep it short or add nothing. Extra words would over-explain, reopen a closed topic, " +
                    "or pour fuel on an ultimatum that told you not to talk.",
                "make_plan", "Propose or confirm logistics (time, place, task) for a non-conflict request " +
                    "such as a meal or a meeting."
            )
        ));
        o.put("she_needs", choice(
            "What does the other person need from you right now? Judge the LATEST message first. " +
                "If they genuinely accepted (thanks / got it / 没事了 / 那就这样 / 收到了 / 过去了), " +
                "you MUST choose nothing, even if earlier they wanted action or an apology. " +
                "Sarcastic 'I'm used to it', 'whatever', 'I don't want to hear it', 'don't bother coming' " +
                "is NOT genuine satisfaction - do not choose nothing. " +
                "If they asked you to recap a named time/place/date, choose action. " +
                "If they are testing whether you remember or still care, and the content is unnamed, choose care.",
            linkedMapOf(
                "apology", "They need a sincere apology for hurt or a mistake, and they have not accepted one yet.",
                "action", "They need a concrete action, time, commitment, recap of a named fact, or follow-through, " +
                    "and they have not yet accepted one.",
                "explanation", "They need a clear explanation of what happened or why, and have not received it.",
                "care", "They need proof you remember, listen, or care - a loyalty or attention test - " +
                    "not yet a plan or an apology. Sarcastic 'I am used to it' belongs here, not nothing.",
                "nothing", "They need nothing further. Genuine acceptance, a peaceful closed topic, " +
                    "warm casual chat with no ask, or a rupture where they told you not to reply. " +
                    "Not sarcasm pretending to be fine."
            )
        ));
        o.put("tension_resolved", noul(
            "Has interpersonal tension already been resolved? " +
                "Answer true only if there was never tension, or the other person has clearly accepted, " +
                "cooled down, joked again, or said it is fine. " +
                "A sarcastic 'you better', an unanswered test, leftover blame, or an open ultimatum means false.",
            "No remaining tension: they accepted, joked again, said it's fine, " +
                "confirmed a happy plan, or the chat was never tense.",
            "Tension is still present: they are waiting, testing, angry, sarcastic, " +
                "issuing an ultimatum, or the issue is open."
        ));
        return o;
    }

    /** An insertion-ordered map, the `linkedMapOf` the choice criteria rely on. */
    private static Map<String, String> linkedMapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The exact JSON shape [LlmJudgeClient] parses back into an `Analysis`. */
    private static final String CONTRACT =
        "{\n" +
            "  \"literal_question\": 0.0,\n" +
            "  \"true_intent\": { \"choice\": \"<选项值>\", \"confidence\": 0.0 },\n" +
            "  \"danger_level\": { \"score\": 0, \"confidence\": 0.0 },\n" +
            "  \"should_reply_now\": 0.0,\n" +
            "  \"best_action\": { \"choice\": \"<选项值>\", \"confidence\": 0.0 },\n" +
            "  \"she_needs\": { \"choice\": \"<选项值>\", \"confidence\": 0.0 },\n" +
            "  \"tension_resolved\": 0.0\n" +
            "}";

    /**
     * Render the 7 questions + the output contract into one system prompt.
     * `literal_question` / `should_reply_now` / `tension_resolved` are 0..1
     * decimals (1 = the "true" branch), `danger_level.score` is 0..9.
     */
    public static String renderSystemPrompt() {
        JSONObject q = judge();
        StringBuilder sb = new StringBuilder();
        sb.append("你是中文聊天情境判断助手。你会读到一段我和对方的聊天记录，")
            .append("需要按下面的判断题逐条给出判断。\n")
            .append("只输出一个 JSON 对象，不要解释，不要 markdown 代码块。\n\n# 判断题\n");
        for (String key : q.keySet()) {
            JSONObject o = q.getJSONObject(key);
            String type = o.optString("type");
            sb.append('\n').append('【').append(key).append('】');
            switch (type) {
                case "noul" -> sb.append("（返回 0~1 的小数：1 = 是，0 = 否）\n");
                case "choice" -> sb.append("（choice 只能从下面的选项值里选一个）\n");
                default -> sb.append("（返回 0~9 的整数分）\n");
            }
            sb.append(o.optString("instructions")).append('\n');
            switch (type) {
                case "noul" -> {
                    JSONObject c = o.optJSONObject("criteria");
                    sb.append("  是：").append(c == null ? null : c.optString("true")).append('\n');
                    sb.append("  否：").append(c == null ? null : c.optString("false")).append('\n');
                }
                case "choice" -> {
                    JSONObject c = o.optJSONObject("criteria");
                    if (c == null) c = new JSONObject();
                    for (String k : c.keySet()) {
                        sb.append("  - ").append(k).append("：").append(c.optString(k)).append('\n');
                    }
                }
                default -> {
                    JSONArray a = o.optJSONArray("criteria");
                    int len = a == null ? 0 : a.length();
                    for (int i = 0; i < len; i++) {
                        sb.append("  ").append(i).append(" = ").append(a.optString(i)).append('\n');
                    }
                }
            }
        }
        sb.append("\n# 输出格式（严格按此结构，字段名不要改）\n").append(CONTRACT);
        sb.append("\n\n所有 confidence 是 0~1 的小数。判断要依据整段对话，不要只看最后一句。");
        return sb.toString();
    }

    /**
     * The user message: background first (relationship, contact notes, notes,
     * session context), then the on-screen conversation.
     *
     * @param window how many of the newest on-screen messages to include.
     */
    public static String renderUserMessage(
        ChatSnapshot snapshot,
        String relationship,
        String background,
        List<LogEntry> history,
        int window
    ) {
        StringBuilder sb = new StringBuilder();
        if (!background.isBlank()) {
            sb.append("以下是背景与知识库（属于给定上下文，不是跑题）：\n")
                .append(background).append("\n\n");
        }
        if (!history.isEmpty()) {
            sb.append("更早的聊天记录（越靠下越新）：\n");
            for (LogEntry it : history) {
                sb.append(sideName(it.side())).append('：').append(it.text()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("关系：").append(relationship.isBlank() ? "未说明" : relationship).append('\n');
        sb.append("\n当前屏幕上的最近对话（越靠下越新）：\n");
        List<Msg> msgs = ChatSnapshot.takeLast(snapshot.messages(), Math.max(window, 1));
        for (Msg m : msgs) {
            sb.append(sideName(m.side())).append('：').append(m.text()).append('\n');
        }
        if (!msgs.isEmpty()) {
            sb.append("\n最新一条是").append(sideName(msgs.get(msgs.size() - 1).side())).append("发的。");
        }
        return sb.toString();
    }

    private static String sideName(String side) {
        return "me".equals(side) ? "我" : "对方";
    }
}
