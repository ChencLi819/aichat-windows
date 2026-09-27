package com.jev.probe.desktop.ui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * The app's vector icon set, drawn as {@link Glyph}s.
 *
 * Every shape is described in a 24×24 design grid and scaled to whatever size
 * the caller asks for, so one definition serves a 16px tray mark and a 28px
 * button. Stroke weight is derived from the size for the same reason.
 */
public final class Glyphs {

    private Glyphs() {
    }

    /** A stroked line weight that stays legible from 12px to 40px. */
    private static float sw(int size) {
        return Math.max(1.4f, size / 14f);
    }

    private static double u(int size, double v) {
        return v / 24.0 * size;
    }

    private static Graphics2D stroked(Graphics2D g2, int size, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(sw(size), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        return g2;
    }

    /** The settings cog: a ring with six teeth. */
    public static Glyph gear() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            double c = size / 2.0;
            double outer = u(size, 9.2);
            double inner = u(size, 5.4);
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 3 * i;
                g2.draw(new Line2D.Double(
                    c + Math.cos(a) * inner, c + Math.sin(a) * inner,
                    c + Math.cos(a) * outer, c + Math.sin(a) * outer));
            }
            double ring = u(size, 6.4);
            g2.draw(new Ellipse2D.Double(c - ring, c - ring, ring * 2, ring * 2));
            double hole = u(size, 2.6);
            g2.draw(new Ellipse2D.Double(c - hole, c - hole, hole * 2, hole * 2));
        };
    }

    /** Minimize-to-tray: a single horizontal bar. */
    public static Glyph minimize() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            double y = u(size, 12);
            g2.draw(new Line2D.Double(u(size, 6.5), y, u(size, 17.5), y));
        };
    }

    public static Glyph close() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            g2.draw(new Line2D.Double(u(size, 7), u(size, 7), u(size, 17), u(size, 17)));
            g2.draw(new Line2D.Double(u(size, 17), u(size, 7), u(size, 7), u(size, 17)));
        };
    }

    /** The overflow trigger: three dots. */
    public static Glyph more() {
        return (g2, size, color) -> {
            g2.setColor(color);
            double r = Math.max(1.3, u(size, 1.6));
            double x = size / 2.0;
            for (double dy : new double[]{-6.4, 0, 6.4}) {
                double cy = size / 2.0 + u(size, dy);
                g2.fill(new Ellipse2D.Double(x - r, cy - r, r * 2, r * 2));
            }
        };
    }

    /**
     * The empty-state mark: a speech bubble with a spark, so an empty panel
     * reads as "nothing said yet" rather than as an error.
     */
    public static Glyph sparkle() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            Path2D p = new Path2D.Double();
            double x0 = u(size, 3.5);
            double y0 = u(size, 4.5);
            double x1 = u(size, 20.5);
            double y1 = u(size, 16.5);
            p.moveTo(x0 + u(size, 3), y0);
            p.lineTo(x1 - u(size, 3), y0);
            p.quadTo(x1, y0, x1, y0 + u(size, 3));
            p.lineTo(x1, y1 - u(size, 3));
            p.quadTo(x1, y1, x1 - u(size, 3), y1);
            p.lineTo(x0 + u(size, 7), y1);
            p.lineTo(x0 + u(size, 3.5), u(size, 20.5));
            p.lineTo(x0 + u(size, 3.5), y1);
            p.quadTo(x0, y1, x0, y1 - u(size, 3));
            p.lineTo(x0, y0 + u(size, 3));
            p.quadTo(x0, y0, x0 + u(size, 3), y0);
            p.closePath();
            g2.draw(p);
            g2.draw(new Line2D.Double(u(size, 9), u(size, 10.5), u(size, 15), u(size, 10.5)));
        };
    }

    /** Send: an arrow leaving a baseline. */
    public static Glyph send() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            Path2D p = new Path2D.Double();
            p.moveTo(u(size, 4), u(size, 12));
            p.lineTo(u(size, 20), u(size, 4));
            p.lineTo(u(size, 14), u(size, 20));
            p.lineTo(u(size, 12), u(size, 13.6));
            p.closePath();
            g2.draw(p);
        };
    }

    /** The onboarding promise tick. */
    public static Glyph check() {
        return (g2, size, color) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(Math.max(1.8f, size / 8f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Double();
            p.moveTo(u(size, 5), u(size, 12.5));
            p.lineTo(u(size, 10), u(size, 17));
            p.lineTo(u(size, 19), u(size, 7));
            g2.draw(p);
        };
    }

    /** The region-picking mark: a crop frame. */
    public static Glyph crop() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            Path2D p = new Path2D.Double();
            p.moveTo(u(size, 7), u(size, 3));
            p.lineTo(u(size, 7), u(size, 17));
            p.lineTo(u(size, 21), u(size, 17));
            p.moveTo(u(size, 3), u(size, 7));
            p.lineTo(u(size, 17), u(size, 7));
            p.lineTo(u(size, 17), u(size, 21));
            g2.draw(p);
        };
    }

    /** A downward chevron, for selects. */
    public static Glyph chevronDown() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            Path2D p = new Path2D.Double();
            p.moveTo(u(size, 6), u(size, 9.5));
            p.lineTo(u(size, 12), u(size, 15));
            p.lineTo(u(size, 18), u(size, 9.5));
            g2.draw(p);
        };
    }

    /** A thin cross, for clearing a field. */
    public static Glyph cross() {
        return (g2, size, color) -> {
            stroked(g2, size, color);
            g2.draw(new Line2D.Double(u(size, 8), u(size, 8), u(size, 16), u(size, 16)));
            g2.draw(new Line2D.Double(u(size, 16), u(size, 8), u(size, 8), u(size, 16)));
        };
    }
}
