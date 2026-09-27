package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

/**
 * A left-aligned row of small components that spills onto as many lines as it
 * needs — the 要点标签 row under a round's conclusion, and the reply cards'
 * action row at narrow widths.
 *
 * `FlowLayout` looks like the right tool and is not: it reports a single row's
 * preferred size no matter how many lines it actually wraps onto, so inside a
 * vertical box layout it is handed one line's height and every tag past the
 * first line is clipped. At the panel's 360px minimum width that is where most
 * of the tags end up.
 *
 * Sizes are reported against the width the row actually has. That width is only
 * known after the first layout pass, so the height is corrected with one
 * `revalidate()` on the first layout — the standard wrap-layout convergence,
 * guarded by the width so it cannot loop.
 */
public class Wrap extends JPanel {

    /** Used for the first measurement, before the parent has given us a width. */
    private static final int FALLBACK_WIDTH = 280;

    private final int gapX;
    private final int gapY;
    /** Width the current preferred height was computed for. */
    private int measuredWidth = -1;
    /** Guards against queueing the same corrective layout pass more than once. */
    private boolean reflowPending;

    public Wrap() {
        this(Tokens.SP2, Tokens.SP2);
    }

    public Wrap(int gapX, int gapY) {
        this.gapX = gapX;
        this.gapY = gapY;
        setOpaque(false);
    }

    /** The row spans its parent — how every call site uses it. */
    public Wrap span() {
        setAlignmentX(LEFT_ALIGNMENT);
        revalidate();
        return this;
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        if (width > 0 && width != measuredWidth) {
            measuredWidth = width;
            // The children may now need a different number of lines, so the
            // height the parent gave us is stale and the column has to be laid
            // out again. The request is deferred rather than made here: a
            // revalidate from inside a layout pass can re-enter the pass that is
            // running, and a layout that never settles is a window that never
            // finishes painting.
            scheduleReflow();
        }
        place(width);
    }

    private void scheduleReflow() {
        if (reflowPending) return;
        reflowPending = true;
        javax.swing.SwingUtilities.invokeLater(() -> {
            reflowPending = false;
            revalidate();
        });
    }

    @Override
    public Dimension getPreferredSize() {
        int width = measuredWidth > 0 ? measuredWidth
            : (getWidth() > 0 ? getWidth() : FALLBACK_WIDTH);
        // Height for that width, but a minimal preferred WIDTH: the row is
        // stretched by its container, and whatever width it asks for here is what
        // the enclosing column reports as ITS preferred width — which, past the
        // viewport's own width, would stop the viewport tracking it and clip the
        // column at the right edge.
        return new Dimension(1, heightFor(width));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** One pass over the children, laying each at its own preferred size. */
    private void place(int width) {
        int usable = Math.max(1, width);
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (Component c : getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            if (x > 0 && x + d.width > usable) {
                x = 0;
                y += rowHeight + gapY;
                rowHeight = 0;
            }
            c.setBounds(x, y, d.width, d.height);
            x += d.width + gapX;
            rowHeight = Math.max(rowHeight, d.height);
        }
    }

    private int heightFor(int width) {
        int usable = Math.max(1, width);
        int x = 0;
        int lines = 0;
        int rowHeight = 0;
        for (Component c : getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = ((JComponent) c).getPreferredSize();
            if (x > 0 && x + d.width > usable) {
                lines += rowHeight + gapY;
                x = 0;
                rowHeight = 0;
            }
            x += d.width + gapX;
            rowHeight = Math.max(rowHeight, d.height);
        }
        return lines + rowHeight;
    }
}
