package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JPasswordField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * `.input[type=password]` — the key field on the three route cards.
 *
 * The prototype pairs it with a 显示/隐藏 button in an `.input-group` rather than
 * an in-field eye icon, so this is a plain password field plus a
 * {@link #reveal(boolean)} that flips the echo character. The toggle is the
 * caller's button; keeping the mask logic here means the settings dialog never
 * has to know which field type it is holding.
 */
public class Password extends JPasswordField {

    private String placeholder = "";
    private boolean revealed;
    private boolean invalid;

    public Password() {
        this("");
    }

    public Password(String text) {
        super(text);
        setOpaque(false);
        // Nothing may fill this field: the panel is a per-pixel translucent window,
        // so an opaque fill paints over the whole surface instead of the field.
        setBackground(new Color(0, 0, 0, 0));
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
        ThemeManager.addListener(this::applyTheme);
        applyTheme();
    }

    private void applyTheme() {
        Tokens t = Theme.tokens();
        setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        setForeground(isEnabled() ? t.text1() : t.text3());
        setCaretColor(t.brand());
        setSelectionColor(t.brandWeakStrong());
        setSelectedTextColor(t.text1());
        repaint();
    }

    public Password placeholder(String p) {
        this.placeholder = p == null ? "" : p;
        repaint();
        return this;
    }

    /** The 显示/隐藏 toggle. */
    public void reveal(boolean on) {
        this.revealed = on;
        setEchoChar(on ? (char) 0 : '\u2022');
        repaint();
    }

    public boolean isRevealed() {
        return revealed;
    }

    public Password invalid(boolean bad) {
        this.invalid = bad;
        repaint();
        return this;
    }

    public String value() {
        return new String(getPassword());
    }

    public Password text(String v) {
        setText(v == null ? "" : v);
        return this;
    }

    public Password grow() {
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Input.HEIGHT));
        setAlignmentX(LEFT_ALIGNMENT);
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Input.HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(40, Input.HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, Input.HEIGHT);
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
        if (getPassword().length == 0 && !placeholder.isEmpty()) {
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
}
