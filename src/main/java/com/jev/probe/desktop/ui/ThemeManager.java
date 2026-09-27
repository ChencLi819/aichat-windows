package com.jev.probe.desktop.ui;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single source of truth for which palette is active, and the one place
 * that knows how to change it at runtime.
 *
 * Why this exists: the old `Theme` reassigned static colour fields that every
 * component had already copied into its own fields during construction, and the
 * only call to `apply` was in `main` before any component existed. A theme
 * change therefore could not be anything but a restart. Here the palette is a
 * value that widgets ask for while painting, and a change is broadcast to every
 * registered surface so the whole app (panel and any open dialogs) repaints
 * within one frame.
 *
 * "Follow system" is honest about what it can do: Windows exposes the
 * apps-light/dark preference in the registry, and that is what is read — once
 * per resolution, not watched. A live change is picked up the next time the
 * user opens settings or the app starts, which is the same behaviour as the
 * prototype's `matchMedia` one-shot read.
 */
public final class ThemeManager {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /** `--theme` values as stored in `Config`. */
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String SYSTEM = "system";

    /** Font-size presets from the 外观 page. */
    public static final float SCALE_STANDARD = 1.0f;
    public static final float SCALE_LARGE = 1.15f;

    private static final String PERSONALIZE_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String APPS_USE_LIGHT = "AppsUseLightTheme";

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static String mode = LIGHT;
    private static boolean dark;
    private static float fontScale = SCALE_STANDARD;

    private ThemeManager() {
    }

    /** The active palette. Read this while painting, never store it. */
    public static Tokens tokens() {
        return dark ? Tokens.DARK : Tokens.LIGHT;
    }

    public static boolean isDark() {
        return dark;
    }

    public static String mode() {
        return mode;
    }

    public static float fontScale() {
        return fontScale;
    }

    /**
     * Switch the theme and repaint everything that registered.
     *
     * @param newMode [LIGHT], [DARK] or [SYSTEM].
     */
    public static void setMode(String newMode) {
        String m = newMode == null || newMode.isBlank() ? LIGHT : newMode.trim().toLowerCase();
        boolean nowDark = switch (m) {
            case DARK -> true;
            case SYSTEM -> systemPrefersDark();
            default -> false;
        };
        boolean changed = !m.equals(mode) || nowDark != dark;
        mode = m;
        dark = nowDark;
        if (changed) notifyListeners();
    }

    /** 标准 / 大 — applied to every `Theme.font` result. */
    public static void setFontScale(float scale) {
        float s = scale <= 0 ? SCALE_STANDARD : scale;
        if (Math.abs(s - fontScale) < 0.001f) return;
        fontScale = s;
        notifyListeners();
    }

    /**
     * Register a repaint target. Components that paint their own colours should
     * call this and `SwingUtilities.updateComponentTreeUI`-style refresh
     * themselves; text that carries colour inside an HTML string (see
     * `MdText`) must re-render instead.
     */
    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        Runnable fire = () -> {
            for (Runnable r : LISTENERS) {
                try {
                    r.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "主题切换回调异常", e);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) fire.run();
        else SwingUtilities.invokeLater(fire);
    }

    /**
     * Windows' own "choose your default app mode" setting.
     *
     * `AppsUseLightTheme` is 1 for light and 0 for dark; a missing value means
     * the user never touched it, which is light on every build this targets.
     * Any failure (non-Windows, locked-down registry) falls back to light rather
     * than throwing — the theme is not worth crashing over.
     */
    private static boolean systemPrefersDark() {
        try {
            return Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, APPS_USE_LIGHT) == 0;
        } catch (Exception e) {
            LOG.log(Level.FINE, "读不到系统主题偏好，按浅色处理", e);
            return false;
        }
    }
}
