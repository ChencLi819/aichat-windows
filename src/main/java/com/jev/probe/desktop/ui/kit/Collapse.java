package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * `.collapse` — the 「本轮依据」 and 「思考过程」 disclosure blocks.
 *
 * The body is a real child that is added and removed rather than merely hidden,
 * so a collapsed block costs nothing to lay out; the title carries the message
 * count in the evidence case (`本轮依据 · 5 条消息`), which is why the title is
 * mutable.
 *
 * A toggle notifies its owner, because the owner (the round list) is the one
 * that must remember the open state: FR-PANEL-07 requires the streaming
 * reasoning append not to rebuild the list, and a rebuilt list would otherwise
 * snap every collapse shut.
 */
public class Collapse extends JPanel {

    private static final int HEAD_PAD_Y = 6;

    private final Head head;
    private final JPanel bodyHolder;
    private final JPanel body;
    private boolean open;
    private Runnable onToggle;

    public Collapse(String title) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        head = new Head(title);
        bodyHolder = new JPanel(new java.awt.BorderLayout());
        bodyHolder.setOpaque(false);
        bodyHolder.setAlignmentX(LEFT_ALIGNMENT);
        body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(LEFT_ALIGNMENT);
        bodyHolder.add(body, java.awt.BorderLayout.CENTER);
        add(head);
    }

    public Collapse setTitle(String title) {
        head.title = title;
        head.revalidate();
        head.repaint();
        return this;
    }

    /** The block's content; replaces anything set before. */
    public Collapse setBody(JComponent content) {
        body.removeAll();
        if (content != null) {
            content.setAlignmentX(LEFT_ALIGNMENT);
            body.add(content);
        }
        apply();
        return this;
    }

    public boolean isOpen() {
        return open;
    }

    public Collapse setOpen(boolean value) {
        if (open == value) return this;
        open = value;
        apply();
        head.repaint();
        return this;
    }

    public Collapse onToggle(Runnable listener) {
        this.onToggle = listener;
        return this;
    }

    /** Hides the caret and the head padding — for a block that is always open. */
    public Collapse headless() {
        head.setVisible(false);
        open = true;
        apply();
        return this;
    }

    private void apply() {
        removeAll();
        if (head.isVisible()) add(head);
        if (open) {
            add(bodyHolder);
        }
        revalidate();
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private final class Head extends JComponent {

        private String title;

        Head(String title) {
            this.title = title;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(HEAD_PAD_Y, 0, HEAD_PAD_Y, 0));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    setOpen(!open);
                    if (onToggle != null) onToggle.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    repaint();
                }
            });
            ThemeManager.addListener(this::repaint);
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(font());
            return new Dimension(fm.stringWidth(title) + 4 + 6 + 20,
                fm.getHeight() + HEAD_PAD_Y * 2);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private java.awt.Font font() {
            return Theme.font(Font.PLAIN, Tokens.FS_NOTE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                Color fg = getMousePosition() != null ? t.text1() : t.text2();
                int cy = getHeight() / 2;
                // `border-left: 4px solid currentColor` triangle, rotated when open.
                double cx = 4;
                double half = 4;
                g2.setColor(fg);
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
                if (open) {
                    p.moveTo(cx - half / 2 + 1, cy - half / 2);
                    p.lineTo(cx + half / 2 + 1, cy);
                    p.lineTo(cx - half / 2 + 1, cy + half / 2);
                } else {
                    p.moveTo(cx, cy - half / 2);
                    p.lineTo(cx + half, cy);
                    p.lineTo(cx, cy + half / 2);
                }
                p.closePath();
                g2.fill(p);

                g2.setFont(font());
                g2.setColor(fg);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(title, 14, cy + (fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
