package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/**
 * `.seg` — the segmented control: the 「AI 分析 | 问 AI」 switch, the five-page
 * settings nav's cousin, the 外观 theme picker, and the knowledge-base tabs.
 *
 * The active item sits on `surface-0` and goes semibold, on a `surface-2` track
 * with 3px of padding — the CSS expresses "lifted out of the track", which is
 * what makes the current segment readable at a glance without a colour change.
 */
public class Segmented extends JPanel {

    private static final int TRACK_PAD = 3;
    private static final int ITEM_HEIGHT = 28;
    private static final int TRACK_RADIUS = Tokens.R_MD;
    private static final int ITEM_RADIUS = Tokens.R_SM;

    private final JPanel track;
    private final String[] labels;
    private final Item[] items;
    private int selected;
    private IntConsumer onChange;

    public Segmented(String... labels) {
        this.labels = labels.clone();
        setOpaque(false);
        setLayout(new java.awt.BorderLayout());
        track = new JPanel(new GridLayout(1, labels.length, 2, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Kit.prep(g);
                try {
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), TRACK_RADIUS,
                        Theme.tokens().surface2());
                } finally {
                    g2.dispose();
                }
            }
        };
        track.setOpaque(false);
        items = new Item[labels.length];
        for (int i = 0; i < labels.length; i++) {
            Item item = new Item(i, labels[i]);
            items[i] = item;
            track.add(item);
        }
        add(track, java.awt.BorderLayout.CENTER);
        rebuildBorders();
        ThemeManager.addListener(this::repaint);
    }

    /** The whole control spans its parent — how the panel's tab switch is used. */
    public Segmented fullWidth() {
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        setAlignmentX(LEFT_ALIGNMENT);
        return this;
    }

    public Segmented fixedWidth(int width) {
        Dimension d = new Dimension(width, getPreferredSize().height);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        return this;
    }

    public int selected() {
        return selected;
    }

    public void setSelected(int index) {
        if (index < 0 || index >= items.length) return;
        selected = index;
        for (Item it : items) it.repaint();
    }

    /** Fires only on a real user pick, not on [setSelected]. */
    public Segmented onChange(IntConsumer listener) {
        this.onChange = listener;
        return this;
    }

    public void setItemEnabled(int index, boolean enabled) {
        if (index < 0 || index >= items.length) return;
        items[index].setEnabled(enabled);
    }

    public void setLabel(int index, String label) {
        if (index < 0 || index >= items.length) return;
        items[index].setLabel(label);
        revalidate();
    }

    private void rebuildBorders() {
        // GridLayout gives equal shares; no per-item size juggling needed.
        revalidate();
    }

    private final class Item extends JPanel {

        private final int index;
        private String label;

        Item(int index, String label) {
            this.index = index;
            this.label = label;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!isEnabled() || selected == index) return;
                    selected = index;
                    for (Item it : items) it.repaint();
                    if (onChange != null) onChange.accept(index);
                }
            });
        }

        void setLabel(String l) {
            this.label = l;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                boolean active = selected == index;
                boolean over = isEnabled() && getMousePosition() != null;
                if (active) {
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), ITEM_RADIUS, t.surface0());
                    // A hint of the CSS `--e1` lift, without a real drop shadow.
                    Kit.stroke(g2, 0, 0, getWidth(), getHeight(), ITEM_RADIUS, t.border1());
                } else if (over && isEnabled()) {
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), ITEM_RADIUS, t.surface3());
                }
                Font base = Theme.font(active ? Font.BOLD : Font.PLAIN, Tokens.FS_BODY);
                g2.setFont(base);
                g2.setColor(!isEnabled() ? t.text3() : active ? t.text1() : t.text2());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(label, (getWidth() - fm.stringWidth(label)) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int widest = 0;
        for (String l : labels) {
            widest = Math.max(widest, getFontMetrics(Theme.font(Font.PLAIN, Tokens.FS_BODY))
                .stringWidth(l));
        }
        int itemW = Math.max(48, widest + 12 * 2);
        return new Dimension(itemW * labels.length + 2 * (labels.length - 1) + TRACK_PAD * 2,
            ITEM_HEIGHT + TRACK_PAD * 2);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }
}
