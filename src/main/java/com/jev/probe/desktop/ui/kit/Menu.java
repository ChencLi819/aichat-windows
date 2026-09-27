package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.List;

/**
 * `.menu` — the floating list behind the panel's ⋯ button, the relation pill and
 * the relation selects.
 *
 * A borderless popup window instead of `JPopupMenu`: the stock popup paints with
 * the look-and-feel's own border and background, which cannot be themed by
 * swapping colours and would be the one place that still looks like Windows
 * chrome in dark mode. Dismissal is the platform convention — clicking outside
 * or moving focus away — so it behaves the way a menu is expected to.
 */
public final class Menu {

    /** One row: optional glyph, label, enabled flag and action. */
    public record Item(String label, Glyph glyph, boolean enabled, Runnable action) {

        public static Item of(String label, Runnable action) {
            return new Item(label, null, true, action);
        }

        public static Item of(String label, Glyph glyph, Runnable action) {
            return new Item(label, glyph, true, action);
        }

        public static Item disabled(String label) {
            return new Item(label, null, false, null);
        }

        public static Item separator() {
            return new Item(null, null, true, null);
        }
    }

    private static final int MIN_WIDTH = 168;
    private static final int ITEM_HEIGHT = 32;
    private static final int PAD = 4;
    private static final int SEP_HEIGHT = 9;

    // ------------------------------------------------------------- popup state
    //
    // Dismissal used to be delegated entirely to the popup's own
    // `windowLostFocus`. Measured on this JDK/Windows: a JWindow popup owned by
    // the panel NEVER gains focus (`isFocused()` is false for its whole life),
    // so that event never fires — the menu opened once and then could not be
    // closed by any gesture, and every further click on the ⋯ button stacked
    // another popup on top of it. See `MenuDismissProbe2` in the report.
    //
    // So the menu now owns its dismissal explicitly: one popup at a time, an
    // outside mouse press or Escape closes it, and a click on the invoker
    // toggles it shut.

    /** The single live popup, or null. */
    private static Window open;
    /** What the open popup hangs off; null when it is screen-anchored (tray). */
    private static JComponent openInvoker;
    /** The window whose focus loss closes [open] — the invoker's own top level. */
    private static Window openOwner;
    /** Global press/Escape watcher, installed only while a popup is up. */
    private static AWTEventListener watcher;

    /**
     * The invoker's window losing focus means the user moved to another
     * application, so the menu should not stay floating. Skip the case where
     * focus merely moved into the popup itself.
     */
    private static final WindowFocusListener OWNER_FOCUS_LOST = new WindowFocusListener() {
        @Override
        public void windowGainedFocus(WindowEvent e) {
        }

        @Override
        public void windowLostFocus(WindowEvent e) {
            if (e.getOppositeWindow() == open) return;
            close();
        }
    };

    /** Dispose the live popup and unhook everything watching for it. */
    private static void close() {
        Window popup = open;
        if (popup == null) return;
        open = null;
        openInvoker = null;
        if (openOwner != null) {
            openOwner.removeWindowFocusListener(OWNER_FOCUS_LOST);
            openOwner = null;
        }
        if (watcher != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(watcher);
            watcher = null;
        }
        popup.dispose();
    }

    /** Close the live popup, if there is one, without opening anything. */
    public static void dismiss() {
        close();
    }

    /**
     * Presses outside the popup and Escape dismiss it. A press that lands on the
     * invoker is left alone on purpose: the invoker's own click handler runs
     * next and toggles the menu shut, which is what a second click should do.
     */
    private static void watch() {
        watcher = event -> {
            if (open == null) return;
            if (event instanceof MouseEvent me && event.getID() == MouseEvent.MOUSE_PRESSED) {
                Component c = me.getComponent();
                if (c == null || SwingUtilities.isDescendingFrom(c, open)) return;
                if (openInvoker != null && SwingUtilities.isDescendingFrom(c, openInvoker)) return;
                close();
            } else if (event instanceof KeyEvent ke && event.getID() == KeyEvent.KEY_PRESSED
                && ke.getKeyCode() == KeyEvent.VK_ESCAPE) {
                close();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(watcher,
            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    private Menu() {
    }

    /**
     * Show a menu anchored to [invoker]'s bottom-left (or above it when there is
     * no room below), in the same window.
     */
    public static void show(JComponent invoker, List<Item> items) {
        show(invoker, items, 0);
    }

    /**
     * @param rightInset distance from the invoker window's right edge; the
     *        panel's overflow menu sits flush with the footer's padding.
     */
    public static void show(JComponent invoker, List<Item> items, int rightInset) {
        Window owner = SwingUtilities.getWindowAncestor(invoker);
        if (owner == null) return;
        Dimension size = size(items);
        // Already open for this very invoker: the click that got us here is the
        // same click the user means as "put it away".
        if (open != null && openInvoker == invoker) {
            close();
            return;
        }
        show(owner, invoker, anchor(owner, invoker, size, rightInset), size, items);
    }

    /**
     * Show a menu at a screen point rather than off one of our components.
     *
     * <p>For a click that never landed on a component at all: the notification
     * area's icon is a {@link java.awt.TrayIcon}, which is not a
     * {@code Component}, so there is no invoker to anchor to and the pointer's
     * own position is the only anchor there is.
     *
     * <p>The point becomes the menu's BOTTOM-left corner — the way Windows
     * itself anchors a tray menu, so the list grows upwards over the taskbar —
     * clamped to whichever screen the click was on. With no owner window the
     * popup is a plain top-level: dismissal is the outside-press watcher, plus
     * Escape.
     */
    public static void showAt(Point screenPoint, List<Item> items) {
        Dimension size = size(items);
        Rectangle screen = screenOf(screenPoint);
        int x = Math.max(screen.x + 4,
            Math.min(screenPoint.x, screen.x + screen.width - size.width - 4));
        int y = screenPoint.y - size.height;
        if (y < screen.y + 4) y = screenPoint.y;
        if (y + size.height > screen.y + screen.height) {
            y = screen.y + screen.height - size.height - 4;
        }
        show(null, null, new Point(x, y), size, items);
    }

    /** The bounds of the screen holding [p], or of the default screen. */
    private static Rectangle screenOf(Point p) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            if (bounds.contains(p)) return bounds;
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private static Dimension size(List<Item> items) {
        return new Dimension(measure(items) + PAD * 2, preferredHeight(items) + PAD * 2);
    }

    private static void show(Window owner, JComponent invoker, Point location,
                             Dimension size, List<Item> items) {
        // One menu at a time. Anything still up is a menu the user has moved on
        // from, so it goes rather than being buried under the new one.
        close();

        javax.swing.JWindow popup = new javax.swing.JWindow(owner);
        try {
            popup.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            popup.setBackground(Theme.tokens().surface0());
        }
        popup.setFocusableWindowState(true);
        popup.setAlwaysOnTop(true);

        Surface shell = new Surface((g2, w, h, t) -> {
            Kit.fill(g2, 0, 0, w, h, Tokens.R_MD, t.surface0());
            Kit.stroke(g2, 0, 0, w, h, Tokens.R_MD, t.border1());
        });
        shell.setLayout(new BoxLayout(shell, BoxLayout.Y_AXIS));
        shell.setBorder(javax.swing.BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
        shell.setWindowSurface(true);

        for (Item item : items) {
            if (item.label() == null) {
                shell.add(sep());
                continue;
            }
            shell.add(row(item));
        }
        popup.setContentPane(shell);

        popup.setSize(size);
        popup.setLocation(location);

        // Register BEFORE the popup is shown, so the state the watcher reads is
        // already consistent if a press arrives while it is being mapped.
        open = popup;
        openInvoker = invoker;
        openOwner = owner;
        watch();
        if (owner != null) owner.addWindowFocusListener(OWNER_FOCUS_LOST);

        popup.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Reached only for a press on the window itself; a press on the
                // shell's padding targets the shell (see its own listener).
                close();
            }
        });
        // The shell, not the popup, is what a click on the padding actually
        // targets, so the dismiss-on-padding behaviour has to live here.
        shell.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                close();
            }
        });
        popup.setVisible(true);
        popup.toFront();
        popup.requestFocus();
    }

    private static JComponent sep() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Kit.prep(g);
                try {
                    g2.setColor(Theme.tokens().border1());
                    g2.fillRect(0, getHeight() / 2, getWidth(), 1);
                } finally {
                    g2.dispose();
                }
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(10, SEP_HEIGHT));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, SEP_HEIGHT));
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return p;
    }

    private static JComponent row(Item item) {
        JComponent row = new JComponent() {

            private boolean hover;

            {
                setOpaque(false);
                setPreferredSize(new Dimension(10, ITEM_HEIGHT));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, ITEM_HEIGHT));
                setAlignmentX(LEFT_ALIGNMENT);
                setCursor(Cursor.getPredefinedCursor(item.enabled()
                    ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (!item.enabled()) return;
                        // Through close(), not popup.dispose(): the watcher and
                        // the owner's focus listener have to be unhooked too.
                        close();
                        if (item.action() != null) item.action().run();
                    }
                });
                ThemeManager.addListener(this::repaint);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Kit.prep(g);
                try {
                    Tokens t = Theme.tokens();
                    if (hover && item.enabled()) {
                        Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM, t.surface2());
                    }
                    Color fg = item.enabled() ? t.text1() : t.text3();
                    int x = Tokens.SP3;
                    if (item.glyph() != null) {
                        Graphics2D gi = (Graphics2D) g2.create();
                        try {
                            gi.translate(x, (getHeight() - 16) / 2);
                            item.glyph().paint(gi, 16, fg);
                        } finally {
                            gi.dispose();
                        }
                        x += 16 + Tokens.SP3;
                    }
                    g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
                    g2.setColor(fg);
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(item.label(), x,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                } finally {
                    g2.dispose();
                }
            }
        };
        return row;
    }

    private static int measure(List<Item> items) {
        java.awt.Font f = Theme.font(Font.PLAIN, Tokens.FS_BODY);
        java.awt.FontMetrics fm = new javax.swing.JLabel().getFontMetrics(f);
        int widest = MIN_WIDTH;
        for (Item i : items) {
            if (i.label() == null) continue;
            int w = fm.stringWidth(i.label())
                + (i.glyph() != null ? 16 + Tokens.SP3 : 0) + Tokens.SP3 * 2;
            widest = Math.max(widest, w);
        }
        return widest;
    }

    private static int preferredHeight(List<Item> items) {
        int h = 0;
        for (Item i : items) {
            h += i.label() == null ? SEP_HEIGHT : ITEM_HEIGHT;
        }
        return h;
    }

    /**
     * Above the invoker when there is room, below it otherwise — the ⋯ button
     * lives in the panel's footer, so "above" is the normal case.
     */
    private static Point anchor(Window owner, JComponent invoker, Dimension size, int rightInset) {
        Point origin = invoker.getLocationOnScreen();
        int x = rightInset > 0
            ? owner.getLocationOnScreen().x + owner.getWidth() - size.width - rightInset
            : origin.x + invoker.getWidth() - size.width;
        int above = origin.y - size.height - Tokens.SP1;
        int below = origin.y + invoker.getHeight() + Tokens.SP1;
        int ownerTop = owner.getLocationOnScreen().y;
        int y = above >= ownerTop ? above : below;
        return new Point(Math.max(owner.getLocationOnScreen().x + 4, x), y);
    }
}
