package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.capture.Win32Windows;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;

/**
 * Edge and corner resizing for an undecorated window, plus dragging it by its
 * title bar — the two things a native frame would have given us for free.
 *
 * <p>Why a global {@link AWTEventListener} instead of listeners on the
 * components: AWT does not bubble mouse events. A listener on the content pane
 * never sees a press that landed on a child, and the panel rebuilds its whole
 * content tree on every analysis round, so attaching to every component would
 * mean re-attaching after every {@code render()}. Listening on the event queue
 * and filtering by window sidesteps both — it stays correct no matter what the
 * content does.
 *
 * <p>Geometry is taken in screen coordinates, not component coordinates: during
 * a drag the window itself moves, so a component-relative delta would feed back
 * into its own result and the window would run away from the cursor.
 *
 * <p>It works on any {@link Window}, not just the panel: the settings and
 * knowledge dialogs are undecorated too and need the same edges, and a
 * {@code JDialog} is not a {@code JFrame}. Callers that create one per dialog
 * MUST {@link #uninstall()} it — see that method.
 */
public final class WindowResizer implements AWTEventListener {

    /** Hot zone at each edge, in pixels. Six is the Windows default. */
    public static final int GRAB = 6;

    /** Edge bits. Public so a surface can draw the matching hint. */
    public static final int N = 1;
    public static final int E = 2;
    public static final int S = 4;
    public static final int W = 8;

    /** The window being resized — the panel's JFrame, or a dialog's JDialog. */
    private final Window window;
    /**
     * Its content pane, cached once: every geometry conversion below is relative
     * to it, and a {@link Window} has no {@code getContentPane()} of its own.
     */
    private final Container content;
    /** Pressing anywhere inside this component drags the window. */
    private final JComponent dragHandle;
    private final int minWidth;
    private final int minHeight;

    /** Edges being dragged, as a bitmask; 0 when not resizing. */
    private int edges;
    private boolean moving;
    private Point pressOnScreen;
    /** The press position in physical pixels — see [delta]. */
    private Point pressPhysical;
    private Rectangle startBounds;

    /** The component whose cursor we borrowed, what it was, and the zone. */
    private Component hot;
    private Cursor hotSaved;
    private int hotZone;
    /** Repaints the window when the hot zone changes; see {@link #onHoverChange}. */
    private Runnable onHover;

    public WindowResizer(Window window, JComponent dragHandle, int minWidth, int minHeight) {
        this.window = window;
        this.content = window instanceof RootPaneContainer r ? r.getContentPane() : window;
        this.dragHandle = dragHandle;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    public void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    /**
     * Stops listening. Mandatory for a window that is created and disposed
     * repeatedly: the listener is registered on the toolkit, so it outlives the
     * dialog it was installed on, and every settings session would otherwise
     * leave another one behind — each still holding a disposed window and still
     * being asked about every mouse event in the app.
     *
     * The panel installs one for the life of the app and never calls this.
     */
    public void uninstall() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }

    /**
     * The edge/corner bitmask the pointer is on right now, or 0.
     *
     * A window that draws the resize hint (FR-PANEL-16) reads this while
     * painting: the cursor alone tells you the window CAN be resized, but not
     * which edge you are about to grab.
     */
    public int hotZone() {
        return hotZone;
    }

    /**
     * Called whenever the hot zone appears, moves or clears, so the window can
     * repaint its hint. Cheap by design — it fires on zone changes, not on every
     * mouse move.
     */
    public WindowResizer onHoverChange(Runnable action) {
        this.onHover = action;
        return this;
    }

    private void notifyHover() {
        if (onHover != null) onHover.run();
    }

    // ------------------------------------------------------------- dispatch

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof MouseEvent me) || !isOurs(me)) return;
        switch (me.getID()) {
            // ENTERED as well as MOVED: a pointer that comes back to rest on the
            // edge without moving would otherwise keep whatever cursor the
            // component was left holding.
            case MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED -> track(me);
            case MouseEvent.MOUSE_PRESSED -> begin(me);
            case MouseEvent.MOUSE_DRAGGED -> drag(me);
            case MouseEvent.MOUSE_RELEASED -> end();
            default -> { }
        }
    }

    /**
     * Whether the event belongs to our window.
     *
     * The window itself has to be accepted explicitly: an event on the window's
     * edge is sourced from the window, not from a child, and
     * {@code getWindowAncestor} of a window is null — so a plain ancestor check
     * silently drops every event in exactly the region this class cares about.
     */
    private boolean isOurs(MouseEvent me) {
        Component c = me.getComponent();
        if (c == null) return false;
        return c == window || SwingUtilities.getWindowAncestor(c) == window;
    }

    // --------------------------------------------------------------- cursor

    private void track(MouseEvent me) {
        apply(me.getComponent(), edgesAt(inFrame(me)));
    }

    /**
     * The event point in the window's own coordinates.
     *
     * {@code getPoint()} is relative to whatever component the event reached —
     * a button, a label, a scroll viewport — so comparing it against the window's
     * width and height would put the hot zones in the wrong place on every child
     * that is not the content pane. The content pane fills an undecorated window,
     * so its coordinates are the window's.
     */
    private Point inFrame(MouseEvent me) {
        return SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), content);
    }

    /**
     * Borrows the component's cursor for the resize arrows and hands it back
     * when the pointer leaves the hot zone. The component has to be tracked
     * rather than the window: AWT resolves the cursor from the deepest component
     * under the pointer, so one set on the content pane is overridden by every
     * child anyway.
     */
    private void apply(Component comp, int zone) {
        if (zone == 0) {
            if (hot != null) {
                hot.setCursor(hotSaved);
                hot = null;
                hotZone = 0;
                // Only a real clearing repaints: this branch is reached on every
                // mouse move that is not on an edge, and most of those changed
                // nothing.
                notifyHover();
            }
            return;
        }
        if (hot == comp && hotZone == zone) return;
        if (hot != null) hot.setCursor(hotSaved);
        if (hot != comp) {
            // getCursor() reports the inherited cursor when none is set; putting
            // that back explicitly looks identical as long as the parent holds still.
            hotSaved = comp.getCursor();
        }
        hot = comp;
        hotZone = zone;
        comp.setCursor(cursorFor(zone));
        notifyHover();
    }

    private int edgesAt(Point p) {
        int w = window.getWidth();
        int h = window.getHeight();
        int e = 0;
        if (p.x < GRAB) e |= W;
        if (p.x >= w - GRAB) e |= E;
        if (p.y < GRAB) e |= N;
        if (p.y >= h - GRAB) e |= S;
        return e;
    }

    private static Cursor cursorFor(int edges) {
        int type = switch (edges) {
            case N -> Cursor.N_RESIZE_CURSOR;
            case S -> Cursor.S_RESIZE_CURSOR;
            case E -> Cursor.E_RESIZE_CURSOR;
            case W -> Cursor.W_RESIZE_CURSOR;
            case N | E -> Cursor.NE_RESIZE_CURSOR;
            case N | W -> Cursor.NW_RESIZE_CURSOR;
            case S | E -> Cursor.SE_RESIZE_CURSOR;
            case S | W -> Cursor.SW_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        };
        return Cursor.getPredefinedCursor(type);
    }

    // ----------------------------------------------------------------- drag

    /**
     * How far the pointer has moved since the press, in the window's own units.
     *
     * Taken from the cursor's PHYSICAL position whenever Windows can supply it,
     * and divided by the window's monitor scale to get back into AWT's units. AWT
     * cannot be asked directly on a mixed-DPI desktop: measured on this machine,
     * the primary monitor is reported as 0…1536 while the secondary one is
     * reported at x = -2560, so the two do not meet — a drag across that seam
     * produces a delta of several hundred units for a few pixels of hand movement,
     * and the window is hurled open to many times its size. A physical cursor
     * position has no seam, so the window keeps tracking the hand on both screens.
     */
    private Point delta(MouseEvent me) {
        Point now = Win32Windows.supported() ? Win32Windows.cursorPoint() : null;
        if (now == null || pressOnScreen == null) {
            Point awt = me.getLocationOnScreen();
            return new Point(awt.x - pressOnScreen.x, awt.y - pressOnScreen.y);
        }
        if (pressPhysical == null) pressPhysical = now;
        double scale = window.getGraphicsConfiguration() == null ? 1.0
            : window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        if (scale <= 0) scale = 1.0;
        return new Point(
            (int) Math.round((now.x - pressPhysical.x) / scale),
            (int) Math.round((now.y - pressPhysical.y) / scale));
    }

    private void begin(MouseEvent me) {
        if (me.getButton() != MouseEvent.BUTTON1) return;
        Component c = me.getComponent();
        Point p = inFrame(me);
        int zone = edgesAt(p);

        if (zone == 0 && !isDragArea(c, p)) return;

        edges = zone;
        moving = zone == 0;
        pressOnScreen = me.getLocationOnScreen();
        pressPhysical = Win32Windows.supported() ? Win32Windows.cursorPoint() : null;
        startBounds = window.getBounds();
        // The arrows must survive the drag: the pointer stays on the edge while
        // the edge moves under it, so the component beneath it keeps changing.
        if (!moving) apply(c, zone);
    }

    /**
     * The title bar drags the window, but its buttons do not — a press that
     * starts on one of them belongs to the button.
     *
     * The test is positional rather than "is this component inside the title
     * bar", because an undecorated window delivers its edge events with the
     * window as the source, not the lightweight child under the pointer: an
     * ancestry check would therefore never see the title bar at all.
     */
    private boolean isDragArea(Component c, Point p) {
        if (c instanceof AbstractButton) return false;
        return dragHandle != null && titleBarBounds().contains(p);
    }

    /** The title bar's rectangle, in the content pane's coordinates. */
    private Rectangle titleBarBounds() {
        Container parent = dragHandle.getParent();
        return parent == null
                ? dragHandle.getBounds()
                : SwingUtilities.convertRectangle(parent, dragHandle.getBounds(), content);
    }

    private void drag(MouseEvent me) {
        if (edges == 0 && !moving) return;
        Point delta = delta(me);
        int dx = delta.x;
        int dy = delta.y;

        if (moving) {
            window.setLocation(startBounds.x + dx, startBounds.y + dy);
            return;
        }

        apply(me.getComponent(), edges);

        int x = startBounds.x;
        int y = startBounds.y;
        int w = startBounds.width;
        int h = startBounds.height;
        if ((edges & W) != 0) {
            x = startBounds.x + dx;
            w = startBounds.width - dx;
        }
        if ((edges & E) != 0) w = startBounds.width + dx;
        if ((edges & N) != 0) {
            y = startBounds.y + dy;
            h = startBounds.height - dy;
        }
        if ((edges & S) != 0) h = startBounds.height + dy;

        // Clamping keeps the edge the user is NOT holding still: pulling the
        // west edge past the minimum width would otherwise slide the whole
        // window right instead of stopping.
        if (w < minWidth) {
            if ((edges & W) != 0) x -= minWidth - w;
            w = minWidth;
        }
        if (h < minHeight) {
            if ((edges & N) != 0) y -= minHeight - h;
            h = minHeight;
        }
        window.setBounds(x, y, w, h);
    }

    private void end() {
        edges = 0;
        moving = false;
        pressOnScreen = null;
        pressPhysical = null;
        startBounds = null;
    }
}
