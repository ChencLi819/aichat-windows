package com.jev.probe.desktop.llm;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ChatContext;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.RankedReply;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Thin facade over the judgment and reply clients so callers keep one entry
 * point. Both routes are plain OpenAI-compatible chat calls — nothing here
 * talks to a Jev endpoint.
 *
 * Construct with [Config] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
public class ChatAiClient {

    private final LlmJudgeClient judgeClient;
    private final ReplyClient replyClient;

    public ChatAiClient(Config prefs) {
        this.judgeClient = new LlmJudgeClient(prefs);
        this.replyClient = new ReplyClient(prefs);
    }

    public Analysis judge(ChatSnapshot snapshot, String relationship) {
        return judge(snapshot, relationship, null, null);
    }

    public Analysis judge(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        return judge(snapshot, relationship, ctx, null);
    }

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    public Analysis judge(
        ChatSnapshot snapshot,
        String relationship,
        ChatContext ctx,
        BiConsumer<String, String> onDelta
    ) {
        return judgeClient.judge(snapshot, relationship, ctx, onDelta);
    }

    public List<RankedReply> draftAndRank(ChatSnapshot snapshot, String relationship) {
        return draftAndRank(snapshot, relationship, null);
    }

    /**
     * 3 candidates, best first.
     *
     * The drafting call now scores its own candidates, so this is ONE request
     * where it used to be two (draft, then a separate ranking call). See
     * [ReplyClient.draft].
     */
    public List<RankedReply> draftAndRank(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        return replyClient.draft(snapshot, relationship, ctx);
    }

    public Analysis analyze(ChatSnapshot snapshot, String relationship) {
        return analyze(snapshot, relationship, null);
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    public Analysis analyze(ChatSnapshot snapshot, String relationship, ChatContext ctx) {
        Analysis a = judge(snapshot, relationship, ctx);
        if (a.error() != null) return a;
        List<RankedReply> ranked;
        try {
            ranked = draftAndRank(snapshot, relationship, ctx);
        } catch (Exception e) {
            ranked = List.of();
        }
        return a.withRankedReplies(ranked);
    }

    /** Free-form "问 AI" answer plus its thinking pass (null when the model
     *  did not emit one). Never throws; a failed ask answers in [answer]. */
    public record AskResult(String answer, String reasoning) {
    }

    /** Free-form "问 AI" about this conversation. Never throws; see [LlmJudgeClient.ask]. */
    public AskResult ask(
        String question,
        ChatSnapshot snapshot,
        String relationship,
        ChatContext ctx,
        BiConsumer<String, String> onDelta
    ) {
        return judgeClient.ask(question, snapshot, relationship, ctx, onDelta);
    }
}
