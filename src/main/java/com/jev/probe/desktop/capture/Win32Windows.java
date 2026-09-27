package com.jev.probe.desktop.capture;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The chat window, addressed by handle rather than by screen rectangle.
 *
 * Why this exists at all: a screen rectangle cannot express "the chat window" on
 * a mixed-DPI desktop. Measured on the development machine — primary 1920x1080
 * at 125% and a 2560x1440 secondary at 150% — AWT reports the primary as
 * 1536x864 device units and the secondary as 1707x960 units whose origin is
 * -2560, i.e. a PHYSICAL x. One coordinate space, two scale factors and two
 * origins from two different systems: a rectangle selected on one monitor is
 * then read back by {@code Robot} through the other monitor's transform, and the
 * capture lands somewhere else entirely. That is why the conversation could be
 * captured on the second monitor and not on the primary one, and why a crop
 * shifted far enough can push the user's own (right-hand, green) bubbles into
 * the left half of the picture where the reader then calls them the other
 * person's.
 *
 * A window handle has none of those problems: its rectangle comes from
 * {@code GetWindowRect} in physical pixels, unambiguous on any monitor at any
 * scale, and the pixels come from {@code PrintWindow}, which renders the window
 * itself — so it is also independent of what happens to be on top of it, and of
 * where the assistant's own panel is parked.
 *
 * Everything here is Windows-only; the app is a Windows desktop tool.
 */
public final class Win32Windows {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /**
     * PrintWindow's "render the whole window including whatever a
     * composition/GPU surface draws" flag. Without it a client that renders
     * through DirectComposition (WeChat 4.x is Qt-based and does) comes back
     * blank — measured: 0.8% non-black, i.e. an empty frame.
     */
    private static final int PW_RENDERFULLCONTENT = 0x00000002;

    /** `WM_PRINT`, and the parts a window may be asked to draw into our DC. */
    private static final int WM_PRINT = 0x0317;
    private static final int PRF_CHECKVISIBLE = 0x00000001;
    private static final int PRF_NONCLIENT = 0x00000002;
    private static final int PRF_CLIENT = 0x00000004;
    private static final int PRF_ERASEBKGND = 0x00000008;
    private static final int PRF_CHILDREN = 0x00000010;
    private static final int PRF_OWNED = 0x00000020;

    private static final int DIB_RGB_COLORS = 0;

    // ------------------------------------------------------ off-screen parking

    private static final int SW_HIDE = 0;
    // SW_RESTORE is deliberately absent: it ACTIVATES the window it restores,
    // which would leave a parked window holding the keyboard focus while sitting
    // invisibly off screen. Everything here restores with SW_SHOWNOACTIVATE.
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int SW_MINIMIZE = 6;

    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;

    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;

    /**
     * How far beyond the virtual screen a parked window is put.
     *
     * It only has to be far enough that no part of the window can touch a
     * monitor on any display layout. Measured on this machine: a window parked at
     * x=21920 — with the desktop's right edge at 1920 — still answered
     * PrintWindow with a complete frame, and kept repainting as its content
     * changed. Anywhere off the virtual screen behaves the same.
     */
    private static final int PARK_MARGIN = 4000;

    // Restoring a window is NOT done here and never to take a picture of it.
    // Taking a picture of a minimized window is impossible (Windows keeps no
    // render target for it) and restoring it just to read it made the chat
    // window pop up over and over. Restoring is the job of `WindowKeeper`, which
    // does it once, in response to the user minimizing, and puts the window
    // straight back off screen — see [parkRect] and [WindowKeeper].

    /** A window whose sampled pixels are at least this uniform is blank. */
    private static final double BLANK_SAME_FRACTION = 0.985;

    private final long ownPid = ProcessHandle.current().pid();

    /** One top-level window, in physical screen pixels. */
    public record Win(HWND hwnd, String title, String cls, Rectangle rect, boolean iconic) {
    }

    // ------------------------------------------------------------------ Win32

    private interface Gdi32 extends StdCallLibrary {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class, W32APIOptions.DEFAULT_OPTIONS);

        HDC CreateCompatibleDC(HDC hdc);

        HBITMAP CreateCompatibleBitmap(HDC hdc, int w, int h);

        WinNT.HANDLE SelectObject(HDC hdc, WinNT.HANDLE obj);

        boolean DeleteDC(HDC hdc);

        boolean DeleteObject(WinNT.HANDLE obj);

        int GetDIBits(HDC hdc, HBITMAP hbm, int start, int lines,
                      Pointer bits, WinGDI.BITMAPINFO bmi, int usage);
    }

    private interface User32x extends StdCallLibrary {
        User32x INSTANCE = Native.load("user32", User32x.class, W32APIOptions.DEFAULT_OPTIONS);

        HDC GetDC(HWND hwnd);

        int ReleaseDC(HWND hwnd, HDC hdc);

        boolean PrintWindow(HWND hwnd, HDC hdc, int flags);

        boolean GetCursorPos(POINT p);

        boolean IsIconic(HWND hwnd);

        boolean IsWindow(HWND hwnd);

        boolean IsWindowVisible(HWND hwnd);

        boolean ShowWindow(HWND hwnd, int cmd);

        boolean SetWindowPos(HWND hwnd, HWND after, int x, int y,
                             int cx, int cy, int flags);
    }

    // ------------------------------------------------------------ enumeration

    /**
     * Every visible top-level window that has a title, in z-order: the window
     * the user is looking at comes first.
     *
     * The order is the whole point. Two chat clients maximised on the same
     * monitor have rectangles that differ by a few pixels, so "which window is
     * the selection inside" cannot be answered by size — it is answered by
     * "which of them is on top", and that is exactly what the enumeration order
     * already encodes.
     *
     * Windows belonging to this process are left out: the floating panel and the
     * selection overlay are windows too, and they are always on top, so leaving
     * them in would make the overlay answer every question about what is under
     * the selection.
     */
    public List<Win> topLevel() {
        List<Win> out = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            if (pid.getValue() == ownPid) return true;
            RECT r = new RECT();
            User32.INSTANCE.GetWindowRect(hwnd, r);
            int w = r.right - r.left;
            int h = r.bottom - r.top;
            if (w < 32 || h < 32) return true;
            String title = text(hwnd);
            if (title.isEmpty()) return true;
            out.add(new Win(hwnd, title, className(hwnd),
                new Rectangle(r.left, r.top, w, h), User32x.INSTANCE.IsIconic(hwnd)));
            return true;
        }, null);
        return out;
    }

    /**
     * The window a saved target refers to, or null when it is gone.
     *
     * Matched on class AND a title substring: the class alone would match a
     * second chat client built with the same toolkit, the title alone is only
     * the contact's name on some clients. Both together, so a window that was
     * moved, resized or maximised between runs still resolves.
     */
    public Win resolve(String cls, String title) {
        List<Win> all = topLevel();
        if (cls != null && !cls.isBlank()) {
            for (Win w : all) {
                if (w.cls().equals(cls) && titleMatches(w.title(), title)) return w;
            }
        }
        for (Win w : all) {
            if (titleMatches(w.title(), title)) return w;
        }
        return null;
    }

    private static boolean titleMatches(String actual, String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        String a = actual.trim();
        String b = wanted.trim();
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /**
     * The window [physical] was drawn inside — the topmost one, since that is
     * the window the user could see while dragging.
     *
     * A window must also be big enough to be a chat client rather than a
     * notification toaster, and must not be minimized (a minimized window is
     * nowhere on screen, so it cannot be what was framed).
     *
     * The selection is allowed to overshoot the window by a little — the drag
     * ends wherever the hand stops, and a couple of pixels past the edge must
     * not lose the chat. Overshooting the whole window does not count: framing
     * bare desktop finds nothing, and the caller says so.
     */
    public Win containerOf(Rectangle physical) {
        long area = (long) physical.width * physical.height;
        if (area <= 0) return null;
        Win best = null;
        for (Win w : topLevel()) {
            Rectangle r = w.rect();
            if (r.width < 200 || r.height < 200 || w.iconic()) continue;
            Rectangle overlap = r.intersection(physical);
            if (overlap.isEmpty()) continue;
            double covered = (double) overlap.width * overlap.height / area;
            if (covered >= 1.0) return w;
            if (covered >= 0.9 && best == null) best = w;
        }
        return best;
    }

    /** The physical position of the mouse — the drag geometry, DPI-free. */
    public static Point cursorPoint() {
        POINT p = new POINT();
        if (!User32x.INSTANCE.GetCursorPos(p)) return null;
        return new Point(p.x, p.y);
    }

    // --------------------------------------------------------------- capture

    /**
     * The window's pixels, rendered by the window itself.
     *
     * @param crop the part to keep, in the window's own pixel space (0,0 = its
     *        top-left corner), or null for the whole window.
     * @throws IOException when the window is gone, or refused to paint — the
     *         caller shows the message verbatim, so it says what to do.
     */
    /**
     * True when the window is minimized.
     *
     * Checked before capturing: a minimized window has no pixels to give, and
     * restoring it purely to read it is what made the chat window pop up over
     * and over. See {@link #capture}.
     */
    public boolean isMinimized(Win w) {
        return User32x.INSTANCE.IsIconic(w.hwnd());
    }

    public BufferedImage capture(Win w, Rectangle crop) throws IOException {
        BufferedImage full = renderOnce(w);
        if (!isBlank(full)) return crop == null ? full : cropTo(full, crop);

        // Blank frame. Second attempt, still without touching the window: ask it to
        // paint itself into our DC directly (WM_PRINT/WM_PRINTCLIENT with every
        // part flag). PrintWindow is documented to do this, but not every toolkit
        // answers the first form; a Qt window that ignores it can still honour the
        // explicit message.
        BufferedImage viaPrint = renderViaPrintMessage(w);
        if (viaPrint != null && !isBlank(viaPrint)) {
            return crop == null ? viaPrint : cropTo(viaPrint, crop);
        }

        // Neither worked. A MINIMIZED window is the usual reason: Windows keeps no
        // render target for it, so no API can hand its content back — asking again
        // cannot help.
        //
        // This used to restore the window briefly (SW_SHOWNOACTIVATE, pushed to the
        // bottom of the z-order, then minimized again). It worked, and it was still
        // wrong: at the default one-second poll the chat window reappeared once a
        // second, which is unusable if you are doing anything else. It is gone for
        // good; the assistant reads a window that is open, and says so when it is
        // not.
        if (User32x.INSTANCE.IsIconic(w.hwnd())) {
            throw new IOException("「" + w.title() + "」已最小化，Windows 不保留最小化窗口的画面。");
        }
        throw new IOException("抓到的是空白画面（窗口「" + w.title() + "」没有绘制内容）");
    }

    // -------------------------------------------------------------- off screen

    /**
     * Where a window sat, and in what state, before it was moved off screen.
     *
     * @param rect the window's normal (restored) geometry, in the same coordinate
     *        space {@code GetWindowRect} answers in.
     */
    public record Home(Rectangle rect, boolean visible, boolean iconic) {
    }

    /** Is the handle still a live window? A closed chat window is not. */
    public boolean alive(Win w) {
        return User32x.INSTANCE.IsWindow(w.hwnd());
    }

    public boolean isVisible(Win w) {
        return User32x.INSTANCE.IsWindowVisible(w.hwnd());
    }

    public boolean isIconic(Win w) {
        return User32x.INSTANCE.IsIconic(w.hwnd());
    }

    /** The window's geometry as it is right now, in the same space as {@link #home}. */
    public Rectangle rectOf(Win w) {
        RECT r = new RECT();
        if (!User32.INSTANCE.GetWindowRect(w.hwnd(), r)) return null;
        return new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top);
    }

    /**
     * The window's resting geometry and state — the thing to put back later.
     *
     * Read from the PLACEMENT when the window is minimized. {@code GetWindowRect}
     * on a minimized window answers (-32000,-32000) with the size of its taskbar
     * icon, and a "remember then restore" round trip built on that puts the chat
     * in the corner of the desktop — measured, 199x34.
     *
     * @return null when neither call succeeded (the window is already gone).
     */
    public Home home(Win w) {
        boolean iconic = User32x.INSTANCE.IsIconic(w.hwnd());
        RECT r = new RECT();
        boolean ok;
        if (iconic) {
            WinUser.WINDOWPLACEMENT p = new WinUser.WINDOWPLACEMENT();
            p.length = p.size();
            ok = User32.INSTANCE.GetWindowPlacement(w.hwnd(), p).booleanValue();
            if (ok) r = p.rcNormalPosition;
        } else {
            ok = User32.INSTANCE.GetWindowRect(w.hwnd(), r);
        }
        if (!ok) return null;
        int width = r.right - r.left;
        int height = r.bottom - r.top;
        if (width <= 0 || height <= 0) return null;
        return new Home(new Rectangle(r.left, r.top, width, height),
            User32.INSTANCE.IsWindowVisible(w.hwnd()), iconic);
    }

    /**
     * Put the window exactly here, un-minimizing it first when it was minimized.
     *
     * `SW_SHOWNOACTIVATE` rather than `SW_RESTORE`, and that is not cosmetic:
     * `SW_RESTORE` ACTIVATES the window, so a parked window would come back
     * holding the keyboard focus while sitting invisibly off screen — the user's
     * typing would vanish into it. It also makes the window foreground, which
     * {@code WindowKeeper} reads as "the user wants to see this" and would undo
     * the parking a second later. Measured both ways.
     *
     * `SetWindowPos` on a minimized window is ignored — it answers
     * (-32000,-32000) with the 199x34 icon rectangle — so the show comes first.
     */
    public boolean place(Win w, Rectangle r) {
        User32x.INSTANCE.ShowWindow(w.hwnd(), SW_SHOWNOACTIVATE);
        return User32x.INSTANCE.SetWindowPos(w.hwnd(), null, r.x, r.y, r.width, r.height,
            SWP_NOZORDER | SWP_NOACTIVATE);
    }

    /**
     * Where a window whose home is [home] gets parked: past the right edge of the
     * virtual screen, keeping its size, out of sight and out of the taskbar.
     *
     * Derived from {@code home} rather than from the window's present position —
     * reading the present position would add another {PARK_MARGIN} on every call
     * and walk the window off to infinity.
     *
     * The screen metric can be virtualized when the process is not DPI aware, so
     * the window's own right edge is taken as a second candidate and the larger
     * of the two wins: whichever space the metrics are in, the result is off
     * screen.
     */
    public Rectangle parkRect(Home home) {
        int screenRight = User32.INSTANCE.GetSystemMetrics(SM_XVIRTUALSCREEN)
            + User32.INSTANCE.GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int screenTop = User32.INSTANCE.GetSystemMetrics(SM_YVIRTUALSCREEN);
        Rectangle h = home.rect();
        int x = Math.max(screenRight, h.x + h.width) + PARK_MARGIN;
        return new Rectangle(x, Math.max(screenTop, 0), h.width, h.height);
    }

    /** True when [r] is already at the parked position, so nothing has to move. */
    public static boolean sameRect(Rectangle a, Rectangle b) {
        return a != null && b != null && a.x == b.x && a.y == b.y
            && a.width == b.width && a.height == b.height;
    }

    /** Put the window back on the taskbar. Used only to restore a prior state. */
    public boolean minimize(Win w) {
        return User32x.INSTANCE.ShowWindow(w.hwnd(), SW_MINIMIZE);
    }

    /** Take the window off screen and off the taskbar. Used to restore a prior state. */
    public boolean hide(Win w) {
        return User32x.INSTANCE.ShowWindow(w.hwnd(), SW_HIDE);
    }

    /**
     * The second, non-intrusive render attempt: WM_PRINT with every flag the
     * window may act on. It never shows, moves, activates or restores anything.
     */
    private BufferedImage renderViaPrintMessage(Win w) {
        RECT r = new RECT();
        if (User32x.INSTANCE.IsIconic(w.hwnd())) {
            WinUser.WINDOWPLACEMENT p = new WinUser.WINDOWPLACEMENT();
            p.length = p.size();
            if (!User32.INSTANCE.GetWindowPlacement(w.hwnd(), p).booleanValue()) return null;
            r = p.rcNormalPosition;
        } else {
            User32.INSTANCE.GetWindowRect(w.hwnd(), r);
        }
        int width = r.right - r.left;
        int height = r.bottom - r.top;
        if (width <= 0 || height <= 0 || width > 20000 || height > 20000) return null;

        HDC screen = User32x.INSTANCE.GetDC(null);
        HDC memDc = Gdi32.INSTANCE.CreateCompatibleDC(screen);
        HBITMAP bitmap = Gdi32.INSTANCE.CreateCompatibleBitmap(screen, width, height);
        WinNT.HANDLE old = Gdi32.INSTANCE.SelectObject(memDc, bitmap);
        try {
            long hdc = Pointer.nativeValue(memDc.getPointer());
            long parts = PRF_CHECKVISIBLE | PRF_NONCLIENT | PRF_CLIENT
                | PRF_ERASEBKGND | PRF_CHILDREN | PRF_OWNED;
            User32.INSTANCE.SendMessage(w.hwnd(), WM_PRINT,
                new WinDef.WPARAM(hdc), new WinDef.LPARAM(parts));
            return toImage(memDc, bitmap, width, height);
        } catch (Exception e) {
            LOG.log(Level.FINE, "WM_PRINT 渲染失败", e);
            return null;
        } finally {
            Gdi32.INSTANCE.SelectObject(memDc, old);
            Gdi32.INSTANCE.DeleteObject(bitmap);
            Gdi32.INSTANCE.DeleteDC(memDc);
            User32x.INSTANCE.ReleaseDC(null, screen);
        }
    }

    /** One PrintWindow into a 32-bit top-down DIB, as a BufferedImage. */
    private BufferedImage renderOnce(Win w) throws IOException {
        // A minimized window reports (-32000,-32000); the placement carries the
        // geometry it will be restored to, and PrintWindow needs a surface that
        // big even to attempt a render.
        RECT r = new RECT();
        if (User32x.INSTANCE.IsIconic(w.hwnd())) {
            WinUser.WINDOWPLACEMENT p = new WinUser.WINDOWPLACEMENT();
            p.length = p.size();
            if (!User32.INSTANCE.GetWindowPlacement(w.hwnd(), p).booleanValue()) {
                throw new IOException("读不到窗口位置：" + w.title());
            }
            r = p.rcNormalPosition;
        } else {
            User32.INSTANCE.GetWindowRect(w.hwnd(), r);
        }
        int width = r.right - r.left;
        int height = r.bottom - r.top;
        if (width <= 0 || height <= 0 || width > 20000 || height > 20000) {
            throw new IOException("窗口尺寸异常：" + width + "x" + height);
        }

        HDC screen = User32x.INSTANCE.GetDC(null);
        HDC memDc = Gdi32.INSTANCE.CreateCompatibleDC(screen);
        HBITMAP bitmap = Gdi32.INSTANCE.CreateCompatibleBitmap(screen, width, height);
        WinNT.HANDLE old = Gdi32.INSTANCE.SelectObject(memDc, bitmap);
        try {
            if (!User32x.INSTANCE.PrintWindow(w.hwnd(), memDc, PW_RENDERFULLCONTENT)) {
                throw new IOException("系统拒绝捕获窗口「" + w.title() + "」（PrintWindow 失败）");
            }
            return toImage(memDc, bitmap, width, height);
        } finally {
            Gdi32.INSTANCE.SelectObject(memDc, old);
            Gdi32.INSTANCE.DeleteObject(bitmap);
            Gdi32.INSTANCE.DeleteDC(memDc);
            User32x.INSTANCE.ReleaseDC(null, screen);
        }
    }

    /** The DC's contents as a 32-bit top-down image. */
    private static BufferedImage toImage(HDC memDc, HBITMAP bitmap, int width, int height)
            throws IOException {
        WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height; // negative = top-down rows
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
        Memory mem = new Memory((long) width * height * 4);
        if (Gdi32.INSTANCE.GetDIBits(memDc, bitmap, 0, height, mem, bmi, DIB_RGB_COLORS) == 0) {
            throw new IOException("读取窗口像素失败");
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, mem.getIntArray(0, width * height), 0, width);
        return img;
    }

    /**
     * Whether the frame holds nothing. A window that is not being painted does
     * not come back transparent, it comes back filled with one flat colour — so
     * the test is "is essentially the whole frame one colour".
     *
     * Against the MOST COMMON colour, not against pixel (0,0): a real frame of a
     * not-yet-painted window still has a border pixel or two in a different
     * shade, and measuring from the corner let a completely white frame — the
     * exact thing this test exists to catch — count as content.
     */
    static boolean isBlank(BufferedImage img) {
        Map<Integer, Integer> counts = new HashMap<>();
        int sampled = 0;
        int most = 0;
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                int c = img.getRGB(x, y) & 0x00FFFFFF;
                int n = counts.merge(c, 1, Integer::sum);
                sampled++;
                if (n > most) most = n;
            }
        }
        return sampled == 0 || (double) most / sampled >= BLANK_SAME_FRACTION;
    }

    /** [crop] clipped to the frame, so a window resized since selection still works. */
    static BufferedImage cropTo(BufferedImage src, Rectangle crop) {
        int x = Math.max(0, Math.min(crop.x, src.getWidth() - 1));
        int y = Math.max(0, Math.min(crop.y, src.getHeight() - 1));
        int w = Math.max(1, Math.min(crop.width, src.getWidth() - x));
        int h = Math.max(1, Math.min(crop.height, src.getHeight() - y));
        if (x == 0 && y == 0 && w == src.getWidth() && h == src.getHeight()) return src;
        return src.getSubimage(x, y, w, h);
    }

    private static String text(HWND hwnd) {
        char[] buf = new char[512];
        int n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
        return n > 0 ? Native.toString(buf).trim() : "";
    }

    private static String className(HWND hwnd) {
        char[] buf = new char[256];
        User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return Native.toString(buf);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("捕获被中断", e);
        }
    }

    /** True when this machine can do any of it. */
    public static boolean supported() {
        try {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        } catch (Exception e) {
            LOG.log(Level.FINE, "os check failed", e);
            return false;
        }
    }
}
