package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.tools.IconGenerator;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * The panel's icon set: flat vector art drawn with Java2D, no image resources.
 *
 * <p>Same reasoning as {@code tools/IconGenerator} — this project has no image
 * toolchain — plus one it does not have: a bitmap cannot follow the palette.
 * The same glyph has to be muted in its resting state, ink-black under the
 * cursor, and white on the red the close button turns into; and all of that in
 * two themes. So the geometry lives here and every icon is drawn at the size it
 * is used at, in the colour the call site asks for.
 *
 * <p>The look follows the reference: flat, rounded, one stroke weight, no
 * shadows, no bevels, no text. Sizes are pixels — 18 is what the title bar uses.
 */
public final class Icons {

    private Icons() {
    }

    /** Draws one icon into a {@code size x size} box, origin at the top-left. */
    private interface Art {
        void paint(Graphics2D g, float size);
    }

    // ------------------------------------------------------------------ gear

    /** Teeth on the settings gear. Six, not the usual eight: at 18px eight teeth
     *  leave barely a pixel of gap between them and the ring reads as a blob. */
    private static final int GEAR_TEETH = 6;

    /** The classic cog, in the panel's line weight: a thick ring with six
     *  rounded teeth and a punched-out hub. */
    public static Icon gear(int size, Color color) {
        return vector(size, color, (g, s) -> {
            float c = s / 2f;
            float rOuter = s * 0.29f;
            float rHub = s * 0.12f;
            float toothLen = s * 0.10f;
            float toothW = s * 0.13f;

            Area body = new Area(new Ellipse2D.Float(
                    c - rOuter, c - rOuter, rOuter * 2f, rOuter * 2f));

            // Each tooth starts inside the ring and sticks out past it, so the
            // union has no seam where they meet.
            float y = c - rOuter - toothLen + s * 0.02f;
            float h = toothLen + s * 0.04f;
            for (int i = 0; i < GEAR_TEETH; i++) {
                Shape tooth = new RoundRectangle2D.Float(
                        c - toothW / 2f, y, toothW, h, s * 0.06f, s * 0.06f);
                AffineTransform rot = AffineTransform.getRotateInstance(
                        2 * Math.PI * i / GEAR_TEETH, c, c);
                body.add(new Area(rot.createTransformedShape(tooth)));
            }

            body.subtract(new Area(new Ellipse2D.Float(
                    c - rHub, c - rHub, rHub * 2f, rHub * 2f)));
            g.fill(body);
        });
    }

    // ----------------------------------------------------------------- close

    /** A plain cross with rounded caps — the window close glyph. */
    public static Icon close(int size, Color color) {
        return vector(size, color, (g, s) -> {
            float a = s * 0.30f;
            float b = s * 0.70f;
            g.setStroke(new BasicStroke(s * 0.095f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(a, a, b, b));
            g.draw(new Line2D.Float(b, a, a, b));
        });
    }

    // -------------------------------------------------------------- minimize

    /**
     * One horizontal bar — the window minimize glyph.
     *
     * Deliberately the same span and stroke as {@link #close}: the two sit side
     * by side, and a bar drawn to a different width reads as a misalignment
     * rather than as a second button.
     */
    public static Icon minimize(int size, Color color) {
        return vector(size, color, (g, s) -> {
            g.setStroke(new BasicStroke(s * 0.095f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(s * 0.30f, s * 0.50f, s * 0.70f, s * 0.50f));
        });
    }

    // ----------------------------------------------------------------- brand

    /**
     * The app mark at tray scale — and it is the launcher icon, not a lookalike:
     * the very same vector art, drawn by the same generator that bakes
     * {@code assets/icon.ico}.
     *
     * <p>This used to be a hand-reduced copy of the launcher's drawing, which is
     * exactly how the two drifted apart: two sets of coordinates, two palettes,
     * and only one of them updated when the icon changed. Rendering one source
     * means the tray follows the launcher automatically — including the tile's
     * 8% inset and the draft lines dropping to two at 22px, then one at 16px.
     */
    public static Icon brand(int size) {
        BufferedImage art = IconGenerator.render(size);
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.drawImage(art, x, y, null);
            }
        };
    }

    // ---------------------------------------------------------------- shared

    /** Wraps an {@link Art} into an {@link Icon} that always fills its box. */
    private static Icon vector(int size, Color color, Art art) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    antialias(g2);
                    g2.translate(x, y);
                    g2.setColor(color);
                    art.paint(g2, size);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    private static void antialias(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        // Snap strokes to the pixel grid: at this size a half-pixel offset turns
        // a 1.7px line into two grey ones.
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
    }
}
