package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * `.toast` — the transient confirmation at the bottom of the window:
 * 「已复制」, 「已暂停监听」, 「设置已保存」.
 *
 * A separate always-on-top window rather than a layer inside the panel, so the
 * same call works from the floating panel (a translucent undecorated frame) and
 * from a modal dialog without either having to reserve layout space. It is
 * non-focusable and never intercepts clicks, so it cannot get in the way of the
 * action it is confirming.
 */
public final class Toast {

    private static final int LIFETIME_MS = 1800;

    private Toast() {
    }

    public static void show(Window owner, String message) {
        if (message == null || message.isBlank()) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(owner, message));
            return;
        }
        JLabel label = new JLabel(message) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = Kit.prep(g);
                try {
                    Tokens t = Theme.tokens();
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_MD, t.text1());
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        label.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        label.setForeground(Theme.tokens().surface0());
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder(Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));

        JWindow toast = new JWindow(owner);
        try {
            toast.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            // Without per-pixel translucency the rounded corners show a square
            // sliver of the parent colour; acceptable, so carry on.
            toast.getContentPane().setBackground(Theme.tokens().text1());
        }
        toast.setFocusableWindowState(false);
        toast.setAlwaysOnTop(true);
        toast.setContentPane(label);
        toast.pack();

        Point at = anchor(owner, toast.getSize());
        toast.setLocation(at);
        toast.setVisible(true);

        javax.swing.Timer timer = new javax.swing.Timer(LIFETIME_MS, e -> toast.dispose());
        timer.setRepeats(false);
        timer.start();
        toast.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
            }
        });
    }

    /** Bottom-centre of [owner], or of the primary screen when there is none. */
    private static Point anchor(Window owner, Dimension size) {
        java.awt.Rectangle bounds;
        if (owner != null && owner.isShowing()) {
            bounds = owner.getBounds();
        } else {
            bounds = new java.awt.Rectangle(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds());
        }
        int x = bounds.x + (bounds.width - size.width) / 2;
        int y = bounds.y + bounds.height - size.height - Tokens.SP8;
        return new Point(x, y);
    }
}
