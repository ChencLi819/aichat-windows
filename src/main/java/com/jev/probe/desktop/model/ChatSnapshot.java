package com.jev.probe.desktop.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground.
 */
public final class ChatSnapshot {

    /**
     * Capture chrome that is NOT a message: typing indicators, read receipts,
     * timestamps, recall notices, the input-box placeholder. These flicker while
     * the user watches the screen, and counting them as content made an
     * already-analysed snapshot look new — the reported "生成候选回复后经常
     * 自动重新生成". Filtered out of [ChatSnapshot.signature] / [ChatSnapshot.latestFrom],
     * but the lines still go to the model (harmless there).
     */
    private static final Pattern CHROME_CONTAINS = Pattern.compile("正在输入|撤回了一条消息|撤回了一条|重新编辑");
    private static final Pattern CHROME_EXACT = Pattern.compile(
        "^已读$|^未读$|^发送$|^按住.*说话$|^\\d{1,2}[:：]\\d{2}$|^(昨天|今天|前天|星期[一二三四五六日天])$");

    static boolean isChromeLine(String text) {
        String s = text.trim();
        if (s.isEmpty()) return true;
        return CHROME_CONTAINS.matcher(s).find() || CHROME_EXACT.matcher(s).matches();
    }

    private final String title;
    private final List<Msg> messages;

    private volatile List<Msg> real;

    public ChatSnapshot(String title, List<Msg> messages) {
        this.title = title;
        this.messages = messages;
    }

    public String title() {
        return title;
    }

    public List<Msg> messages() {
        return messages;
    }

    /** [messages] minus the capture chrome — what the conversation really is. */
    public List<Msg> real() {
        List<Msg> r = real;
        if (r == null) {
            synchronized (this) {
                r = real;
                if (r == null) {
                    List<Msg> out = new ArrayList<>();
                    for (Msg m : messages) {
                        if (!isChromeLine(m.text())) out.add(m);
                    }
                    r = out;
                    real = r;
                }
            }
        }
        return r;
    }

    public String latestFrom() {
        List<Msg> r = real();
        return r.isEmpty() ? null : r.get(r.size() - 1).side();
    }

    /** Identity of the newest incoming message: "is there something new to answer". */
    public String lastIncoming() {
        List<Msg> r = real();
        for (int i = r.size() - 1; i >= 0; i--) {
            if ("other".equals(r.get(i).side())) return r.get(i).text();
        }
        return null;
    }

    /** A stable signature of the last few REAL messages, to detect real changes. */
    public String signature() {
        List<Msg> r = real();
        int from = Math.max(0, r.size() - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < r.size(); i++) {
            if (i > from) sb.append('|');
            sb.append(r.get(i).side()).append(':').append(r.get(i).text());
        }
        return sb.toString();
    }

    /** The newest [n] elements, or the whole list when it is shorter. */
    public static <T> List<T> takeLast(List<T> list, int n) {
        if (n <= 0) return List.of();
        int from = Math.max(0, list.size() - n);
        return new ArrayList<>(list.subList(from, list.size()));
    }
}
