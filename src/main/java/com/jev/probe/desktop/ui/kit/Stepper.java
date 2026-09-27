package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/**
 * `.stepper` — a `−` button, a narrow numeric input and a `＋` button, drawn as a
 * single control so the three parts share one outline.
 *
 * It deliberately does NOT clamp out-of-range input to the nearest legal value.
 * The old settings form did (`parseInt` fell back to the previous value), which
 * meant a typo was silently discarded; FR-SET-05 asks for the opposite — show
 * the range error immediately and block saving until it is fixed. So a bad value
 * stays on screen, the field goes red, and {@link #isValid()} is false.
 */
public class Stepper extends JComponent {

    private static final int BTN_W = 30;
    private static final int FIELD_W = 56;
    private static final int H = 34;

    private final int min;
    private final int max;
    private final JTextField field;
    private final int base;
    private IntConsumer onChange;
    private boolean hoverLeft;
    private boolean hoverRight;

    public Stepper(int min, int max, int value) {
        this.min = min;
        this.max = Math.max(min, max);
        this.base = value;
        setOpaque(false);
        field = new JTextField(String.valueOf(value));
        field.setOpaque(false);
        field.setBorder(BorderFactory.createEmptyBorder());
        field.setHorizontalAlignment(JTextField.CENTER);
        field.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                notifyChange();
                repaint();
            }
        });
        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean l = e.getX() < BTN_W;
                boolean r = e.getX() > getWidth() - BTN_W;
                if (l != hoverLeft || r != hoverRight) {
                    hoverLeft = l;
                    hoverRight = r;
                    setCursor(Cursor.getPredefinedCursor(l || r ? Cursor.HAND_CURSOR
                        : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverLeft = false;
                hoverRight = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getX() < BTN_W) {
                    step(-1);
                } else if (e.getX() > getWidth() - BTN_W) {
                    step(1);
                } else {
                    field.requestFocusInWindow();
                }
            }
        };
        addMouseListener(hover);
        addMouseMotionListener(hover);
        ThemeManager.addListener(this::restyle);
        restyle();
    }

    private void restyle() {
        field.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        field.setForeground(Theme.tokens().text1());
        field.setCaretColor(Theme.tokens().brand());
        repaint();
    }

    private void step(int delta) {
        int next = parsed() + delta;
        if (next < min || next > max) {
            // Nudge to the legal edge rather than refusing to move at all.
            next = Math.max(min, Math.min(max, next));
        }
        field.setText(String.valueOf(next));
        notifyChange();
        repaint();
    }

    private int parsed() {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (Exception e) {
            return base;
        }
    }

    private void notifyChange() {
        if (onChange != null) onChange.accept(parsed());
    }

    public int value() {
        return parsed();
    }

    public Stepper setValue(int v) {
        field.setText(String.valueOf(v));
        repaint();
        return this;
    }

    /**
     * False while the typed value is outside `min…max` or is not a number.
     *
     * Deliberately NOT called `isValid`: {@link java.awt.Component#isValid()} is
     * AWT's own "this component is laid out" flag, and AWT calls it from inside
     * `JComponent`'s constructor (via `setLocale`) and from every
     * `invalidate()`. Overriding it meant the field was read before it was
     * assigned — a NullPointerException on construction — and, once it existed, a
     * stepper holding a bad number would have reported itself un-laid-out and
     * been revalidated on every pass.
     */
    public boolean inRange() {
        return error() == null;
    }

    /** The inline message for the range rule, or null when the value is fine. */
    public String error() {
        String raw = field.getText().trim();
        if (raw.isEmpty()) return "请填写数字";
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) return "需要在 " + min + "–" + max + " 之间";
            return null;
        } catch (NumberFormatException e) {
            return "请填写数字";
        }
    }

    public Stepper onChange(IntConsumer listener) {
        this.onChange = listener;
        return this;
    }

    /** Live validation hook for the owning `Field`. */
    public Stepper onValidate(java.util.function.Consumer<String> sink) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void push() {
                sink.accept(error());
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                push();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                push();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                push();
            }
        });
        return this;
    }

    @Override
    public void doLayout() {
        int midX = (getWidth() - FIELD_W) / 2;
        field.setBounds(midX, 0, FIELD_W, H);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (field.getParent() != this) add(field);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(BTN_W * 2 + FIELD_W, H);
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
            int w = getWidth();
            String err = error();
            Color outer = err != null ? t.danger() : t.border2();

            Kit.fill(g2, 0, 0, BTN_W + FIELD_W + BTN_W, H, Tokens.R_MD, t.surface0());
            Kit.fill(g2, BTN_W, 0, FIELD_W, H, 0, t.surface1());
            if (hoverLeft) Kit.fill(g2, 1, 1, BTN_W - 1, H - 2, Tokens.R_MD, t.surface2());
            if (hoverRight) {
                Kit.fill(g2, w - BTN_W, 1, BTN_W - 1, H - 2, Tokens.R_MD, t.surface2());
            }
            Kit.stroke(g2, 0, 0, w, H, Tokens.R_MD, outer);
            // The middle section carries no side borders; draw them as hairlines.
            g2.setColor(outer);
            g2.drawLine(BTN_W, 1, BTN_W, H - 2);
            g2.drawLine(w - BTN_W - 1, 1, w - BTN_W - 1, H - 2);

            g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_SUBTITLE));
            g2.setColor(hoverLeft ? t.text1() : t.text2());
            drawCentered(g2, "\u2212", BTN_W / 2, H / 2);
            g2.setColor(hoverRight ? t.text1() : t.text2());
            drawCentered(g2, "\uFF0B", w - BTN_W / 2, H / 2);
        } finally {
            g2.dispose();
        }
    }

    private void drawCentered(Graphics2D g2, String s, int cx, int cy) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s, cx - fm.stringWidth(s) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }
}
