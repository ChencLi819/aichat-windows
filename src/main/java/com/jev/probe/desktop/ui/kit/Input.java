package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * `.input` — the single-line text field, drawn to the design system instead of
 * the system look-and-feel.
 *
 * It stays a real `JTextField` so the caret, selection, IME composition and
 * clipboard behaviour keep working; only the background, border and focus halo
 * are replaced. The foreground and caret colours are re-applied on every paint
 * and on every theme change, which is what lets an open dialog switch palette
 * without being rebuilt.
 */
public class Input extends JTextField {

    public static final int HEIGHT = 34;

    private String placeholder = "";
    private boolean invalid;
    private Runnable onChange;
    private Runnable onEnter;

    public Input() {
        this("");
    }

    public Input(String text) {
        super(text);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, Tokens.SP3, 0, Tokens.SP3));
        setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }
        });
        addActionListener(e -> {
            if (onEnter != null) onEnter.run();
        });
        ThemeManager.addListener(this::applyTheme);
        applyTheme();
    }

    private void changed() {
        if (invalid) {
            invalid = false;
            repaint();
        }
        if (onChange != null) onChange.run();
    }

    private void applyTheme() {
        Tokens t = Theme.tokens();
        setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        setForeground(isEnabled() ? t.text1() : t.text3());
        // Transparent, not a token colour.
        //
        // The field paints its own skin in paintComponent, and the text UI paints
        // the background property on top of it — as an opaque RECTANGLE, ignoring
        // the rounded corners, which both squared off the field and put an opaque
        // slab into a per-pixel-translucent window. Nor may it be left at the
        // L&F default, which is WHITE and is what the IME composition repaint
        // falls back on while Chinese text is being typed.
        setBackground(new Color(0, 0, 0, 0));
        setCaretColor(t.brand());
        setSelectionColor(t.brandWeakStrong());
        setSelectedTextColor(t.text1());
        repaint();
    }

    public Input placeholder(String p) {
        this.placeholder = p == null ? "" : p;
        repaint();
        return this;
    }

    public Input onChange(Runnable r) {
        this.onChange = r;
        return this;
    }

    /** Fired on Enter — used by the 问 AI box and the custom-relation field. */
    public Input onEnter(Runnable r) {
        this.onEnter = r;
        return this;
    }

    public Input invalid(boolean bad) {
        this.invalid = bad;
        repaint();
        return this;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public String value() {
        return getText();
    }

    public Input text(String v) {
        setText(v == null ? "" : v);
        return this;
    }

    /** `.input-group` takes the flexible share; the button beside it does not. */
    public Input grow() {
        setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
        setAlignmentX(LEFT_ALIGNMENT);
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(40, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyTheme();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            Kit.fieldSkin(g2, getWidth(), getHeight(), Theme.tokens(),
                isFocusOwner(), invalid, isEnabled());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
        if (getText().isEmpty() && !placeholder.isEmpty()) {
            Graphics2D gp = Kit.prep(g);
            try {
                gp.setFont(getFont());
                gp.setColor(Theme.tokens().text3());
                FontMetrics fm = gp.getFontMetrics();
                gp.drawString(placeholder, getInsets().left,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                gp.dispose();
            }
        }
    }

    /** The caret colour must survive a look-and-feel that repaints over it. */
    @Override
    public Color getForeground() {
        Color fg = super.getForeground();
        if (fg == null) return Theme.tokens().text1();
        return fg;
    }
}
