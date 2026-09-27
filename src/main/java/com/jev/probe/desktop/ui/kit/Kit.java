package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Tokens;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Painting helpers shared by every self-drawn widget. */
public final class Kit {

    private Kit() {
    }

    /** A 2D context with antialiasing on; the caller must dispose it. */
    public static Graphics2D prep(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /**
     * Rounds a rectangle's arcs.
     *
     * `Tokens.R_FULL` means "a stadium": the CSS `border-radius: 999px` idiom,
     * where the radius collapses to half the shorter side. Passing 999*2 as an
     * arc diameter would be wrong, so it is resolved here instead of at every
     * call site.
     */
    public static int arc(int w, int h, int radius) {
        return radius >= Tokens.R_FULL ? Math.min(w, h) : Math.min(radius * 2, Math.min(w, h));
    }

    public static void fill(Graphics2D g2, int x, int y, int w, int h, int radius, Color fill) {
        if (fill == null || w <= 0 || h <= 0) return;
        g2.setColor(fill);
        int a = arc(w, h, radius);
        g2.fillRoundRect(x, y, w, h, a, a);
    }

    public static void stroke(Graphics2D g2, int x, int y, int w, int h, int radius, Color stroke) {
        if (stroke == null || w <= 0 || h <= 0) return;
        g2.setColor(stroke);
        int a = arc(w, h, radius);
        // Inset by half a pixel so a 1px hairline lands inside the bounds rather
        // than being clipped by them.
        g2.drawRoundRect(x, y, w - 1, h - 1, a, a);
    }

    public static void roundRect(Graphics2D g2, int x, int y, int w, int h,
                                 int radius, Color fill, Color stroke) {
        fill(g2, x, y, w, h, radius, fill);
        stroke(g2, x, y, w, h, radius, stroke);
    }

    /** `rgba()` over an opaque parent, the way the CSS weak tints work. */
    public static Color tint(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /**
     * `.input` — surface-1 fill, 1px border, 8px radius, and on focus a brand
     * border plus the `0 0 0 3px var(--brand-weak)` halo.
     *
     * The halo is drawn just inside the border rather than outside it: Swing
     * clips a component to its own bounds, so a real outer glow would need a
     * padding wrapper around every field in the app.
     */
    public static void fieldSkin(Graphics2D g2, int w, int h, Tokens t,
                                 boolean focused, boolean invalid, boolean enabled) {
        int radius = Tokens.R_MD;
        fill(g2, 0, 0, w, h, radius, enabled ? t.surface1() : t.surface2());
        if (focused && enabled && !invalid) {
            g2.setColor(t.brandWeak());
            g2.setStroke(new java.awt.BasicStroke(3f));
            int a = Math.max(2, arc(w, h, radius) - 6);
            g2.drawRoundRect(3, 3, w - 7, h - 7, a, a);
        }
        Color line = invalid ? t.danger()
            : focused && enabled ? t.brand()
            : t.border2();
        stroke(g2, 0, 0, w, h, radius, line);
    }

    /**
     * FR-PANEL-16: lights up the resize hot zone the pointer is currently on.
     *
     * A native frame draws its own grab affordance; an undecorated window has
     * none, so the hint is painted by hand — a translucent band along whichever
     * edge or corner is live, matching the hot zone's width so what lights up is
     * exactly what can be dragged.
     *
     * Clipped to the rounded rect: without it the band would fill the corner
     * square outside the arc and turn the radius into a hard right angle.
     *
     * The edges are passed as flags rather than as a bitmask so this class stays
     * independent of {@code WindowResizer}, which lives a package up.
     */
    public static void resizeHint(Graphics2D g2, int w, int h, int radius, int grab,
                                  boolean north, boolean south, boolean west, boolean east,
                                  Color color) {
        if (color == null || w <= 0 || h <= 0) return;
        if (!north && !south && !west && !east) return;
        java.awt.Shape prev = g2.getClip();
        int a = arc(w, h, radius);
        g2.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, a, a));
        g2.setColor(color);
        if (north) g2.fillRect(0, 0, w, grab);
        if (south) g2.fillRect(0, h - grab, w, grab);
        if (west) g2.fillRect(0, 0, grab, h);
        if (east) g2.fillRect(w - grab, 0, grab, h);
        g2.setClip(prev);
    }
}
