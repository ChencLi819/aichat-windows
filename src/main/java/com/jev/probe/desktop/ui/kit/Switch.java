package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * `.switch` — the 36×20 toggle behind 「沿用判断接口密钥」, 「自动分析」,
 * 「记录聊天历史」 and the per-note enable switch.
 *
 * One class for every switch in the app, replacing the `JCheckBox` / `JToggleButton`
 * mixture that FR-SET-11 forbids: the two native controls render with the system
 * look-and-feel and stop matching as soon as the dark palette is active.
 */
public class Switch extends JComponent {

    private static final int W = 36;
    private static final int H = 20;
    private static final int KNOB = 16;

    private boolean on;
    private boolean enabled = true;
    private Consumer<Boolean> onChange;

    public Switch(boolean initial) {
        this.on = initial;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!enabled) return;
                Switch.this.on = !Switch.this.on;
                repaint();
                if (onChange != null) onChange.accept(Switch.this.on);
            }
        });
        ThemeManager.addListener(this::repaint);
    }

    public boolean isOn() {
        return on;
    }

    /** Programmatic change; does not fire [onChange]. */
    public Switch setOn(boolean value) {
        if (on == value) return this;
        on = value;
        repaint();
        return this;
    }

    @Override
    public void setEnabled(boolean e) {
        this.enabled = e;
        setCursor(Cursor.getPredefinedCursor(e ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public Switch onChange(Consumer<Boolean> listener) {
        this.onChange = listener;
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W, H);
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
            int y = (getHeight() - H) / 2;
            Kit.fill(g2, 0, y, W, H, Tokens.R_FULL,
                !enabled ? t.surface2() : on ? t.brand() : t.surface3());
            int kx = on ? W - KNOB - 2 : 2;
            g2.setColor(!enabled ? t.text3() : t.surface0());
            g2.fillOval(kx, y + 2, KNOB, KNOB);
            g2.setColor(Theme.alpha(java.awt.Color.BLACK, 18));
            g2.drawOval(kx, y + 2, KNOB, KNOB);
        } finally {
            g2.dispose();
        }
    }
}
