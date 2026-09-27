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
import java.util.function.IntConsumer;

/**
 * `input[type=range]` — the 4px track with a 14px brand knob, used for panel
 * opacity.
 *
 * `JSlider` is deliberately not used: it renders through the system
 * look-and-feel, which is the mixing FR-SET-11 forbids and which looks
 * particularly wrong once the dark palette is active.
 */
public class Slider extends JComponent {

    private static final int HEIGHT = 20;
    private static final int TRACK = 4;
    private static final int KNOB = 14;

    private final int min;
    private final int max;
    private int value;
    private IntConsumer onChange;

    public Slider(int min, int max, int value) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        this.value = clamp(value);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                drag(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                drag(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        ThemeManager.addListener(this::repaint);
    }

    private void drag(MouseEvent e) {
        int usable = Math.max(1, getWidth() - KNOB);
        double f = (e.getX() - KNOB / 2.0) / usable;
        int next = clamp((int) Math.round(min + f * (max - min)));
        if (next == value) return;
        value = next;
        repaint();
        if (onChange != null) onChange.accept(value);
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    public int value() {
        return value;
    }

    /** Programmatic change; does not fire [onChange]. */
    public Slider setValue(int v) {
        value = clamp(v);
        repaint();
        return this;
    }

    public Slider onChange(IntConsumer listener) {
        this.onChange = listener;
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(180, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(80, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            var t = Theme.tokens();
            int mid = getHeight() / 2;
            int trackY = mid - TRACK / 2;
            int usable = Math.max(1, getWidth() - KNOB);
            int knobX = (int) Math.round((double) (value - min) / (max - min) * usable);

            Kit.fill(g2, 0, trackY, getWidth(), TRACK, Tokens.R_FULL, t.surface3());
            if (knobX > 0) {
                Kit.fill(g2, 0, trackY, knobX + KNOB / 2, TRACK, Tokens.R_FULL, t.brand());
            }
            g2.setColor(t.brand());
            g2.fillOval(knobX, mid - KNOB / 2, KNOB, KNOB);
            g2.setColor(t.surface0());
            g2.fillOval(knobX + 2, mid - KNOB / 2 + 2, KNOB - 4, KNOB - 4);
            g2.setColor(t.brand());
            g2.fillOval(knobX + 4, mid - KNOB / 2 + 4, KNOB - 8, KNOB - 8);
        } finally {
            g2.dispose();
        }
    }
}
