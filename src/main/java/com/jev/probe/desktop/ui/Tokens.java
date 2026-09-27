package com.jev.probe.desktop.ui;

import java.awt.Color;

/**
 * The design tokens from `design/demo/assets/css/tokens.css`, as one
 * immutable value.
 *
 * A record rather than a pile of static fields on purpose. The old `Theme` held
 * 17 `public static Color` fields that every component copied at construction
 * time, which is exactly why changing the theme needed a restart — nothing ever
 * asked again. Passing a palette value around means a widget reads
 * {@link Theme#tokens()} at paint time and is therefore correct for whichever
 * theme is current.
 *
 * The `*Weak` colours keep their CSS alpha instead of being pre-blended against
 * a surface: painting a translucent fill over an opaque parent (which Swing
 * always paints first) reproduces `rgba(...)` exactly, and keeps working when
 * the widget sits on surface-1 instead of surface-0.
 *
 * Sizes, spacing, radii and motion do not change between themes and live here as
 * constants so there is still one source of truth.
 */
public record Tokens(
    Color brand,
    Color brandHover,
    Color brandActive,
    Color brandWeak,
    Color brandWeakStrong,
    Color brandInk,
    Color success,
    Color successWeak,
    Color warning,
    Color warningWeak,
    Color danger,
    Color dangerWeak,
    Color info,
    Color infoWeak,
    Color text1,
    Color text2,
    Color text3,
    Color textInverse,
    Color surface0,
    Color surface1,
    Color surface2,
    Color surface3,
    Color canvas,
    Color border1,
    Color border2,
    Color borderFocus
) {

    // ------------------------------------------------------------ structure

    /** Spacing scale `--sp-1 … --sp-8`. */
    public static final int SP1 = 4;
    public static final int SP2 = 6;
    public static final int SP3 = 8;
    public static final int SP4 = 12;
    public static final int SP5 = 16;
    public static final int SP6 = 20;
    public static final int SP7 = 24;
    public static final int SP8 = 32;

    /** Radius scale `--r-xs … --r-full`. */
    public static final int R_XS = 4;
    public static final int R_SM = 6;
    public static final int R_MD = 8;
    public static final int R_LG = 12;
    public static final int R_XL = 16;
    /** The pill radius: large enough that any height rounds to a stadium. */
    public static final int R_FULL = 999;

    /** Type scale `--fs-*`, in points at 100%. */
    public static final float FS_CAPTION = 11f;
    public static final float FS_NOTE = 12f;
    public static final float FS_BODY = 13f;
    public static final float FS_SUBTITLE = 15f;
    public static final float FS_TITLE = 17f;
    public static final float FS_DISPLAY = 20f;

    /** Matching line heights `--lh-*`, used for wrap-aware label sizing. */
    public static final int LH_CAPTION = 16;
    public static final int LH_NOTE = 18;
    public static final int LH_BODY = 20;
    public static final int LH_SUBTITLE = 22;
    public static final int LH_TITLE = 24;
    public static final int LH_DISPLAY = 28;

    /** Motion durations `--t-*`, for anything that animates. */
    public static final int T_FAST = 120;
    public static final int T_BASE = 200;

    // -------------------------------------------------------------- palettes

    /** `:root` from tokens.css. */
    public static final Tokens LIGHT = new Tokens(
        new Color(0x00, 0xC2, 0x9A),
        new Color(0x00, 0xAD, 0x8A),
        new Color(0x00, 0x98, 0x7A),
        rgba(0x00, 0xC2, 0x9A, 0.10),
        rgba(0x00, 0xC2, 0x9A, 0.18),
        Color.WHITE,
        new Color(0x0C, 0xBF, 0x5B),
        rgba(0x0C, 0xBF, 0x5B, 0.12),
        new Color(0xF5, 0x9E, 0x0B),
        rgba(0xF5, 0x9E, 0x0B, 0.14),
        new Color(0xE5, 0x3E, 0x3E),
        rgba(0xE5, 0x3E, 0x3E, 0.10),
        new Color(0x3B, 0x82, 0xF6),
        rgba(0x3B, 0x82, 0xF6, 0.12),
        new Color(0x1F, 0x1F, 0x1F),
        new Color(0x5A, 0x61, 0x69),
        new Color(0x8A, 0x91, 0x99),
        Color.WHITE,
        Color.WHITE,
        new Color(0xF7, 0xF8, 0xF9),
        new Color(0xF0, 0xF2, 0xF4),
        new Color(0xE9, 0xEC, 0xEF),
        new Color(0xEC, 0xEF, 0xF3),
        rgba(0x00, 0x00, 0x00, 0.08),
        rgba(0x00, 0x00, 0x00, 0.14),
        rgba(0x00, 0xC2, 0x9A, 0.55)
    );

    /** `[data-theme="dark"]` from tokens.css. Only colours differ. */
    public static final Tokens DARK = new Tokens(
        new Color(0x2E, 0xD3, 0xAE),
        new Color(0x53, 0xDF, 0xC1),
        new Color(0x00, 0xC2, 0x9A),
        rgba(0x2E, 0xD3, 0xAE, 0.14),
        rgba(0x2E, 0xD3, 0xAE, 0.24),
        new Color(0x08, 0x24, 0x1F),
        new Color(0x34, 0xD3, 0x99),
        rgba(0x34, 0xD3, 0x99, 0.16),
        new Color(0xF7, 0xB4, 0x3C),
        rgba(0xF7, 0xB4, 0x3C, 0.18),
        new Color(0xF0, 0x6B, 0x6B),
        rgba(0xF0, 0x6B, 0x6B, 0.16),
        new Color(0x60, 0xA5, 0xFA),
        rgba(0x60, 0xA5, 0xFA, 0.18),
        new Color(0xE8, 0xEA, 0xED),
        new Color(0xA8, 0xAF, 0xB7),
        new Color(0x7C, 0x84, 0x8C),
        new Color(0x08, 0x24, 0x1F),
        new Color(0x1E, 0x1F, 0x22),
        new Color(0x26, 0x28, 0x2C),
        new Color(0x2F, 0x32, 0x37),
        new Color(0x3A, 0x3E, 0x44),
        new Color(0x14, 0x16, 0x1A),
        rgba(0xFF, 0xFF, 0xFF, 0.09),
        rgba(0xFF, 0xFF, 0xFF, 0.18),
        rgba(0x2E, 0xD3, 0xAE, 0.60)
    );

    /** `rgba(r, g, b, a)` from tokens.css, as a translucent colour. */
    private static Color rgba(int r, int g, int b, double alpha) {
        return new Color(r, g, b, (int) Math.round(alpha * 255));
    }
}
