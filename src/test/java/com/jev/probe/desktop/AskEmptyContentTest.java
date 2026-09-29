package com.jev.probe.desktop;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.llm.ChatAiClient;
import com.jev.probe.desktop.llm.LlmJudgeClient;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.net.ApiException;
import com.jev.probe.desktop.net.Route;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「问 AI 返回内容为空」的回归测试（本地假服务，不联网）。
 *
 * <p>复现的是实测过的真实故障：思考模型在同一次响应里既输出思考又输出正文，而
 * 输出上限把两者一起算。实测（百炼 deepseek-v4.1-flash + 30 条对话）思考用掉
 * 3364 tokens、`finish_reason=length`、正文长度 0 —— 用户看到的就是「返回内容为空」。
 *
 * <p>这里用假服务端把两种响应做出来，断言修复后的行为：
 * <ol>
 *   <li>上限低于阈值时，第一次请求就不带思考（不再白等一次思考）；</li>
 *   <li>上限足够时先按原样请求，遇到「只有思考没有正文」会关掉思考重试一次，
 *       并且最终一定带回正文；</li>
 *   <li>空正文的错误不会被 HTTP 层重发（避免把几十秒的思考再付一遍）。</li>
 * </ol>
 */
class AskEmptyContentTest {

    private HttpServer server;
    private String base;
    private final List<JSONObject> requests = new ArrayList<>();
    private final AtomicInteger n = new AtomicInteger();

    /** 第 1 次请求返回“只有思考、被上限截断”，第 2 次返回正文。 */
    private final boolean[] sawReasoningOnly = {false};

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject body = new JSONObject(raw);
            synchronized (requests) {
                requests.add(body);
            }
            n.incrementAndGet();
            // 开思考的请求 → 思考一大段然后被上限截断（正文为空）；
            // 关思考的请求 → 正常给正文。这就是线上模型的表现。
            boolean reasoningOnly = body.optBoolean("enable_thinking", false);
            if (reasoningOnly) sawReasoningOnly[0] = true;
            // 两条路由的“正常响应”不一样：问 AI 是自由文本，判断是那 7 题的 JSON。
            String system = "";
            org.json.JSONArray msgs = body.optJSONArray("messages");
            if (msgs != null && msgs.length() > 0 && msgs.optJSONObject(0) != null) {
                system = msgs.optJSONObject(0).optString("content");
            }
            boolean askStyle = system.contains("聊天参谋");
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                if (reasoningOnly) {
                    for (int k = 0; k < 20; k++) {
                        chunk(os, new JSONObject().put("delta",
                            new JSONObject().put("reasoning_content", "思考片段" + k + "；")));
                    }
                    chunk(os, new JSONObject()
                        .put("delta", new JSONObject())
                        .put("finish_reason", "length"));
                } else if (askStyle) {
                    chunk(os, new JSONObject().put("delta",
                        new JSONObject().put("content", "建议先道个歉，")));
                    chunk(os, new JSONObject().put("delta",
                        new JSONObject().put("content", "再说具体怎么改。")));
                    chunk(os, new JSONObject()
                        .put("delta", new JSONObject())
                        .put("finish_reason", "stop"));
                } else {
                    // 判断路由：一个最小可解析的 7 题 JSON
                    String json = "{\"literal_question\":0.0,"
                        + "\"true_intent\":{\"choice\":\"vent_anger\",\"confidence\":0.9},"
                        + "\"danger_level\":{\"score\":7,\"confidence\":0.7},"
                        + "\"she_needs\":{\"choice\":\"care\",\"confidence\":0.6},"
                        + "\"should_reply_now\":0.0,\"best_action\":{\"choice\":\"acknowledge\"},"
                        + "\"tension_resolved\":0.0}";
                    chunk(os, new JSONObject().put("delta",
                        new JSONObject().put("content", json)));
                    chunk(os, new JSONObject()
                        .put("delta", new JSONObject())
                        .put("finish_reason", "stop"));
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        // 地址里带上 dashscope.aliyuncs.com：这样应用才会按“百炼平台”发思考开关，
        // 假服务端因此能像真平台一样对“开/关思考”给出不同响应（全程不出网）。
        base = "http://127.0.0.1:" + server.getAddress().getPort()
            + "/dashscope.aliyuncs.com/compatible-mode/v1";
    }

    private static void chunk(OutputStream os, JSONObject deltaOrChoice) throws java.io.IOException {
        JSONObject o = new JSONObject().put("choices",
            new org.json.JSONArray().put(deltaOrChoice));
        os.write(("data: " + o + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Config config(Path dir, int maxOutput) {
        Config c = new Config(dir.resolve("config.json"));
        c.judgeBaseUrl(base);
        c.judgeModel("test-model");
        c.judgeKey("test-key");
        c.maxOutputTokens(maxOutput);
        c.proxyEnabled(false);
        return c;
    }

    private static ChatSnapshot conversation() {
        return new ChatSnapshot("测试会话", List.of(
            new Msg("other", "你怎么又忘了我说的事"),
            new Msg("me", "这两天太忙了，抱歉"),
            new Msg("other", "每次都是这个理由")));
    }

    @Test
    @DisplayName("上限低于思考阈值：只发一次请求，且直接关闭思考")
    void lowCapSkipsThinkingUpFront(@TempDir Path dir) {
        Config c = config(dir, 2048);
        ChatAiClient.AskResult res = new ChatAiClient(c)
            .ask("我该怎么回复？", conversation(), "伴侣", null, null);

        assertEquals(1, n.get(), "低上限时不该有第二次请求");
        assertFalse(requests.get(0).optBoolean("enable_thinking", true),
            "低上限时第一次请求就应关闭思考");
        assertTrue(res.answer().contains("建议先道个歉"), "答案应带回正文：" + res.answer());
    }

    @Test
    @DisplayName("只有思考没有正文：自动关掉思考重试一次，最终一定带回正文")
    void reasoningOnlyResponseIsRetriedWithoutThinking(@TempDir Path dir) {
        Config c = config(dir, 8192);
        ChatAiClient.AskResult res = new ChatAiClient(c)
            .ask("我该怎么回复？", conversation(), "伴侣", null, null);

        assertEquals(2, n.get(), "应为“原样一次 + 关思考一次”共两次请求");
        assertTrue(requests.get(0).optBoolean("enable_thinking", false),
            "上限足够时第一次应保留思考");
        assertFalse(requests.get(1).optBoolean("enable_thinking", true),
            "重试必须关掉思考");
        assertTrue(res.answer().contains("建议先道个歉"), "重试后应有正文：" + res.answer());
        assertNotNull(res.reasoning(), "第一次的思考过程仍应带回来给用户看");
    }

    @Test
    @DisplayName("空正文错误不可重发：HTTP 层只请求一次（不重复付思考时间）")
    void emptyContentIsNotRetriedByTransport(@TempDir Path dir) {
        Config c = config(dir, 2048);
        // 直接把「只有思考、被截断」的响应喂给 HTTP 层：应抛出可读错误且只请求一次
        try {
            com.jev.probe.desktop.net.HttpJson.postChatFull(
                c.judgeEndpoint(), c.judgeKey(),
                new JSONObject().put("model", "test-model")
                    .put("messages", new org.json.JSONArray())
                    .put("enable_thinking", true),
                Route.JUDGE, null, null);
            throw new AssertionError("空正文应当抛错");
        } catch (ApiException e) {
            assertFalse(e.retryable(), "空正文属于内容问题，不该重发");
            assertTrue(e.getMessage().contains("思考"), "错误里应说明是思考吃满了上限：" + e.getMessage());
        }
        assertEquals(1, n.get(), "HTTP 层不得重发");
    }

    @Test
    @DisplayName("判断接口遇到空响应：自动关掉思考重试一次，最终返回可解析的 JSON 分析")
    void judgeRecoversFromEmptyResponse(@TempDir Path dir) {
        Config c = config(dir, 8192);
        LlmJudgeClient client = new LlmJudgeClient(c);
        ChatSnapshot snap = new ChatSnapshot("测试会话", List.of(
            new Msg("other", "你怎么又忘了我说的事"),
            new Msg("me", "这两天太忙了，抱歉")));
        com.jev.probe.desktop.model.Analysis a = client.judge(snap, "伴侣", null, null);

        assertEquals(2, n.get(), "应为“原样一次 + 关思考一次”共两次请求");
        assertFalse(a.error() != null && a.error().contains("响应里没有消息内容"),
            "判断不应把空响应报给用户：" + a.error());
        assertTrue(a.trueIntent() != null, "应解析出意图判断：" + a.error());
        assertEquals("vent_anger", a.trueIntent().choice());
    }
}