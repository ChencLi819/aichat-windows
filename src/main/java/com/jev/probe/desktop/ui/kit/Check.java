package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * `.check` — the 16px self-drawn checkbox with a label.
 *
 * Nothing in the prototype uses it yet, but FR-SET-11 requires that the app not
 * mix native and self-drawn controls, so the one place a real checkbox is needed
 * (the destructive-action confirmation) uses this instead of `JCheckBox`.
 */
public class Check extends JComponent {

    private static final int BOX = 16;
    private static final int GAP = 8;

    private boolean checked;
    private String text;
    private Consumer<Boolean> onChange;

    public Check(String text, boolean initial) {
        this.text = text == null ? "" : text;
        this.checked = initial;
        setOpaque(false);
        setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Check.this.checked = !Check.this.checked;
                repaint();
                if (onChange != null) onChange.accept(Check.this.checked);
            }
        });
        ThemeManager.addListener(this::repaint);
    }

    public boolean isChecked() {
        return checked;
    }

    /** Programmatic change; does not fire [onChange]. */
    public Check setChecked(boolean value) {
        checked = value;
        repaint();
        return this;
    }

    public Check onChange(Consumer<Boolean> listener) {
        this.onChange = listener;
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(BOX + GAP + fm.stringWidth(text), Math.max(BOX, fm.getHeight()) + 2);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            Tokens t = Theme.tokens();
            int y = (getHeight() - BOX) / 2;
            Kit.fill(g2, 0, y, BOX, BOX, Tokens.R_XS, checked ? t.brand() : t.surface0());
            Kit.stroke(g2, 0, y, BOX, BOX, Tokens.R_XS, checked ? t.brand() : t.border2());
            if (checked) {
                Graphics2D gi = (Graphics2D) g2.create();
                try {
                    gi.translate(0, y);
                    Glyphs.check().paint(gi, BOX, t.surface0());
                } finally {
                    gi.dispose();
                }
            }
            g2.setFont(getFont());
            g2.setColor(t.text1());
            FontMetrics fm = g2.getFontMetrics();
            int cy = getHeight() / 2;
            g2.drawString(text, BOX + GAP, cy + (fm.getAscent() - fm.getDescent()) / 2);
        } finally {
            g2.dispose();
        }
    }
}
