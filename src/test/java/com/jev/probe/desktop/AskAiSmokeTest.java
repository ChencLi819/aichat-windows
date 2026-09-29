package com.jev.probe.desktop;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.llm.ChatAiClient;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「问 AI」的活体冒烟测试：真实判断接口 -> 自由问答答案。
 *
 * <p>五种查询类型各来一次（简单问答 / 复杂推理 / 多轮上下文 / 情感安慰 / 长对话
 * 详细方案——最后一条正是「返回内容为空」的原复现条件：30 条对话 + 要求完整方案，
 * 修复前思考会用满 2048 上限、正文为空）。
 *
 * <p>与 VisionSmokeTest 一样，未配置接口时自动跳过，保证任何机器上测试套件都是绿的。
 * 运行：
 *   .\gradlew.bat test --tests AskAiSmokeTest --console=plain -i
 */
class AskAiSmokeTest {

    /** 一条较长的对话，用来压出“思考吃满上限”的老故障。 */
    private static final String[][] LONG_THREAD = {
        {"other", "你怎么又忘了我说的事"}, {"me", "这两天太忙了，抱歉"},
        {"other", "每次都是这个理由"}, {"me", "我明天一定处理"},
        {"other", "算了不用了"}, {"me", "别这样，我真的会做"},
        {"other", "你说的话我还能信吗"}, {"me", "给我一次机会"},
        {"other", "我累了"}, {"me", "那我们冷静一下明天聊"},
        {"other", "随你"}, {"me", "我先把东西发你"},
        {"other", "不用了"}, {"me", "已经发了你看下"},
        {"other", "看到了"}, {"me", "哪里不满意我可以改"},
        {"other", "不是改不改的问题"}, {"me", "那是什么问题你说"},
        {"other", "算了不说了"}, {"me", "我不想这样结束"},
        {"other", "我也没说结束"}, {"me", "那我们好好谈一次"},
        {"other", "周末吧"}, {"me", "好，周六下午我来找你"},
        {"other", "嗯"}, {"me", "早点休息"},
        {"other", "你也是"}, {"me", "晚安"},
        {"other", "嗯"}, {"me", "（等她回复）"},
    };

    private record Case(String name, String question, List<Msg> messages) {
    }

    private static List<Case> cases() {
        List<Msg> shortThread = List.of(
            new Msg("other", "明天一起去爬山吗？"),
            new Msg("me", "看情况吧，最近有点忙"));
        List<Case> out = new ArrayList<>();
        out.add(new Case("简单问答", "她这句话是什么意思？", shortThread));
        out.add(new Case("复杂推理",
            "结合整段对话分析对方的真实诉求，给出我接下来三步怎么说的方案，并解释每一步的理由。",
            longThread()));
        out.add(new Case("多轮上下文", "前面我答应过什么、又没做到什么？我该怎么补？", longThread()));
        out.add(new Case("情感安慰", "我想让她开心一点，这句话怎么接？", shortThread));
        out.add(new Case("长对话详细方案",
            "请详细分析这段对话里对方的真实情绪和诉求，给出我接下来应该怎么说的完整方案，"
                + "并解释每一步的原因。", longThread()));
        return out;
    }

    private static List<Msg> longThread() {
        List<Msg> out = new ArrayList<>();
        for (String[] m : LONG_THREAD) out.add(new Msg(m[0], m[1]));
        return out;
    }

    @Test
    void askAnswersFiveQueryTypes() {
        Config config = new Config();
        assumeTrue(config.hasKey(), "未配置判断接口，跳过");
        ChatAiClient ai = new ChatAiClient(config);

        int ok = 0;
        List<String> problems = new ArrayList<>();
        for (Case c : cases()) {
            long t0 = System.currentTimeMillis();
            ChatAiClient.AskResult res = ai.ask(
                c.question(), new ChatSnapshot("冒烟测试-" + c.name(), c.messages()), "伴侣", null, null);
            long ms = System.currentTimeMillis() - t0;
            String answer = res.answer() == null ? "" : res.answer();
            System.out.println("=== [" + c.name() + "] " + ms + "ms  len=" + answer.length()
                + " reasoning=" + (res.reasoning() == null ? 0 : res.reasoning().length())
                + " ===");
            System.out.println(answer.length() > 220 ? answer.substring(0, 220) + "…" : answer);
            if (!answer.isBlank() && !answer.startsWith("问 AI 失败")) {
                ok++;
            } else {
                problems.add(c.name() + " -> " + answer);
            }
        }
        System.out.println("=== 通过 " + ok + "/" + cases().size() + " ===");
        assertTrue(problems.isEmpty(), "以下查询没有拿到有效内容：" + problems);
        assertFalse(ok < cases().size());
    }
}