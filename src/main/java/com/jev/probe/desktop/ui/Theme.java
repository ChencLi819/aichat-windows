package com.jev.probe.desktop.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared, theme-aware helpers: typography, the scrolling column, the slim
 * scrollbar, and colour arithmetic.
 *
 * There are deliberately no colour constants here any more. The old version
 * exposed 17 `public static Color` fields which components copied at
 * construction, which is precisely why a theme change required a restart — see
 * {@link ThemeManager} and {@link Tokens}. Anything that needs a colour asks
 * {@link #tokens()} while painting.
 */
public final class Theme {

    private Theme() {
    }

    /** The active palette — shorthand, because call sites use it constantly. */
    public static Tokens tokens() {
        return ThemeManager.tokens();
    }

    // ------------------------------------------------------------ typography

    /**
     * The reference's font stack, in the order that matters on Windows:
     * 'Microsoft YaHei UI' / '微软雅黑' for Chinese, then the system UI face.
     */
    private static final String[] FAMILIES = {
        "Microsoft YaHei UI", "微软雅黑", "Microsoft YaHei", "PingFang SC",
        "Segoe UI", "Helvetica", "Arial", "SansSerif"
    };

    private static String resolvedFamily;

    /**
     * A font at one of the design-token sizes, already multiplied by the user's
     * 标准/大 choice so a size preset takes effect everywhere at once.
     */
    public static Font font(int style, float sizePx) {
        return fontScaled(style, sizePx * ThemeManager.fontScale());
    }

    /** A font that ignores the 标准/大 preset — for icons and fixed-size chrome. */
    public static Font fontScaled(int style, float sizePx) {
        if (resolvedFamily == null) resolvedFamily = pickFamily();
        return new Font(resolvedFamily, style, Math.max(1, Math.round(sizePx)));
    }

    private static String pickFamily() {
        Set<String> installed = new HashSet<>(Arrays.asList(
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()));
        for (String f : FAMILIES) {
            if (installed.contains(f)) return f;
        }
        return "SansSerif";
    }

    // ----------------------------------------------------------- containers

    /**
     * A transparent vertical column that takes its viewport's width.
     *
     * A BoxLayout column inside a scroll pane is handed its own preferred width,
     * which is only as wide as its widest child. In a dialog that leaves the form
     * narrow and left-aligned with dead space beside it, and one over-wide child
     * (a row of provider pills, say) instead forces a horizontal scrollbar that
     * pushes the save button out of sight. Reporting
     * {@code getScrollableTracksViewportWidth} makes the viewport stretch the
     * column instead, so the content always spans the full width.
     */
    public static JPanel column() {
        return new Column();
    }

    /** A vertical gap that lines up with the column's children — see [Column.add]. */
    public static JComponent gap(int px) {
        JComponent gap = (JComponent) javax.swing.Box.createVerticalStrut(px);
        gap.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return gap;
    }

    private static final class Column extends JPanel implements Scrollable {

        Column() {
            setOpaque(false);
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        /**
         * A box column's children all line up on the left.
         *
         * `BoxLayout` does not place a cross-axis child at its own
         * `alignmentX`; it positions the whole set against a shared alignment
         * line, so a stack that mixes them — a bare `createVerticalStrut` is
         * CENTER while a label is LEFT — lands everything at a compromise: text
         * indented inside a card that is itself correctly placed, a paragraph
         * hanging past its dialog. Centring is done *inside* a full-width row in
         * this design system, never by aligning the row itself, so pinning the
         * alignment here is safe and makes the stacks deterministic.
         */
        @Override
        public java.awt.Component add(java.awt.Component comp) {
            if (comp instanceof JComponent jc) jc.setAlignmentX(LEFT_ALIGNMENT);
            return super.add(comp);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * Replaces a scroll pane's scrollbars with slim, arrow-less ones.
     *
     * The panel is a narrow column of text, and a stock scrollbar — a 17px track
     * with two chunky arrow buttons — eats a visible slice of it while adding
     * nothing. This is the overlay-style bar tokens.css describes: an 8px track
     * that paints nothing and a rounded thumb that only darkens under the
     * pointer.
     */
    public static void slimScrollBar(JScrollPane sp) {
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setUI(new SlimScrollBarUI());
        bar.setPreferredSize(new Dimension(8, 0));
        bar.setUnitIncrement(16);
        bar.setOpaque(false);
        bar.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(null);
    }

    private static final class SlimScrollBarUI extends BasicScrollBarUI {

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        /** Collapses the arrow buttons to nothing, leaving just the track. */
        private static JButton zeroButton() {
            JButton b = new JButton();
            Dimension none = new Dimension(0, 0);
            b.setPreferredSize(none);
            b.setMinimumSize(none);
            b.setMaximumSize(none);
            b.setFocusable(false);
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle track) {
            // Deliberately empty: a painted track reads as a second border next
            // to the panel's own hairline.
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !c.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = isThumbRollover() || isDragging;
                Tokens t = tokens();
                g2.setColor(active ? t.text3() : alpha(t.border2(), 180));
                int inset = 2;
                int w = r.width - inset * 2;
                g2.fillRoundRect(r.x + inset, r.y, w, r.height, w, w);
            } finally {
                g2.dispose();
            }
        }
    }

    // --------------------------------------------------------------- colours

    /** The same colour at a different alpha — for tints of an opaque token. */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Linear blend; `t` = 0 keeps [a], `t` = 1 returns [b]. */
    public static Color mix(Color a, Color b, float t) {
        return new Color(
            Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
