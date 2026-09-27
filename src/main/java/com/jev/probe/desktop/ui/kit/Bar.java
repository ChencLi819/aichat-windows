package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** `.bar` — the 4px hit-probability bar under a candidate reply. */
public class Bar extends JComponent {

    private double fraction;

    public Bar() {
        setOpaque(false);
        ThemeManager.addListener(this::repaint);
    }

    /** 0–1; values outside the range are clamped, because the model's `score`
     *  is only *meant* to be 0–1 and has been observed outside it. */
    public Bar setFraction(double f) {
        this.fraction = Double.isNaN(f) ? 0 : Math.max(0, Math.min(1, f));
        repaint();
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(40, 4);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(20, 4);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, 4);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            int w = getWidth();
            int h = getHeight();
            Kit.fill(g2, 0, 0, w, h, Tokens.R_FULL, Theme.tokens().surface2());
            int fill = (int) Math.round(w * fraction);
            if (fill > 0) {
                Kit.fill(g2, 0, 0, Math.max(fill, h), h, Tokens.R_FULL, Theme.tokens().brand());
            }
        } finally {
            g2.dispose();
        }
    }
}
