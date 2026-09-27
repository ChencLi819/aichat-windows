package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * `.rel-pill` — the panel header's relationship capsule.
 *
 * It shows WHICH relationship is in force for the person currently being read,
 * and distinguishes the two sources: a solid brand pill means the contact has
 * their own relationship, a dashed warning pill means the value shown is only the
 * fallback (FR-REL-02 / FR-REL-06). Without that distinction the user cannot tell
 * whether editing the person did anything.
 */
public class RelPill extends JComponent {

    private String label = "";
    private boolean fallback;
    private Runnable onClick;

    public RelPill() {
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (onClick != null) onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                repaint();
            }
        });
        ThemeManager.addListener(this::repaint);
    }

    /**
     * @param label the pill's text, e.g. `伴侣` or `普通朋友（未设置，用默认）`.
     * @param fallback true when the value comes from the default rather than the
     *        contact; the pill is then dashed and warning-coloured.
     */
    public RelPill set(String label, boolean fallback) {
        this.label = label == null ? "" : label;
        this.fallback = fallback;
        revalidate();
        repaint();
        return this;
    }

    public RelPill onClick(Runnable action) {
        this.onClick = action;
        return this;
    }

    public boolean isFallback() {
        return fallback;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        return new Dimension(Math.min(150, fm.stringWidth(label) + 16), 22);
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
            int w = getWidth();
            int h = getHeight();
            Color fg = fallback ? t.warning() : t.brand();
            Kit.fill(g2, 0, 0, w, h, Tokens.R_FULL, fallback ? t.warningWeak() : t.brandWeak());
            if (fallback) {
                g2.setColor(fg);
                g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT,
                    java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 2.5f}, 0f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            }
            g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            String shown = label;
            while (fm.stringWidth(shown) > w - 16 && shown.length() > 1) {
                shown = shown.substring(0, shown.length() - 2) + "…";
            }
            g2.drawString(shown, (w - fm.stringWidth(shown)) / 2,
                (h + fm.getAscent() - fm.getDescent()) / 2);
        } finally {
            g2.dispose();
        }
    }
}
