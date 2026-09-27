package com.jev.probe.desktop.model;

/** One captured chat bubble. side is "me" (right) or "other" (left). */
public record Msg(String side, String text) {
}
