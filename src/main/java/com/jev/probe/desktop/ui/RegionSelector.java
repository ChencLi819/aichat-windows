package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.capture.Win32Windows;
import com.jev.probe.desktop.config.CaptureTarget;
import com.jev.probe.desktop.ui.kit.Kit;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fullscreen drag-to-select overlay: the desktop stand-in for Android's
 * "pick the chat area" step, which there was implicit (the accessibility
 * service saw the whole foreground window).
 *
 * Painted from the prototype's `.region-mask` / `.region-hint` / `.region-rect` /
 * `.region-size`: a 45% black mask over the whole desktop, a centred hint until
 * the drag begins, then a brand-coloured frame with a `宽 × 高` badge on it, and
 * everything OUTSIDE the frame pressed further down — the prototype's
 * `box-shadow: 0 0 0 9999px rgba(0,0,0,.38)`. Where the platform allows per-pixel
 * translucency the mask is lifted to fully clear inside the frame instead of
 * layering a second wash over the outside, because the two are the same
 * relationship and only the first lets the user READ the messages they are
 * framing. Where it does not, the outside wash is applied literally.
 *
 * Two things make this work on a desktop with more than one monitor and more
 * than one scale factor, and both were wrong before:
 *
 * 1. ONE overlay PER MONITOR, each sized to its own device's bounds. A single
 *    window spanning the union of every screen has to pick one scale factor,
 *    but on a mixed-DPI desktop the union is not a single coordinate space:
 *    AWT reports the secondary monitor's origin in physical pixels and its size
 *    in device pixels. Mouse positions on the monitor whose scale the window did
 *    not adopt then convert to the wrong place — measured, a selection on the
 *    primary came back shifted by hundreds of pixels.
 * 2. The DRAG GEOMETRY comes from Win32's GetCursorPos, not from AWT's mouse
 *    event coordinates. Physical pixels, the same units the target window's
 *    rectangle is expressed in, with no scale factor to get wrong. AWT's
 *    coordinates are still used for what they are good at — drawing the
 *    rubber-band rectangle inside a single-monitor window.
 *
 * What comes back is not a screen rectangle but a {@link CaptureTarget}: the
 * window the drag happened inside, plus the offset of the selection within it.
 * The rectangle is then re-resolved from the window on every read, so moving,
 * occluding or minimizing the chat cannot invalidate it.
 *
 * {@link #select()} blocks until the user finishes (drag) or cancels (Esc), so
 * it must be called off the event dispatch thread. A caller that ignores this
 * deadlocks the UI thread rather than merely misbehaving: the overlay is opened
 * with `invokeLater` and then waited on.
 */
public final class RegionSelector {

    /**
     * Below this in either axis the release is a stray click rather than a
     * selection, and the drag is treated as a cancellation.
     */
    private static final int MIN_SIDE = 16;

    /** `.region-hint`, and the same words the walkthrough shows on page 3. */
    private static final String HINT = "拖动框住聊天区（整个窗口内部），Esc 取消";

    /** Nothing under the selection is a window worth binding to. */
    private static final String NO_WINDOW =
        "选中的范围里没有找到聊天窗口。请把整个聊天区框在聊天窗口内部（不要框到桌面上）。";

    /** The prototype's `toast("选区太小，已取消")`, carried in Selection.message. */
    private static final String TOO_SMALL = "选区太小，已取消";

    /** `rgba(0, 0, 0, .45)` — `.region-mask`. */
    private static final Color SCRIM = withAlpha(Color.BLACK, 115);
    /** `rgba(0, 0, 0, .38)` — the extra wash `.region-rect`'s shadow casts. */
    private static final Color OUTSIDE = withAlpha(Color.BLACK, 97);
    /** `rgba(0, 0, 0, .72)` — `.region-hint`. */
    private static final Color HINT_BG = withAlpha(Color.BLACK, 184);

    private RegionSelector() {
    }

    /**
     * What the drag produced.
     *
     * @param target the window and the area inside it, or null when the user
     *        cancelled or the drag did not land inside a chat window.
     * @param message why there is no target, when there is something to say —
     *        null for a plain cancellation (Esc), so the caller can stay quiet.
     */
    public record Selection(CaptureTarget target, String message) {
    }

    /** Same colour at a given alpha, for the overlay's translucent washes. */
    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    public static Selection select() {
        Session session = new Session();
        SwingUtilities.invokeLater(() -> show(session));
        try {
            session.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Selection(null, null);
        }
        return new Selection(session.result.get(), session.message.get());
    }

    private static void show(Session session) {
        GraphicsDevice[] devices =
            GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (devices.length == 0) {
            session.finish(null, null);
            return;
        }
        for (GraphicsDevice gd : devices) {
            JWindow window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setBounds(gd.getDefaultConfiguration().getBounds());
            // Per-pixel translucency is what lets the selected area be punched
            // fully clear (so the chat underneath is readable while dragging).
            // Where the platform refuses it, the canvas falls back to the
            // prototype's literal treatment — mask everywhere, and the extra
            // .38 wash over everything outside the frame.
            boolean translucent = true;
            try {
                window.setBackground(new Color(0, 0, 0, 0));
            } catch (Exception e) {
                translucent = false;
                window.setBackground(Theme.tokens().surface0());
            }
            SelectionCanvas canvas = new SelectionCanvas(session, translucent);
            window.setContentPane(canvas);
            session.windows.add(window);
            session.canvases.add(canvas);
        }
        for (JWindow window : session.windows) {
            window.setVisible(true);
            window.toFront();
        }
        // Esc has to reach this app even though the overlay has no title bar and
        // nothing to click: without an explicit focus request the key goes to
        // whatever held focus before, and the overlay can only be left by
        // dragging. Measured: with no request, Esc did nothing at all.
        session.focusFirst();
    }

    /** One selection in progress, shared by every monitor's overlay. */
    private static final class Session {

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<CaptureTarget> result = new AtomicReference<>();
        final AtomicReference<String> message = new AtomicReference<>();
        final List<JWindow> windows = new ArrayList<>();
        final List<SelectionCanvas> canvases = new ArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        /** Set by the first press, so the other monitors drop their hint too. */
        volatile boolean dragging;
        KeyEventDispatcher esc;
        private Win32Windows windows32;

        /** Point the keyboard at the overlay so Esc is heard. */
        void focusFirst() {
            for (SelectionCanvas c : canvases) {
                c.requestFocusInWindow();
            }
            if (!windows.isEmpty()) windows.get(0).requestFocusInWindow();
        }

        /**
         * Redraws every monitor. The rubber band itself is drawn by the monitor
         * the drag started on — see {@link SelectionCanvas#selection} — so the
         * other monitors only have to drop their hint once the drag begins.
         */
        void repaintAll() {
            for (SelectionCanvas c : canvases) {
                c.repaint();
            }
        }

        /**
         * Turn a physical screen rectangle into "this window, this far in".
         *
         * The window is whichever visible top-level window wholly contains the
         * drag — the chat window, because that is what the user framed. A drag
         * over bare desktop finds nothing and says so rather than binding to an
         * arbitrary window.
         */
        CaptureTarget bind(Rectangle physical) {
            Win32Windows.Win win = windows32().containerOf(physical);
            if (win == null) return null;
            Rectangle r = win.rect();
            return new CaptureTarget(win.cls(), win.title(),
                physical.x - r.x, physical.y - r.y, physical.width, physical.height);
        }

        private Win32Windows windows32() {
            if (windows32 == null) windows32 = new Win32Windows();
            return windows32;
        }

        void finish(CaptureTarget target, String why) {
            if (!finished.compareAndSet(false, true)) return;
            KeyboardFocusManager mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            if (esc != null) mgr.removeKeyEventDispatcher(esc);
            for (JWindow w : windows) {
                w.dispose();
            }
            result.set(target);
            message.set(why);
            done.countDown();
        }
    }

    private static final class SelectionCanvas extends JPanel {

        private final Session session;
        private final boolean translucent;

        /** Local (AWT) press point, and its physical twin from GetCursorPos. */
        private Point anchorLocal;
        private Point anchorPhysical;
        /**
         * The rubber band, in THIS window's coordinates.
         *
         * Local rather than shared, deliberately: converting one physical
         * rectangle into every monitor's units is the mixed-DPI arithmetic this
         * class exists to avoid, and the monitors the drag is not on have nothing
         * useful to show anyway. The rectangle that is bound to the window is
         * computed from the physical cursor positions, so it is correct across
         * every monitor regardless of which canvas drew the band.
         */
        private Rectangle selection;

        SelectionCanvas(Session session, boolean translucent) {
            this.session = session;
            this.translucent = translucent;
            setOpaque(false);
            // Focusable, because a panel that cannot take focus cannot ask for
            // it, and without focus this app never sees the Esc key.
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    anchorLocal = e.getPoint();
                    anchorPhysical = Win32Windows.cursorPoint();
                    selection = new Rectangle(anchorLocal);
                    // The hint goes as soon as the drag starts, on this monitor
                    // and on the others — which is why the flag is shared and
                    // every canvas is asked to repaint.
                    session.dragging = true;
                    session.repaintAll();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (anchorLocal == null) return;
                    selection = normalize(anchorLocal, e.getPoint());
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (anchorLocal == null) return;
                    Point endPhysical = Win32Windows.cursorPoint();
                    Rectangle r = anchorPhysical == null || endPhysical == null
                        ? null
                        : normalize(anchorPhysical, endPhysical);
                    // A stray click (not a drag) must not lock in a 2px region.
                    if (r == null) {
                        session.finish(null, null);
                        return;
                    }
                    if (r.width < MIN_SIDE || r.height < MIN_SIDE) {
                        session.finish(null, TOO_SMALL);
                        return;
                    }
                    CaptureTarget target = session.bind(r);
                    session.finish(target, target != null ? null : NO_WINDOW);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            // A dispatcher, not a key binding: the overlay has no title bar, so
            // focus is not guaranteed at the moment Esc is pressed.
            if (session.esc == null) {
                session.esc = e -> {
                    if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        session.finish(null, null);
                        return true;
                    }
                    return false;
                };
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addKeyEventDispatcher(session.esc);
            }
        }

        private static Rectangle normalize(Point a, Point b) {
            int x = Math.min(a.x, b.x);
            int y = Math.min(a.y, b.y);
            return new Rectangle(x, y, Math.abs(a.x - b.x), Math.abs(a.y - b.y));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                int w = getWidth();
                int h = getHeight();
                // `.region-mask`.
                g2.setColor(SCRIM);
                g2.fillRect(0, 0, w, h);

                Rectangle sel = selection;
                if (sel == null || sel.width <= 0 || sel.height <= 0) {
                    // Only until the drag starts: the prototype removes the hint
                    // on mousedown rather than leaving it under the band.
                    if (!session.dragging) drawHint(g2);
                    return;
                }
                if (translucent) {
                    // Clears the mask entirely over the selection. Same idea as
                    // the prototype's 9999px shadow — the frame is where the
                    // desktop is NOT darkened — taken as far as it goes.
                    g2.setComposite(AlphaComposite.Clear);
                    g2.fillRect(sel.x, sel.y, sel.width, sel.height);
                    g2.setComposite(AlphaComposite.SrcOver);
                } else {
                    // No per-pixel translucency, so the mask cannot be lifted
                    // inside; the .38 wash goes over everything outside instead,
                    // which is exactly what the shadow spells out.
                    g2.setColor(OUTSIDE);
                    fillOutside(g2, sel, w, h);
                }

                // `.region-rect { border: 2px solid var(--brand); border-radius: 2px }`,
                // inset by a pixel so both of its pixels land inside the frame.
                g2.setColor(t.brand());
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(sel.x + 1, sel.y + 1, sel.width - 2, sel.height - 2, 4, 4);
                drawSizeLabel(g2, t, sel);
            } finally {
                g2.dispose();
            }
        }

        /** Four rectangles around [sel], each clamped, so nothing is double-drawn. */
        private static void fillOutside(Graphics2D g2, Rectangle sel, int w, int h) {
            g2.fillRect(0, 0, w, Math.max(0, sel.y));
            g2.fillRect(0, sel.y, Math.max(0, sel.x), sel.height);
            int rightX = sel.x + sel.width;
            g2.fillRect(rightX, sel.y, Math.max(0, w - rightX), sel.height);
            int belowY = sel.y + sel.height;
            g2.fillRect(0, belowY, w, Math.max(0, h - belowY));
        }

        /**
         * `.region-size`: a brand pill sitting on top of the frame, `4px 10px`
         * padding, radius 6, fs-note, and clamped to the window like the
         * prototype's `max(0, y - 26)`.
         */
        private void drawSizeLabel(Graphics2D g2, Tokens t, Rectangle sel) {
            String text = sel.width + " × " + sel.height;
            g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
            FontMetrics fm = g2.getFontMetrics();
            int padX = 10;
            int padY = 4;
            int tw = fm.stringWidth(text) + padX * 2;
            int th = fm.getHeight() + padY * 2;
            int x = Math.min(sel.x, Math.max(0, getWidth() - tw));
            int y = Math.max(0, sel.y - th);
            Kit.fill(g2, x, y, tw, th, Tokens.R_SM, t.brand());
            g2.setColor(Color.WHITE);
            g2.drawString(text, x + padX, y + (th + fm.getAscent() - fm.getDescent()) / 2);
        }

        /** `.region-hint`: centred, `12px 22px`, radius 12, fs-subtitle. */
        private void drawHint(Graphics2D g2) {
            g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_SUBTITLE));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(HINT) + 22 * 2;
            int th = fm.getHeight() + 12 * 2;
            int x = (getWidth() - tw) / 2;
            int y = (getHeight() - th) / 2;
            Kit.fill(g2, x, y, tw, th, Tokens.R_LG, HINT_BG);
            g2.setColor(Color.WHITE);
            g2.drawString(HINT, x + 22, y + (th + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}
