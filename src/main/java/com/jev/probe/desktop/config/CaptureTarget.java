package com.jev.probe.desktop.config;

import java.awt.Rectangle;

/**
 * Which part of which window to read.
 *
 * Stored as a window identity plus a rectangle INSIDE that window rather than as
 * a screen rectangle, because a screen rectangle cannot survive a mixed-DPI
 * multi-monitor desktop (see `capture/Win32Windows`) and stops meaning anything
 * the moment the chat window is moved, occluded or minimized.
 *
 * @param windowClass the Win32 class of the target window, e.g.
 *        `Qt51514QWindowIcon`; empty when only the title is known.
 * @param windowTitle the window's title, e.g. `微信`.
 * @param x left edge of the watched area, relative to the window's top-left.
 * @param y top edge, relative to the window's top-left.
 */
public record CaptureTarget(String windowClass, String windowTitle,
                            int x, int y, int width, int height) {

    /** The watched area, in the target window's own pixel space. */
    public Rectangle crop() {
        return new Rectangle(x, y, width, height);
    }

    /** `微信（Qt51514QWindowIcon）`, for messages that name what was bound. */
    public String describe() {
        if (windowTitle == null || windowTitle.isBlank()) return "未指定窗口";
        return windowClass == null || windowClass.isBlank()
            ? windowTitle
            : windowTitle + "（" + windowClass + "）";
    }
}
