package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * `.input--area` — the multi-line field: 关系描述, 会话白名单, a note's body, a
 * contact's notes, and the read-only test-result box.
 *
 * `JTextArea` wraps and scrolls on its own, so only the surface is overridden.
 * {@link #wrapInScroll()} is offered because a text area with a hard height needs
 * a scroll pane, and the scroll pane's own border would otherwise draw a second
 * rectangle inside the field's rounded one.
 */
public class Area extends JTextArea {

    private boolean invalid;
    private String placeholder = "";

    public Area(int rows, int columns) {
        super(rows, columns);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(BorderFactory.createEmptyBorder(Tokens.SP3, Tokens.SP3, Tokens.SP3, Tokens.SP3));
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
                repaint();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                repaint();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                repaint();
            }
        });
        ThemeManager.addListener(this::applyTheme);
        applyTheme();
    }

    /**
     * Grey text shown while the field is empty — the 问 AI box's only place to
     * say that Enter sends and Shift+Enter breaks the line. `JTextArea` has no
     * placeholder of its own, so it is drawn here.
     */
    public Area placeholder(String p) {
        this.placeholder = p == null ? "" : p;
        repaint();
        return this;
    }

    private void applyTheme() {
        Tokens t = Theme.tokens();
        setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        setForeground(isEnabled() ? t.text1() : t.text3());
        // Transparent, not a token colour.
        //
        // The field's own skin (see paintComponent) is what fills this area, and
        // the text UI paints the background property on top of it — as an opaque
        // RECTANGLE, ignoring the rounded corners. Setting it to a token colour
        // therefore squared off the field and put an opaque slab into a
        // per-pixel-translucent window. It must also not be left at the L&F
        // default either, which is WHITE and is what the IME composition repaint
        // falls back on while text is being typed.
        setBackground(new Color(0, 0, 0, 0));
        setCaretColor(t.brand());
        setSelectionColor(t.brandWeakStrong());
        setSelectedTextColor(t.text1());
        repaint();
    }

    public Area invalid(boolean bad) {
        this.invalid = bad;
        repaint();
        return this;
    }

    public String value() {
        return getText();
    }

    public Area text(String v) {
        setText(v == null ? "" : v);
        return this;
    }

    /** Read-only output with a soft surface — the settings test-result box. */
    public Area readOnly() {
        setEditable(false);
        setFocusable(true);
        return this;
    }

    public Area fixedHeight(int h) {
        Dimension d = new Dimension(10, h);
        setPreferredSize(d);
        setMinimumSize(new Dimension(10, h));
        return this;
    }

    public Area grow() {
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        setAlignmentX(LEFT_ALIGNMENT);
        return this;
    }

    /** Wraps this area in a borderless scroll pane sized to [height]. */
    public JScrollPane wrapInScroll(int height) {
        fixedHeight(height);
        JScrollPane sp = new JScrollPane(this);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(BorderFactory.createEmptyBorder());
        Theme.slimScrollBar(sp);
        Dimension d = new Dimension(10, height);
        sp.setPreferredSize(d);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        sp.setAlignmentX(LEFT_ALIGNMENT);
        return sp;
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
                java.awt.Insets in = getInsets();
                // First line only: the hint is one sentence and a wrapped
                // placeholder over the caret reads as content the user typed.
                String shown = placeholder;
                while (fm.stringWidth(shown) > getWidth() - in.left - in.right
                        && shown.length() > 1) {
                    shown = shown.substring(0, shown.length() - 2) + "…";
                }
                gp.drawString(shown, in.left, in.top + fm.getAscent());
            } finally {
                gp.dispose();
            }
        }
    }
}
