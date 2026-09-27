package com.jev.probe.desktop.net;

/**
 * Which of the three API routes a failure came from. Used to build error text
 * the user can act on ("判断接口 HTTP 401：…" vs "回复接口 …").
 */
public final class Route {

    private Route() {
    }

    public static final String JUDGE = "判断接口";
    public static final String REPLY = "回复接口";
    public static final String VISION = "视觉接口";
}
