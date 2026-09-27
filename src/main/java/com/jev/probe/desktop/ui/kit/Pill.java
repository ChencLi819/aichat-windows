package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * `.pill` / `.tag` — a small stadium-shaped label: danger badges, round markers,
 * evidence tags, and the 「推荐」/「常驻」/「回落默认」 markers.
 */
public class Pill extends JComponent {

    public enum Kind {
        NEUTRAL, BRAND, SUCCESS, WARNING, DANGER,
        SOLID_DANGER, SOLID_WARNING, SOLID_SUCCESS,
        /** Dashed warning outline, for a fallback value shown where a real one is expected. */
        OUTLINE_WARNING
    }

    private final Kind kind;
    private String text;

    public Pill(String text, Kind kind) {
        this.text = text == null ? "" : text;
        this.kind = kind;
        setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        setOpaque(false);
        ThemeManager.addListener(this::repaint);
    }

    public static Pill neutral(String text) {
        return new Pill(text, Kind.NEUTRAL);
    }

    public static Pill brand(String text) {
        return new Pill(text, Kind.BRAND);
    }

    public void setText(String t) {
        this.text = t == null ? "" : t;
        revalidate();
        repaint();
    }

    public String getText() {
        return text;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int h = 22;
        return new Dimension(fm.stringWidth(text) + 16, h);
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
            boolean solid = kind == Kind.SOLID_DANGER || kind == Kind.SOLID_WARNING
                || kind == Kind.SOLID_SUCCESS;
            Color bg = switch (kind) {
                case NEUTRAL -> t.surface2();
                case BRAND -> t.brandWeak();
                case SUCCESS -> t.successWeak();
                case WARNING -> t.warningWeak();
                case DANGER -> t.dangerWeak();
                case SOLID_DANGER -> t.danger();
                case SOLID_WARNING -> t.warning();
                case SOLID_SUCCESS -> t.success();
                case OUTLINE_WARNING -> t.warningWeak();
            };
            Color fg = switch (kind) {
                case NEUTRAL -> t.text2();
                case BRAND -> t.brand();
                case SUCCESS, SOLID_SUCCESS -> solid ? Color.WHITE : t.success();
                case WARNING, SOLID_WARNING -> solid ? Color.WHITE : t.warning();
                case DANGER, SOLID_DANGER -> solid ? Color.WHITE : t.danger();
                case OUTLINE_WARNING -> t.warning();
            };
            int w = getWidth();
            int h = getHeight();
            Color line = null;
            if (kind == Kind.OUTLINE_WARNING) {
                line = t.warning();
            }
            Kit.fill(g2, 0, 0, w, h, Tokens.R_FULL, bg);
            if (line != null) {
                g2.setColor(line);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    1f, new float[]{3f, 2.5f}, 0f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            }
            g2.setFont(solid ? getFont().deriveFont(Font.BOLD) : getFont());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            int y = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, (w - fm.stringWidth(text)) / 2, y);
        } finally {
            g2.dispose();
        }
    }
}
