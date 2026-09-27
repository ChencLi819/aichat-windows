package com.jev.probe.desktop.model;

import java.util.List;

/**
 * The model's judgment result for one snapshot, plus the ranked candidate replies.
 *
 * [reasoning] is the model's thinking pass when the route streams with thinking
 * on — shown collapsibly on the panel so a long wait is inspectable.
 */
public final class Analysis {

    private final Choice trueIntent;
    private final Score dangerLevel;
    private final Choice sheNeeds;
    private final Double shouldReplyNow;
    private final Choice bestAction;
    private final Double tensionResolved;
    private final Double literalQuestion;
    private final List<RankedReply> rankedReplies;
    private final long latencyMs;
    private final String error;
    private final String reasoning;

    public Analysis(
        Choice trueIntent,
        Score dangerLevel,
        Choice sheNeeds,
        Double shouldReplyNow,
        Choice bestAction,
        Double tensionResolved,
        Double literalQuestion,
        List<RankedReply> rankedReplies,
        long latencyMs
    ) {
        this(trueIntent, dangerLevel, sheNeeds, shouldReplyNow, bestAction, tensionResolved,
            literalQuestion, rankedReplies, latencyMs, null, null);
    }

    public Analysis(
        Choice trueIntent,
        Score dangerLevel,
        Choice sheNeeds,
        Double shouldReplyNow,
        Choice bestAction,
        Double tensionResolved,
        Double literalQuestion,
        List<RankedReply> rankedReplies,
        long latencyMs,
        String error,
        String reasoning
    ) {
        this.trueIntent = trueIntent;
        this.dangerLevel = dangerLevel;
        this.sheNeeds = sheNeeds;
        this.shouldReplyNow = shouldReplyNow;
        this.bestAction = bestAction;
        this.tensionResolved = tensionResolved;
        this.literalQuestion = literalQuestion;
        this.rankedReplies = rankedReplies;
        this.latencyMs = latencyMs;
        this.error = error;
        this.reasoning = reasoning;
    }

    public Choice trueIntent() {
        return trueIntent;
    }

    public Score dangerLevel() {
        return dangerLevel;
    }

    public Choice sheNeeds() {
        return sheNeeds;
    }

    public Double shouldReplyNow() {
        return shouldReplyNow;
    }

    public Choice bestAction() {
        return bestAction;
    }

    public Double tensionResolved() {
        return tensionResolved;
    }

    public Double literalQuestion() {
        return literalQuestion;
    }

    public List<RankedReply> rankedReplies() {
        return rankedReplies;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public String error() {
        return error;
    }

    public String reasoning() {
        return reasoning;
    }

    /** A copy with the ranked replies replaced — the `copy(rankedReplies = …)` shape. */
    public Analysis withRankedReplies(List<RankedReply> ranked) {
        return new Analysis(trueIntent, dangerLevel, sheNeeds, shouldReplyNow, bestAction,
            tensionResolved, literalQuestion, ranked, latencyMs, error, reasoning);
    }
}
