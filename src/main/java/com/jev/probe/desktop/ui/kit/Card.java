package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;

/**
 * `.card` — `border: 1px solid var(--border-1); border-radius: 12px; padding: 12px`.
 *
 * Used for analysis rounds, candidate replies, route configs, readiness items and
 * the danger zone; the variants are the CSS modifier classes.
 */
public class Card extends Surface {

    private Card(Skin skin) {
        super(skin);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP4, Tokens.SP4, Tokens.SP4, Tokens.SP4));
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** The default: a white card with a hairline outline. */
    public static Card plain() {
        return new Card(Surface.outlined(Tokens.R_LG, true));
    }

    /** `.card--tinted` — the brand wash, no outline. Used for the recommended reply. */
    public static Card tinted() {
        return new Card(Surface.tinted(Tokens.R_LG));
    }

    /** `.card--subtle` — surface-1, no outline. Used for banner rows. */
    public static Card subtle() {
        return new Card(Surface.subtle(Tokens.R_LG));
    }

    /** `.danger-zone` — a red outline on a red wash. */
    public static Card danger() {
        return new Card(Surface.of(Tokens.R_LG, Tokens::dangerWeak, Tokens::danger));
    }

    /** Overrides the default 12px padding. */
    public Card pad(int top, int left, int bottom, int right) {
        setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return this;
    }

    /** `.card--subtle` at the smaller radius used by inline rows. */
    public static Card inline() {
        return new Card(Surface.subtle(Tokens.R_MD));
    }

    /** Keeps a card from being stretched by a BoxLayout. */
    public Card pinHeight() {
        setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        return this;
    }

    /**
     * Every child of a card is a full-width row, and gets the same horizontal
     * alignment as a result.
     *
     * It is not optional: a `BoxLayout` positions its children against a shared
     * alignment line, and a stack that mixes them — a `createVerticalStrut`
     * defaults to CENTER while a label set to LEFT is 0 — lays its children out
     * at neither edge. A card whose text was centred inside its own card while
     * its heading was not is exactly what that looked like.
     */
    @Override
    public java.awt.Component add(java.awt.Component comp) {
        if (comp instanceof JComponent jc) jc.setAlignmentX(LEFT_ALIGNMENT);
        return super.add(comp);
    }

    /** Adds a child and a vertical gap before it, the CSS `gap: 8px` idiom. */
    public Card with(JComponent child, int gap) {
        if (gap > 0) add(Box.createVerticalStrut(gap));
        add(child);
        return this;
    }

    /** A left-aligned, full-width child — BoxLayout's default is centred. */
    public static <T extends JComponent> T wide(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    /** Text at one of the token sizes, for card bodies. */
    public static javax.swing.JLabel text(String s, float size, Tokens unused) {
        javax.swing.JLabel l = new javax.swing.JLabel(s);
        l.setFont(Theme.font(java.awt.Font.PLAIN, size));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }
}
