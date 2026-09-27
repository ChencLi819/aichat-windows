package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/**
 * A rounded, optionally outlined surface — the `card` / `panel` / `input`
 * background of the design system.
 *
 * The fill and stroke are supplied as functions of the active palette rather
 * than as colours, which is what makes these survive a theme switch: the skin is
 * evaluated inside `paintComponent`, so a repaint is all it takes.
 *
 * `windowSurface` reproduces a Windows-specific trick the old panel relied on: a
 * layered (`per-pixel translucent`) top-level window routes mouse events by
 * alpha, so the fully transparent corners of a rounded panel are dead to
 * clicks — dragging by the corner would not work. Laying an alpha=1 black
 * rectangle underneath the rounded fill keeps every pixel hit-testable without
 * changing what is drawn.
 */
public class Surface extends JPanel {

    /** Paints a background for the current size and palette. */
    public interface Skin {
        void paint(Graphics2D g2, int w, int h, Tokens t);
    }

    public static final Skin NONE = (g, w, h, t) -> {
    };

    private final Skin skin;
    private boolean windowSurface;

    public Surface(Skin skin) {
        this(skin, null);
    }

    public Surface(Skin skin, LayoutManager layout) {
        this.skin = skin == null ? NONE : skin;
        setOpaque(false);
        if (layout != null) setLayout(layout);
        ThemeManager.addListener(this::repaint);
    }

    /** Keeps the rounded corners clickable on a translucent top-level window. */
    public void setWindowSurface(boolean on) {
        this.windowSurface = on;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (windowSurface) {
            g.setColor(new Color(0, 0, 0, 1));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        Graphics2D g2 = Kit.prep(g);
        try {
            skin.paint(g2, getWidth(), getHeight(), Theme.tokens());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    // ------------------------------------------------------------- factories

    /** `card--subtle`: surface-1, no outline. */
    public static Skin subtle(int radius) {
        return (g, w, h, t) -> Kit.fill(g, 0, 0, w, h, radius, t.surface1());
    }

    /** `card--tinted`: the brand wash. */
    public static Skin tinted(int radius) {
        return (g, w, h, t) -> Kit.fill(g, 0, 0, w, h, radius, t.brandWeak());
    }

    /** A hairline card: `border: 1px solid var(--border-1)`. */
    public static Skin outlined(int radius, boolean onSurface0) {
        return (g, w, h, t) -> Kit.roundRect(g, 0, 0, w, h, radius,
            onSurface0 ? t.surface0() : null, t.border1());
    }

    /** Any fill/stroke pair, resolved against the palette at paint time. */
    public static Skin of(int radius, java.util.function.Function<Tokens, Color> fill,
                          java.util.function.Function<Tokens, Color> stroke) {
        return (g, w, h, t) -> Kit.roundRect(g, 0, 0, w, h, radius,
            fill == null ? null : fill.apply(t), stroke == null ? null : stroke.apply(t));
    }

    /** A flat, square-cornered fill — for headers and footers. */
    public static Skin flat(java.util.function.Function<Tokens, Color> fill) {
        return (g, w, h, t) -> {
            Color c = fill.apply(t);
            if (c != null) {
                g.setColor(c);
                g.fillRect(0, 0, w, h);
            }
        };
    }

    /** A flat fill plus a single 1px edge, the header/footer treatment. */
    public static Skin flatEdge(java.util.function.Function<Tokens, Color> fill,
                                java.util.function.Function<Tokens, Color> edge,
                                boolean edgeOnTop) {
        return (g, w, h, t) -> {
            Color c = fill.apply(t);
            if (c != null) {
                g.setColor(c);
                g.fillRect(0, 0, w, h);
            }
            Color e = edge.apply(t);
            if (e != null) {
                g.setColor(e);
                g.fillRect(0, edgeOnTop ? 0 : h - 1, w, 1);
            }
        };
    }
}
