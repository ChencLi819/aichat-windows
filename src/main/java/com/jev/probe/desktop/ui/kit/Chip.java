package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

/**
 * `.chip` — the panel's status capsule: a 7px dot, a label, and a colour that
 * says whether the assistant is listening, thinking, paused or broken.
 *
 * The analysing state's dot breathes (`@keyframes pulse`, 1s infinite) because a
 * silent 10–30 second wait reads as a hang; the animation is owned by the chip
 * and stops as soon as the state leaves ANALYZING, so an idle panel burns no
 * repaints.
 */
public class Chip extends JComponent {

    public enum Kind {
        LISTENING, ANALYZING, PAUSED, ERROR
    }

    private Kind kind;
    private String text;
    private final Timer pulse;
    private float phase;

    public Chip(Kind kind, String text) {
        this.kind = kind;
        this.text = text == null ? "" : text;
        setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        setOpaque(false);
        pulse = new Timer(60, e -> {
            phase = (phase + 0.06f) % 1f;
            repaint();
        });
        pulse.setRepeats(true);
        ThemeManager.addListener(this::repaint);
    }

    /** The four states of FR-PANEL-02, with their prototype wording. */
    public void setState(Kind kind, String text) {
        this.kind = kind;
        this.text = text == null ? "" : text;
        if (kind == Kind.ANALYZING) {
            if (!pulse.isRunning()) pulse.start();
        } else {
            pulse.stop();
            phase = 0;
        }
        revalidate();
        repaint();
    }

    /** Only the label changes — used to tick the analysing timer once a second. */
    public void setText(String text) {
        this.text = text == null ? "" : text;
        revalidate();
        repaint();
    }

    public Kind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (kind == Kind.ANALYZING && !pulse.isRunning()) pulse.start();
    }

    @Override
    public void removeNotify() {
        pulse.stop();
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(text) + 10 * 2 + 7 + 6, 24);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            Tokens t = Theme.tokens();
            Color bg = switch (kind) {
                case LISTENING -> t.successWeak();
                case ANALYZING -> t.infoWeak();
                case PAUSED -> t.surface2();
                case ERROR -> t.dangerWeak();
            };
            Color fg = switch (kind) {
                case LISTENING -> t.success();
                case ANALYZING -> t.info();
                case PAUSED -> t.text3();
                case ERROR -> t.danger();
            };
            int w = getWidth();
            int h = getHeight();
            Kit.fill(g2, 0, 0, w, h, Tokens.R_FULL, bg);

            double dot = 7;
            double cx = 10 + dot / 2;
            double cy = h / 2.0;
            Color dotColor = fg;
            if (kind == Kind.ANALYZING) {
                // 1 -> .25 -> 1 over a second, matching `pulse`.
                double k = 0.5 - 0.5 * Math.cos(phase * 2 * Math.PI);
                int alpha = (int) Math.round(255 * (1 - 0.75 * k));
                dotColor = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), alpha);
            }
            g2.setColor(dotColor);
            g2.fill(new Ellipse2D.Double(cx - dot / 2, cy - dot / 2, dot, dot));

            g2.setFont(getFont());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            int x = (int) (cx + dot / 2 + 6);
            g2.drawString(text, x, (int) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
        } finally {
            g2.dispose();
        }
    }
}
