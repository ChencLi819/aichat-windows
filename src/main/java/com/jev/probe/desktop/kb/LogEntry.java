package com.jev.probe.desktop.kb;

/** One remembered chat line. side is "me" / "other", matching [com.jev.probe.desktop.model.Msg]. */
public record LogEntry(String side, String text, long ts, String app) {
}
