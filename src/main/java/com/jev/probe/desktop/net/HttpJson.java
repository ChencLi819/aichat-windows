package com.jev.probe.desktop.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Shared POST-JSON helper: UTF-8 body, exponential backoff on 429/529, no retry
 * on other 4xx, and every failure normalized to [ApiException]. Keys are passed
 * in per call and never logged.
 */
public final class HttpJson {

    private HttpJson() {
    }

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    private static final int MAX_ATTEMPTS = 3;

    /**
     * @param route one of [Route], used only for error text.
     * @param extraHeaders additional request headers (e.g. OpenRouter attribution).
     */
    public static JSONObject post(String url, String key, JSONObject body, String route) {
        return post(url, key, body, route, Collections.emptyMap(), null);
    }

    public static JSONObject post(
        String url,
        String key,
        JSONObject body,
        String route,
        Map<String, String> extraHeaders,
        Proxy proxy
    ) {
        int attempt = 0;
        ApiException last = null;
        while (attempt < MAX_ATTEMPTS) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) (proxy != null ? u.openConnection(proxy) : u.openConnection());
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(40000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                if (code == 429 || code == 529) {
                    last = new ApiException(route, code, "服务繁忙，已重试");
                    attempt++;
                    if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
                    continue;
                }
                // Branch on the status code FIRST. Reading the body must never be
                // able to lose it: errorStream is null on some failures (and on
                // some OEM stacks), and a read can throw on a truncated response —
                // either way this used to surface as a transport failure with no
                // status, which then got retried even for a 401.
                if (code < 200 || code > 299) {
                    String errText = readBody(conn.getErrorStream());
                    throw new ApiException(route, code, errText.isBlank() ? "（响应体为空）" : errText);
                }
                String text = readBody(conn.getInputStream());
                if (text.isBlank()) throw new ApiException(route, code, "响应体为空");
                return new JSONObject(text);
            } catch (ApiException e) {
                if (e.status() != null && e.status() >= 400 && e.status() <= 499) throw e;  // client error: no retry
                last = e;
                attempt++;
                if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
            } catch (Exception e) {
                last = new ApiException(route, null, describe(e));
                attempt++;
                if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new ApiException(route, null, "请求失败");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * (1L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Body text, or "" — a null stream or a read failure never costs us the status code. */
    private static String readBody(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** OpenRouter wants attribution headers; other hosts reject unknown ones politely. */
    public static Map<String, String> headersFor(String url) {
        if (url.toLowerCase(Locale.ROOT).contains("openrouter.ai")) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("HTTP-Referer", "https://jev-assistant.local");
            m.put("X-Title", "Jev Assistant");
            return m;
        }
        return Collections.emptyMap();
    }

    /**
     * Per-platform thinking switches, per route — the two platforms spell the
     * toggle differently (docs checked 2026-09):
     *
     * - Bailian (DeepSeek V4 / Qwen hybrids): `"enable_thinking": true|false`.
     * - DeepSeek official (V4 family): `"thinking": {"type": "enabled|disabled"}`
     *   (defaults to enabled; reasoning_effort defaults to high).
     *
     * The judge route THINKS (accuracy of the 7 answers is the product); the
     * reply route does NOT (three ≤40-char lines — measured ~15s → ~1-2s).
     * Params are only ever sent to the platform that defines them.
     *
     * <p>The ask route is the third case: it thinks only while the caller's
     * output budget can hold a thinking pass plus an answer, because on a
     * reasoning model the cap counts BOTH — measured 2026-09-29 on a 30-message
     * conversation: 3364 reasoning tokens and an EMPTY answer under the default
     * 2048 cap (`finish_reason=length`). See [LlmJudgeClient.ask].
     */
    public static void applyJudgeRouteOptions(JSONObject body, String url) {
        applyJudgeRouteOptions(body, url, true);
    }

    public static void applyJudgeRouteOptions(JSONObject body, String url, boolean thinking) {
        if (isBailian(url)) {
            body.put("enable_thinking", thinking);
        } else if (isDeepSeek(url)) {
            body.put("thinking", new JSONObject().put("type", thinking ? "enabled" : "disabled"));
        }
    }

    public static void applyReplyRouteOptions(JSONObject body, String url) {
        if (isBailian(url)) {
            body.put("enable_thinking", false);
        } else if (isDeepSeek(url)) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
    }

    private static boolean isBailian(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("dashscope.aliyuncs.com") || u.contains("maas.aliyuncs.com");
    }

    private static boolean isDeepSeek(String url) {
        return url.toLowerCase(Locale.ROOT).contains("api.deepseek.com");
    }

    /**
     * Streaming chat-completions call: returns the full assistant message.
     *
     * With thinking on, the judgment route thinks for tens of seconds on real
     * conversations. Without streaming that whole wait is ONE silent read, and
     * the 40s read timeout killed the request mid-think (the reported "判断接口
     * 测试无响应 / AI分析失败"). With `stream:true` tokens flow continuously, so
     * the read timeout only bounds the gap between chunks — thinking time never
     * trips it. Providers that ignore `stream` and answer plain JSON are handled
     * too (see [parseChatResponse]).
     */
    public static String postChat(String url, String key, JSONObject body, String route) {
        return postChat(url, key, body, route, null);
    }

    public static String postChat(String url, String key, JSONObject body, String route, Proxy proxy) {
        return postChatFull(url, key, body, route, proxy, null).content();
    }

    /** The assistant message plus its thinking pass, when the model emitted one.
     *
     * @param finishReason the provider's stop reason (`stop` / `length` / …), or
     *        null when the transport never reported one. `length` together with an
     *        empty content is the "thinking ate the whole budget" signature — see
     *        [LlmJudgeClient.ask].
     */
    public record ChatStream(String content, String reasoning, String finishReason) {

        public ChatStream(String content, String reasoning) {
            this(content, reasoning, null);
        }
    }

    /**
     * Like [postChat], but streams INCREMENTALLY: [onDelta] fires as chunks
     * arrive — `"reasoning"` for the model's thinking pass (`reasoning_content`,
     * the field DeepSeek and Bailian both use; `reasoning` as the OpenRouter
     * spelling), `"content"` for the answer — so the UI can show the thinking
     * live instead of a black-box wait. The read timeout only bounds the gap
     * between chunks, so a long think never trips it.
     *
     * A stream that BREAKS mid-answer with content already in hand returns that
     * partial content instead of restarting: a retry re-pays the whole thinking
     * pass (the reported slow rounds), and a truncated answer fails visibly in
     * the parser either way.
     */
    public static ChatStream postChatFull(
        String url,
        String key,
        JSONObject body,
        String route,
        Proxy proxy,
        BiConsumer<String, String> onDelta
    ) {
        body.put("stream", true);
        HttpURLConnection conn = null;
        ApiException last = null;
        int attempt = 0;
        while (attempt < 2) {
            StringBuilder reasoning = new StringBuilder();
            StringBuilder content = new StringBuilder();
            String[] finish = new String[1];
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) (proxy != null ? u.openConnection(proxy) : u.openConnection());
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "text/event-stream");
                for (Map.Entry<String, String> e : headersFor(url).entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code < 200 || code > 299) {
                    String err = readBody(conn.getErrorStream());
                    throw new ApiException(route, code, err.isBlank() ? "（响应体为空）" : err);
                }
                boolean sse = readStream(conn.getInputStream(), reasoning, content, finish, onDelta);
                ChatStream out = sse ? splitInlineThink(content, finish[0])
                    : parsePlainChatResponse(content.toString());
                if (out.content().isBlank()) {
                    // 内容为空是“答案本身的问题”，不是传输抖动：重发一次只会再花一遍
                    // 思考时间（实测每次思考几十秒），所以标为不可重试，交给调用方决定
                    // 换一种问法（见 LlmJudgeClient.ask 的降级重试）。
                    throw new ApiException(route, code, emptyContentReason(out), false);
                }
                return out;
            } catch (ApiException e) {
                if (!e.retryable()) throw e;
                if (e.status() != null && e.status() >= 400 && e.status() <= 499) throw e;
                last = e;
                attempt++;
            } catch (IOException e) {
                // Broken mid-stream with something already said: deliver it.
                // Restarting would re-pay the entire thinking pass.
                if (!content.toString().isBlank()) {
                    LOGGER.warning("stream interrupted after " + content.length() + " chars; returning partial");
                    return splitInlineThink(content, finish[0]);
                }
                last = new ApiException(route, null, describe(e));
                attempt++;
            } catch (Exception e) {
                last = new ApiException(route, null, describe(e));
                attempt++;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new ApiException(route, null, "请求失败");
    }

    /** 空正文的人话解释：能区分“答案被输出上限截断”与“模型什么也没说”。 */
    private static String emptyContentReason(ChatStream out) {
        if ("length".equalsIgnoreCase(out.finishReason())) {
            return "模型只输出了思考过程就用满了输出上限（正文为空）";
        }
        if (out.reasoning() != null && !out.reasoning().isBlank()) {
            return "模型只返回了思考过程，没有给出正文";
        }
        return "响应里没有消息内容";
    }

    /**
     * Read the response incrementally, line by line, feeding builders and
     * [onDelta] as chunks land. Returns true when the body was SSE
     * (`data:` lines); false for a plain (non-streamed) JSON completion.
     */
    private static boolean readStream(
        InputStream stream,
        StringBuilder reasoning,
        StringBuilder content,
        String[] finish,
        BiConsumer<String, String> onDelta
    ) throws IOException {
        boolean sse = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                String t = line.trim();
                if (t.startsWith("data:")) {
                    sse = true;
                    String payload = t.substring("data:".length()).trim();
                    if (!payload.isEmpty() && !payload.equals("[DONE]")) {
                        try {
                            JSONArray choices = new JSONObject(payload).optJSONArray("choices");
                            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                            // finish_reason 只在最后一个 chunk 出现，但要区分
                            // “正常结束”与“被输出上限截断”，见 emptyContentReason。
                            if (choice != null) {
                                String fr = choice.optString("finish_reason");
                                if (!fr.isEmpty()) finish[0] = fr;
                            }
                            JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                            if (delta != null) {
                                String think = delta.optString("reasoning_content");
                                if (think.isEmpty()) think = delta.optString("reasoning");
                                if (!think.isEmpty()) {
                                    reasoning.append(think);
                                    if (onDelta != null) onDelta.accept("reasoning", think);
                                }
                                String piece = delta.optString("content");
                                if (!piece.isEmpty()) {
                                    content.append(piece);
                                    if (onDelta != null) onDelta.accept("content", piece);
                                }
                            }
                        } catch (Exception ignored) {
                            // runCatching — a malformed chunk is skipped, not fatal.
                        }
                    }
                } else if (!t.isEmpty()) {
                    // Not SSE — keep the raw text for the plain-JSON fallback.
                    content.append(line).append('\n');
                }
                line = reader.readLine();
            }
        }
        return sse;
    }

    /** Some models inline their thinking as `<think>…</think>` inside content
     *  instead of a separate field — lift it out at completion time. */
    private static ChatStream splitInlineThink(StringBuilder content, String finishReason) {
        String s = content.toString().trim();
        if (s.startsWith("<think>")) {
            int end = s.indexOf("</think>");
            if (end > 0) {
                String think = s.substring("<think>".length(), end).trim();
                return new ChatStream(s.substring(end + "</think>".length()).trim(), think.isBlank() ? null : think, finishReason);
            }
        }
        return new ChatStream(s, null, finishReason);
    }

    /** Plain (provider ignored `stream`) completion object → content + reasoning. */
    private static ChatStream parsePlainChatResponse(String contentRaw) {
        JSONObject o = null;
        try {
            o = new JSONObject(contentRaw);
        } catch (Exception ignored) {
        }
        JSONObject choice = null;
        JSONObject msg = null;
        if (o != null) {
            JSONArray choices = o.optJSONArray("choices");
            choice = choices == null ? null : choices.optJSONObject(0);
            msg = choice == null ? null : choice.optJSONObject("message");
        }
        String think = msg == null ? null : msg.optString("reasoning_content");
        if (think != null && think.isEmpty()) think = msg.optString("reasoning");
        String content = msg == null ? "" : msg.optString("content");
        String finish = choice == null ? null : choice.optString("finish_reason");
        if (finish != null && finish.isEmpty()) finish = null;
        return new ChatStream(content, (think == null || think.isBlank()) ? null : think, finish);
    }

    /** Human-readable transport failures (no key material ever appears here). */
    private static String describe(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (m.contains("timed out") || m.toLowerCase(Locale.ROOT).contains("timeout")) {
            return "网络超时，请检查连接";
        }
        if (m.contains("Unable to resolve host")) {
            return "域名解析失败，地址填错或无网络";
        }
        if (m.contains("Failed to connect") || m.contains("ECONNREFUSED")) {
            return "无法连接该地址";
        }
        if (m.contains("CertPath") || m.contains("SSL")) {
            return "HTTPS 证书校验失败";
        }
        return m;
    }
}
