package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JButton;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * The design system's button: `.btn` from components.css.
 *
 * Drawn entirely by hand — background, glyph, label and focus ring — because a
 * stock `JButton` cannot express the variants or the hover/press colour shifts,
 * and because the label and the glyph have to share one baseline. Preferred size
 * is computed from the same metrics, so a row of buttons lines up exactly.
 */
public class Btn extends JButton {

    /** `.btn--*`. */
    public enum Variant {
        PRIMARY, SECONDARY, GHOST, DANGER, DANGER_SOLID, ICON
    }

    /** `.btn` / `.btn--sm` / `.btn--lg` — height, horizontal padding, label size. */
    public enum Size {
        MD(32, 12, Tokens.FS_BODY),
        SM(28, 8, Tokens.FS_NOTE),
        LG(38, 16, Tokens.FS_SUBTITLE);

        final int height;
        final int padX;
        final float fontSize;

        Size(int height, int padX, float fontSize) {
            this.height = height;
            this.padX = padX;
            this.fontSize = fontSize;
        }
    }

    private static final int ICON_BOX = 28;
    private static final int GAP = 6;

    private final Variant variant;
    private final Size size;
    private final Glyph glyph;
    /** Not final: the 暂停/继续 button swaps its word in place. */
    private String label;
    /** `.btn--icon.is-danger`: hover turns the whole button red. */
    private boolean iconDanger;

    public Btn(String label, Variant variant) {
        this(label, variant, Size.MD, null);
    }

    public Btn(String label, Variant variant, Size size) {
        this(label, variant, size, null);
    }

    public Btn(Glyph glyph, Variant variant) {
        this(null, variant, variant == Variant.ICON ? Size.MD : Size.MD, glyph);
    }

    private Btn(String label, Variant variant, Size size, Glyph glyph) {
        super(label == null ? "" : label);
        this.label = label;
        this.variant = variant;
        this.size = size;
        this.glyph = glyph;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.font(Font.PLAIN, size.fontSize));
        ThemeManager.addListener(this::repaint);
    }

    public Btn iconDanger(boolean on) {
        this.iconDanger = on;
        return this;
    }

    /** Sugar so a call site reads `button.onClick(...)` instead of a listener. */
    public Btn onClick(Runnable action) {
        addActionListener(e -> action.run());
        return this;
    }

    /**
     * Replaces the label.
     *
     * The word is painted from a field rather than from {@code JButton}'s text,
     * so {@code setText} alone would leave the old word on screen — the
     * 暂停/继续 button needs a real relabel.
     */
    public Btn label(String text) {
        this.label = text;
        setText(text == null ? "" : text);
        revalidate();
        repaint();
        return this;
    }

    /** `.btn--block`: stretches to the parent's width in a box layout. */
    public Btn block() {
        setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
        setAlignmentX(LEFT_ALIGNMENT);
        return this;
    }

    /** Keeps a fixed width, for the paired buttons in a dialog footer. */
    public Btn fixedWidth(int width) {
        Dimension d = new Dimension(width, size.height);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        return this;
    }

    // ------------------------------------------------------------- geometry

    @Override
    public Dimension getPreferredSize() {
        if (variant == Variant.ICON && label == null) {
            return new Dimension(ICON_BOX, ICON_BOX);
        }
        FontMetrics fm = getFontMetrics(getFont());
        int w = label == null ? 0 : fm.stringWidth(label);
        if (glyph != null) {
            w += 16 + (label == null ? 0 : GAP);
        }
        return new Dimension(w + size.padX * 2, size.height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension p = getPreferredSize();
        return new Dimension(Math.max(p.width, Integer.MAX_VALUE / 2), p.height);
    }

    // -------------------------------------------------------------- drawing

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        boolean on = isEnabled();
        if (!on) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        }
        try {
            Tokens t = Theme.tokens();
            boolean over = on && getModel().isRollover();
            boolean down = on && getModel().isPressed();
            int w = getWidth();
            int h = getHeight();
            int radius = Tokens.R_MD;

            Color bg = switch (variant) {
                case PRIMARY -> down ? t.brandActive() : over ? t.brandHover() : t.brand();
                case DANGER_SOLID -> down || over ? Theme.mix(t.danger(), Color.BLACK, 0.06f) : t.danger();
                case SECONDARY -> over ? t.surface2() : t.surface0();
                case GHOST -> over ? t.brandWeak() : null;
                case DANGER -> over ? t.dangerWeak() : null;
                case ICON -> iconDanger && over ? t.danger() : over ? t.surface2() : null;
            };
            Color line = switch (variant) {
                case SECONDARY -> t.border2();
                case DANGER -> over ? t.danger() : t.border2();
                default -> null;
            };
            Color fg = switch (variant) {
                case PRIMARY -> t.brandInk();
                case DANGER_SOLID -> Color.WHITE;
                case SECONDARY -> t.text1();
                case GHOST -> t.brand();
                case DANGER -> t.danger();
                case ICON -> iconDanger && over ? Color.WHITE : over ? t.text1() : t.text3();
            };

            if (down) {
                // `.btn:active { transform: translateY(1px) }` — one pixel down.
                g2.translate(0, 1);
            }
            if (variant == Variant.ICON && label == null) {
                bg = switch (variant) {
                    case ICON -> iconDanger && over ? t.danger() : over ? t.surface2() : null;
                    default -> bg;
                };
            }
            Kit.roundRect(g2, 0, 0, w, h, radius, bg, line);

            if (isFocusOwner()) {
                Color ring = t.brandWeakStrong();
                Kit.roundRect(g2, 1, 1, w - 2, h - 2, radius, null, ring);
            }

            g2.setColor(fg);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int textW = label == null ? 0 : fm.stringWidth(label);
            int glyphBox = glyph == null ? 0 : 16;
            int total = textW + glyphBox + (glyph != null && label != null ? GAP : 0);
            int x = Math.max(size.padX, (w - total) / 2);
            int midY = h / 2;

            if (glyph != null) {
                Graphics2D gi = (Graphics2D) g2.create();
                try {
                    gi.translate(x, midY - glyphBox / 2);
                    glyph.paint(gi, glyphBox, fg);
                } finally {
                    gi.dispose();
                }
                x += glyphBox + (label == null ? 0 : GAP);
            }
            if (label != null) {
                g2.drawString(label, x, midY + (fm.getAscent() - fm.getDescent()) / 2);
            }
        } finally {
            g2.dispose();
        }
    }
}
