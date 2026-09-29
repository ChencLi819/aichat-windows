package com.jev.probe.desktop.net;

/**
 * Carries the route, the HTTP status (null = transport failure) and the first
 * 120 chars of the response body so the settings page can show the real reason.
 *
 * <p>[retryable] separates "the transport hiccuped" from "the answer itself is
 * unusable". A 200 with an empty message is the second kind: re-sending it pays
 * another full thinking pass (tens of seconds) and produces the same emptiness,
 * so the retry loop must not swallow it — the caller decides what to do about it
 * (see LlmJudgeClient.ask).
 */
public class ApiException extends RuntimeException {

    private final String route;
    private final Integer status;
    private final String snippet;
    private final boolean retryable;

    public ApiException(String route, Integer status, String snippet) {
        this(route, status, snippet, true);
    }

    public ApiException(String route, Integer status, String snippet, boolean retryable) {
        super(buildMessage(route, status, snippet));
        this.route = route;
        this.status = status;
        this.snippet = snippet;
        this.retryable = retryable;
    }

    /** False = 重发没有意义（内容类问题），调用方需要换一种做法。 */
    public boolean retryable() {
        return retryable;
    }

    public static String buildMessage(String route, Integer status, String snippet) {
        if (status != null) {
            return route + " HTTP " + status + "：" + take(snippet, 120);
        }
        return route + " 请求失败：" + take(snippet, 120);
    }

    private static String take(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    public String route() {
        return route;
    }

    public Integer status() {
        return status;
    }

    public String snippet() {
        return snippet;
    }
}
