package com.jev.probe.desktop.capture;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.net.ApiException;
import com.jev.probe.desktop.net.HttpJson;
import com.jev.probe.desktop.net.Route;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads a chat screenshot into messages — the desktop capture layer's only
 * reader, because WeChat 4.x exposes nothing through UI Automation (measured:
 * two nodes for the whole window, see tools/uia-probe.ps1). On Windows the
 * screenshot itself is never blocked, so this is the path that works.
 *
 * A vision model does the reading rather than a local OCR engine: chat text is
 * Chinese, small, anti-aliased and often on coloured bubbles, which is exactly
 * where a classical OCR engine produces the garbage that would then poison the
 * judgment. The model also answers the question a local OCR cannot — who said
 * it — and can read the conversation's name off the window header, which the
 * desktop has no other way to learn.
 *
 * Wire format, inherited from the Android client and still load-bearing:
 * - JPEG, not PNG (a PNG screenshot is several times larger).
 * - The image part goes BEFORE the text part — DashScope's compatible mode
 *   rejects the other order.
 */
public final class VisionReader {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /** What one screenshot yielded.
     *
     * @param isChat false when the region does not hold an open conversation
     *        (a login screen, the conversation list, settings…) — then
     *        [messages] is empty and [reason] says what was actually there.
     * @param title the conversation name off the window header, blank when the
     *        header was not part of the selected region (or unreadable). */
    public record Dialog(boolean isChat, String reason, String title, List<Msg> messages, String raw) {
    }

    /**
     * The transcription contract.
     *
     * JSON rather than one-line-per-message on purpose: a WeChat message can
     * span several lines, and a line-based format silently shreds it into
     * separate messages, which then reads as a conversation that never
     * happened.
     *
     * `is_chat` exists because of a measured failure: pointed at WeChat's login
     * screen the model happily transcribed the dialog box ("提示 / 你已退出微信
     * / 我知道了") as if it were a conversation, and the judgment then analysed
     * that. Anything that is not an open conversation must be reported, not
     * guessed at.
     */
    private static final String PROMPT = """
            你是聊天截图转写助手。图中是一个聊天软件窗口的截图。
            请只输出一个 JSON 对象，结构如下：
            {"is_chat":true,"reason":"","title":"聊天对象名称","messages":[{"side":"me","text":"正文"}]}
            规则：
            - is_chat：图中是否是一个**打开的聊天会话**（能看到双方往来的消息气泡）。如果只是登录界面、会话列表、通讯录、设置页、朋友圈等，设为 false
            - reason：is_chat 为 false 时用一句话说明实际看到了什么；否则留空
            - messages 按气泡从上到下的顺序排列，一条消息一个元素
            - side 只能是 "me" 或 "other"。**判断方法见下面「怎么判断谁说的」，必须严格按位置判断**
            - 一条消息即使跨多行也合并成一个元素，不要拆成多条
            - title 是窗口顶部显示的聊天对象名称；截图中看不到就填空字符串
            - 忽略时间戳、日期分隔线、输入框、"正在输入"等非消息内容
            - 忽略**居中**的灰色小字系统提示（"以下为新消息""你已添加了…""对方撤回了一条消息"等）：
              它不属于任何一方，不要塞进 messages，也不要靠猜把它分给某一侧
            - 文字必须和它所在的气泡框（box）**配对**读取：先看清一个 box 的范围，再读这个 box 里的文字。
              不要把 A 气泡的框和 B 气泡的文字拼成一条消息
            - 只输出 JSON，不要解释，不要 markdown 代码块

            怎么判断谁说的（这是最容易出错的地方，请逐条严格照做）：
            1. 只看**水平位置**：把图片沿左右方向一分为二。
            2. 气泡（以及它旁边的头像）贴在**右半边** → side = "me"（自己发的）。
            3. 气泡（以及它旁边的头像）贴在**左半边** → side = "other"（对方发的）。
            4. 辅助特征：自己的气泡通常有颜色（绿色/蓝色底），对方的气泡通常是白色或浅灰底。
               但如果颜色和位置冲突，**一律以位置为准**。
            5. 判断依据是气泡和头像**画在图片的哪一边**，与消息内容无关。
               不要因为某句话像是在提问、像是在指责、或像是别人对你说的，就把它判成 "other"；
               也不要因为某句话是你可能说的话，就把它判成 "me"。内容一律不参与判断。
            6. 逐条单独判断，不要复制上一条的 side，也不要把整段对话判成同一侧。
            7. 左边和右边都出现过气泡时，两种 side 都应该出现。
            8. 判定的基准是**气泡连同它旁边头像**这整个块的水平中心，不是文字的第一行、
               也不是气泡的某一个角；文字与 box 要对得上再判边。
            9. 气泡很宽、跨过中线时，看它**贴着哪一侧的画面边缘**：右缘贴着右侧 → "me"，
               左缘贴着左侧 → "other"。不要仅仅因为它跨过中线就判给对面。
            10. 群聊里消息上方可能带**发言人昵称**，text 只写正文，不要把昵称当前缀带上。
            """;

    private final Config config;

    public VisionReader(Config config) {
        this.config = config;
    }

    /** True when a vision route is configured well enough to try. */
    public boolean isConfigured() {
        return config.visionEndpoint() != null && !config.visionEndpoint().isBlank()
                && config.visionModel() != null && !config.visionModel().isBlank()
                && config.effectiveVisionKey() != null && !config.effectiveVisionKey().isBlank();
    }

    /**
     * Send one screenshot and get the conversation back.
     *
     * @throws ApiException when the route fails — the caller shows the message.
     */
    public Dialog read(BufferedImage image) throws ApiException {
        if (!isConfigured()) {
            throw new ApiException(Route.VISION, null,
                    "未配置视觉接口（需要地址、模型和密钥）。在设置里填，或设环境变量 AICHAT_VISION_KEY");
        }
        String url = config.visionEndpoint();
        String jpeg = ScreenGrabber.encodeJpegBase64(image, 0.85f);
        if (jpeg.isEmpty()) {
            throw new ApiException(Route.VISION, null, "截图编码失败");
        }
        // Image first, then text: DashScope compatible-mode requires this order.
        JSONArray content = new JSONArray()
                .put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:image/jpeg;base64," + jpeg)))
                .put(new JSONObject().put("type", "text").put("text", PROMPT));
        JSONArray messages = new JSONArray().put(
                new JSONObject().put("role", "user").put("content", content));
        JSONObject body = new JSONObject()
                .put("model", config.visionModel())
                .put("messages", messages)
                .put("temperature", 0.0);

        JSONObject resp = HttpJson.post(url, config.effectiveVisionKey(), body, Route.VISION,
                HttpJson.headersFor(url), config.proxy());
        String text = resp.optJSONArray("choices") == null ? ""
                : resp.optJSONArray("choices").optJSONObject(0)
                        .optJSONObject("message").optString("content");
        if (text == null || text.isBlank()) {
            throw new ApiException(Route.VISION, null, "视觉接口没有返回内容");
        }
        Dialog d = parse(text);
        if (!d.isChat()) {
            // The model is telling us the region is not a conversation. Say so
            // instead of analysing whatever it managed to transcribe.
            throw new ApiException(Route.VISION, null,
                    "截取的区域里不是一个打开的聊天会话"
                            + (d.reason().isBlank() ? "" : "（看到的是：" + d.reason() + "）"));
        }
        if (d.messages().isEmpty()) {
            LOG.log(Level.WARNING, "视觉接口未解析出任何消息，原文前 200 字：" + head(text));
            throw new ApiException(Route.VISION, null,
                    "视觉接口没能读出聊天内容（返回： " + head(text) + "）");
        }
        return d;
    }

    /**
     * One read for the settings connectivity test. Unlike [read] this never
     * throws: the caller has nowhere to show an exception, so the failure has to
     * come back as the report itself.
     *
     * @return a one-line 「成功 …」/「失败 …」 report.
     */
    public String ping(BufferedImage image) {
        long start = System.currentTimeMillis();
        try {
            Dialog d = read(image);
            long ms = System.currentTimeMillis() - start;
            String title = d.title().isBlank() ? "（未读到标题）" : d.title();
            return "成功 " + ms + "ms · 标题=" + title + " · " + d.messages().size() + " 条消息";
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            return "失败（" + ms + "ms）："
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * The model's JSON → dialog. Tolerant about the packaging (models like to
     * wrap JSON in prose or a ```json fence) and about the side spelling, but
     * strict about one thing: a message with no text is dropped rather than
     * kept as an empty bubble.
     */
    static Dialog parse(String raw) {
        String json = extractJsonObject(raw);
        List<Msg> out = new ArrayList<>();
        String title = "";
        // A missing flag means the reply predates the contract (or the model
        // dropped it); assume it IS a conversation rather than refusing to work.
        boolean isChat = true;
        String reason = "";
        if (json == null) {
            return new Dialog(true, "", title, out, raw);
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            return new Dialog(true, "", title, out, raw);
        }
        if (o.has("is_chat")) isChat = o.optBoolean("is_chat", true);
        reason = o.optString("reason", "").trim();
        title = o.optString("title", "").trim();
        JSONArray arr = o.optJSONArray("messages");
        if (arr == null) return new Dialog(isChat, reason, title, out, raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String text = m.optString("text", "").trim();
            if (text.isEmpty()) continue;
            out.add(new Msg(normalizeSide(m.optString("side", "")), text));
        }
        return new Dialog(isChat, reason, title, out, raw);
    }

    /** "me" only for an explicit self-marker; everything else is the other
     *  person. Mislabelling THEIR words as MINE is the error the advice would
     *  then be built on, so the safe default is "other".
     *
     *  The alias list is deliberately generous: the model is asked for "me" /
     *  "other" but frequently answers in the language of the prompt instead, and
     *  a reply that says 「我」 or "self" must not silently fall through to
     *  "other" — that would relabel the user's own messages as the other
     *  person's, the exact failure this method exists to avoid. Anything that
     *  means "the other person" is listed too so it is not mistaken for a
     *  self-marker by accident. */
    private static String normalizeSide(String side) {
        String s = side == null ? "" : side.trim().toLowerCase();
        return switch (s) {
            case "me", "self", "mine", "i", "sender", "from_me", "right",
                 "我", "自己", "本人", "己方", "我方", "发送方", "我发的", "右侧" -> "me";
            default -> "other";
        };
    }

    /** The outermost {...} in the text, or null when there is none. */
    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence).trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return t.substring(start, end + 1);
    }

    private static String head(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }
}
