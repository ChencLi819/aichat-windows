package com.jev.probe.desktop.net;

/**
 * Carries the route, the HTTP status (null = transport failure) and the first
 * 120 chars of the response body so the settings page can show the real reason.
 */
public class ApiException extends RuntimeException {

    private final String route;
    private final Integer status;
    private final String snippet;

    public ApiException(String route, Integer status, String snippet) {
        super(buildMessage(route, status, snippet));
        this.route = route;
        this.status = status;
        this.snippet = snippet;
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
