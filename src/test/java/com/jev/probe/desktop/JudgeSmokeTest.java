package com.jev.probe.desktop;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.llm.LlmJudgeClient;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 判断路由（7 题分析）的活体冒烟测试：真实接口一次完整判断。
 *
 * 覆盖用户报的「判断接口 HTTP 200：响应里没有消息内容」——这条链路上不能出现
 * error（含空正文），并要能解析出意图。未配置接口时自动跳过。
 *
 *   .\gradlew.bat test --tests JudgeSmokeTest --console=plain -i
 */
class JudgeSmokeTest {

    @Test
    void judgeReturnsAnswersWithoutError() {
        Config config = new Config();
        assumeTrue(config.hasKey(), "未配置判断接口，跳过");
        ChatSnapshot snap = new ChatSnapshot("宝宝", List.of(
            new Msg("other", "你怎么又忘了我说的事"),
            new Msg("me", "这两天太忙了，抱歉"),
            new Msg("other", "每次都是这个理由"),
            new Msg("me", "我明天一定处理"),
            new Msg("other", "算了不用了"),
            new Msg("me", "别这样，我真的会做"),
            new Msg("other", "你说的话我还能信吗"),
            new Msg("me", "给我一次机会"),
            new Msg("other", "我累了"),
            new Msg("me", "那我们冷静一下明天聊")));

        long t0 = System.currentTimeMillis();
        Analysis a = new LlmJudgeClient(config).judge(snap, "伴侣", null, null);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("=== judge " + ms + "ms error=" + a.error()
            + " intent=" + (a.trueIntent() == null ? "-" : a.trueIntent().choice())
            + " danger=" + (a.dangerLevel() == null ? "-" : a.dangerLevel().score())
            + " action=" + (a.bestAction() == null ? "-" : a.bestAction().choice())
            + " reasoning=" + (a.reasoning() == null ? 0 : a.reasoning().length()) + "字 ===");

        assertNull(a.error(), "判断不应报错：" + a.error());
        assertTrue(a.trueIntent() != null, "应解析出意图判断");
    }
}