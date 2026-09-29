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
 * <p>Geometry is taken in PHYSICAL pixels (Win32) and only converted to AWT's
 * logical coordinates at the last moment, through {@link Screens}. Two measured
 * facts force that shape:
 *
 * <ul>
 *   <li>AWT cannot be asked directly on a mixed-DPI desktop. On this machine the
 *       primary monitor is reported as 0…1536 (scale 1.25) while the secondary
 *       one is reported at x = -2560 with a 1707 logical width (scale 1.5), so
 *       the two logical ranges do not meet — the seam is a hole. A drag that
 *       used logical screen coordinates therefore jumped by hundreds of units
 *       when the pointer crossed that seam, and a drag that started on an edge
 *       (a resize) blew the window up to several times its size.</li>
 *   <li>The physical cursor position has no seam, so the drag is computed there
 *       and the resulting physical rectangle is mapped onto the target monitor's
 *       own mapping ({@link Screens.Monitor#toLogical}).</li>
 * </ul>
 *
 * <p>Events are filtered by the WINDOW THAT OWNS THEM, not by the owner chain:
 * {@code SwingUtilities.getWindowAncestor(dialog)} answers the dialog's owner
 * frame (a JDialog's parent is its owner), which used to make the panel treat
 * the settings dialog's drags as its own — dragging the dialog resized the panel
 * at the same time.
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
    /** The window's physical rectangle when the drag started (Win32). */
    private Rectangle startPhysical;
    /** The window's logical size when the drag started — kept constant while moving. */
    private Rectangle startBounds;
    /** DPI scale captured at press, used only on the AWT fallback path. */
    private double startScale = 1.0;

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
     *
     * <p>An event whose source IS another window is never ours — and it must be
     * tested that way round. {@code SwingUtilities.getWindowAncestor(c)} on a
     * child of our dialog answers the dialog, but on the DIALOG ITSELF it walks
     * up the owner chain and answers our frame (a JDialog's parent is its
     * owner). Accepting that made the panel resize itself while the settings
     * dialog was being dragged. An undecorated window delivers its edge and
     * header events with the window as the source, so this is not a corner
     * case: it is every drag of every dialog this app opens.
     */
    private boolean isOurs(MouseEvent me) {
        Component c = me.getComponent();
        if (c == null) return false;
        Window source = c instanceof Window w ? w : SwingUtilities.getWindowAncestor(c);
        return source == window;
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
        // A point outside the window is not a grab zone. Without this, a press
        // from another window whose coordinates happened to convert to
        // (-20, -65) satisfied both "< GRAB" tests and read as a north-west
        // corner grab on a window the pointer was nowhere near.
        if (p.x < 0 || p.y < 0 || p.x >= w || p.y >= h) return 0;
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
     * Taken from the cursor's PHYSICAL position and divided by the window's
     * monitor scale to get back into AWT's units. Only used on the fallback path
     * ([{@link #drag}]) — the normal path works in physical pixels throughout,
     * because dividing by the scale of the monitor the window happens to be on is
     * itself a source of jumps: the scale changes under the window mid-drag.
     */
    private Point delta(MouseEvent me) {
        Point now = Win32Windows.supported() ? Win32Windows.cursorPoint() : null;
        if (now == null || pressOnScreen == null) {
            Point awt = me.getLocationOnScreen();
            return new Point(awt.x - pressOnScreen.x, awt.y - pressOnScreen.y);
        }
        if (pressPhysical == null) pressPhysical = now;
        return new Point(
            (int) Math.round((now.x - pressPhysical.x) / startScale),
            (int) Math.round((now.y - pressPhysical.y) / startScale));
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
        startPhysical = Screens.physicalOf(window);
        startScale = startPhysical == null
            ? Screens.of(window).scale()
            : Screens.atRect(startPhysical).scale();
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
        apply(me.getComponent(), edges);

        Point cursor = Win32Windows.supported() ? Win32Windows.cursorPoint() : null;
        if (startPhysical != null && pressPhysical != null && cursor != null) {
            int dx = cursor.x - pressPhysical.x;
            int dy = cursor.y - pressPhysical.y;
            Rectangle want = moving
                ? moved(startPhysical, dx, dy)
                : resized(startPhysical, dx, dy);
            // 跨屏时的目标屏：按窗口中心所在的那台，把物理矩形折算成该屏的逻辑坐标。
            Screens.Monitor target = Screens.atRect(want);
            window.setBounds(target.toLogical(want));
            return;
        }

        // 没有 Win32（非 Windows / JNA 不可用）：退回纯 AWT 逻辑坐标，
        // 但比例因子沿用按下时的值，避免拖到另一块屏时中途换尺度。
        Point delta = delta(me);
        int dx = delta.x;
        int dy = delta.y;
        if (moving) {
            window.setLocation(startBounds.x + dx, startBounds.y + dy);
            return;
        }
        window.setBounds(edgeBounds(startBounds, edges, dx, dy, 1.0, minWidth, minHeight));
    }

    /**
     * The window's physical rectangle after moving by [dx],[dy] physical pixels.
     *
     * <p>The SIZE is taken from the logical size captured at press, converted with
     * the target monitor's scale. That is what keeps the window's apparent size
     * constant while it crosses a DPI seam: AWT renders a logical size at
     * {@code logical × scale} physical pixels, so holding the logical size holds
     * what the user sees, and the physical footprint only changes by the DPI
     * ratio — the platform-standard behaviour for a DPI-aware application.
     *
     * <p>The result is then nudged back so the window's centre stays inside the
     * target monitor's work area: a drag can never park the window somewhere the
     * user cannot reach its title bar again.
     */
    private Rectangle moved(Rectangle start, int dx, int dy) {
        Rectangle want = new Rectangle(start.x + dx, start.y + dy,
            (int) Math.round(startBounds.width * scaleOf(start, dx, dy)),
            (int) Math.round(startBounds.height * scaleOf(start, dx, dy)));
        Screens.Monitor target = Screens.atRect(want);
        Rectangle work = target.workPhysical();
        int cx = want.x + want.width / 2;
        int cy = want.y + want.height / 2;
        int ncx = Math.max(work.x, Math.min(work.x + work.width, cx));
        int ncy = Math.max(work.y, Math.min(work.y + work.height, cy));
        want.translate(ncx - cx, ncy - cy);
        return want;
    }

    /** 移动目标点的 DPI 比例：窗口中心大致落在哪台屏上，就用哪台屏的比例。 */
    private double scaleOf(Rectangle start, int dx, int dy) {
        Point centre = new Point(start.x + start.width / 2 + dx, start.y + start.height / 2 + dy);
        return Screens.atPoint(centre).scale();
    }

    /**
     * The physical rectangle after dragging the held edges by [dx],[dy].
     *
     * Physical pixels throughout, so the edge follows the hand on both monitors;
     * the minimum size is the logical minimum expressed in the target monitor's
     * pixels, which keeps "cannot be dragged smaller than MIN_WIDTH×MIN_HEIGHT"
     * true in the units the design is written in.
     */
    private Rectangle resized(Rectangle start, int dx, int dy) {
        double scale = scaleOf(start, dx, dy);
        Rectangle phys = edgeBounds(start, edges, dx, dy, scale, minWidth, minHeight);
        Screens.Monitor target = Screens.atRect(phys);
        return edgeBounds(start, edges, dx, dy, target.scale(), minWidth, minHeight);
    }

    /**
     * The pure edge arithmetic, shared by both paths: which edges move, and the
     * clamping that keeps the edge the user is NOT holding still.
     *
     * Package-private and static so the geometry can be unit-tested without a
     * window: pulling the west edge past the minimum width must stop the window
     * rather than slide it, and a north-west corner grab must move the origin.
     *
     * @param scale the target monitor's DPI scale, so the logical minimum is
     *        enforced in the units the design is written in
     */
    static Rectangle edgeBounds(
        Rectangle start, int edges, int dx, int dy, double scale, int minWidth, int minHeight
    ) {
        int x = start.x;
        int y = start.y;
        int w = start.width;
        int h = start.height;
        if ((edges & W) != 0) {
            x = start.x + dx;
            w = start.width - dx;
        }
        if ((edges & E) != 0) w = start.width + dx;
        if ((edges & N) != 0) {
            y = start.y + dy;
            h = start.height - dy;
        }
        if ((edges & S) != 0) h = start.height + dy;

        int minW = Math.max(1, (int) Math.round(minWidth * scale));
        int minH = Math.max(1, (int) Math.round(minHeight * scale));
        if (w < minW) {
            if ((edges & W) != 0) x -= minW - w;
            w = minW;
        }
        if (h < minH) {
            if ((edges & N) != 0) y -= minH - h;
            h = minH;
        }
        return new Rectangle(x, y, w, h);
    }

    private void end() {
        boolean dragged = edges != 0 || moving;
        edges = 0;
        moving = false;
        pressOnScreen = null;
        pressPhysical = null;
        startPhysical = null;
        startBounds = null;
        if (dragged) adaptToScreen();
    }

    /**
     * 拖拽/缩放结束后把窗口收进目标屏：装不下就等比缩小，窗口中心已经跑到
     * 屏幕外才把它拉回来。
     *
     * <p>刻意不做“一律贴回屏内”：Windows 上把窗口拖到屏边、留一部分在屏幕外是
     * 正常的用法，松手时强行推回去会像窗口自己在跳。拖拽本身（{@link #moved}）
     * 已经保证中心留在工作区内，所以窗口永远抓得回来；这里只处理两种真正的坏
     * 状态：比屏幕还大、以及中心不在任何屏幕上（例如中途拔掉了显示器）。
     */
    public void adaptToScreen() {
        if (!window.isDisplayable()) return;
        Rectangle now = window.getBounds();
        Screens.Monitor target = Screens.of(window);
        Rectangle fitted = Screens.fitInto(now, target, minWidth, minHeight);
        Rectangle centre = target.toPhysical(new Rectangle(
            fitted.x + Math.max(1, fitted.width / 2), fitted.y + Math.max(1, fitted.height / 2), 1, 1));
        if (!target.workPhysical().contains(centre)) {
            fitted = Screens.keepInside(fitted, target);
        }
        if (!fitted.equals(now)) window.setBounds(fitted);
    }
}
