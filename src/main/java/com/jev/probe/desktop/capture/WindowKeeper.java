package com.jev.probe.desktop.capture;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Rectangle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the watched chat window readable while the user has put it away.
 *
 * WHY THIS EXISTS
 *
 * Windows keeps no render target for a minimized or hidden window. Measured on
 * WeChat 4.1.13.65: `PrintWindow` on the minimized window answers 99.1% black and
 * on the hidden one 100% black — there is nothing any API can hand back. So the
 * app used to give up the moment the user minimized, and the panel said so.
 *
 * But a window that is merely parked PAST the edge of the virtual screen is
 * still fully rendered. Measured: with the desktop's right edge at 1920 and the
 * window at x=21920 — completely off screen — `PrintWindow` returned a complete,
 * legible frame; the window kept repainting when a new message arrived; and a
 * poll cost 19-56 ms. Being off screen is not the same as being minimized, and
 * that difference is the whole feature.
 *
 * So: when the user minimizes the window or closes it to the tray, the window is
 * un-minimized, un-hidden and moved past the screen edge — invisible, absent
 * from the taskbar, and readable.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *
 * Fight the user. When the user asks to see the chat — the tray icon, the
 * taskbar button, Alt-Tab — the window is moved back to WHERE IT BELONGED and
 * the keeper stands aside until the user puts it away again.
 *
 * That restore is not a nicety, it is required. Qt re-applies its own remembered
 * geometry whenever the window is shown (measured: a window parked at x=21920
 * came back at x=21920 after a hide/show cycle), so a wake-up would otherwise
 * place the chat off screen and the user would find that their chat had vanished.
 * `home.rect()` is captured fresh every time the window is parked, so "where it
 * belonged" tracks the user moving or resizing the window between parking.
 *
 * HOW THE USER'S INTENT IS RECOGNISED
 *
 * Two signals, because neither is sufficient on its own:
 *   - `EVENT_OBJECT_SHOW` — the tray icon shows the window. Needed because
 *     WeChat's handler may hide-then-show rather than activate.
 *   - `EVENT_SYSTEM_FOREGROUND` — the window came to the front. Needed because a
 *     parked window is already WS_VISIBLE, so a show may not generate a SHOW
 *     event at all; becoming foreground is unambiguous.
 * Both are ignored while `holdUntil` is in the future, because our own
 * `ShowWindow(SW_SHOWNOACTIVATE)` during parking raises them too.
 *
 * IMPLEMENTATION NOTES THAT ARE NOT OBVIOUS
 *
 * - The WinEvent callback ONLY sets a flag. Every window operation happens on the
 *   keeper thread, in the loop that pumps the same message queue. Moving a window
 *   from inside the callback re-enters the hook through the events it causes.
 * - `WINEVENT_OUTOFCONTEXT` is the only mode available without injecting a DLL
 *   into the target process, which this project forbids (CLAUDE.md 硬约束 1).
 *   It delivers events through the installing thread's MESSAGE QUEUE, so the
 *   thread must pump — hence the PeekMessage loop instead of a plain sleep.
 * - The loop also re-checks the invariant on a timer, so an event Windows did not
 *   send cannot leave the chat minimized for ever.
 * - A minimized window is un-minimized BEFORE it is moved: `SetWindowPos` on a
 *   minimized window is ignored (measured: it answers (-32000,-32000) with the
 *   199x34 taskbar-icon rectangle).
 */
public final class WindowKeeper implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /** What the keeper is doing, for the panel to report honestly. */
    public enum Mode {
        /** Not running, or not started yet. */
        OFF,
        /** Parked past the screen edge; the assistant can read the window. */
        PARKED,
        /** The user brought the chat back; the keeper is staying out of the way. */
        YIELDED,
        /** The window is gone — the chat app was closed. */
        LOST
    }

    /** Called on the keeper thread whenever [Mode] changes. */
    public interface Listener {
        void onMode(Mode mode, String detail);
    }

    // ------------------------------------------------------------------ events

    private static final int EVENT_SYSTEM_FOREGROUND = 0x0003;
    private static final int EVENT_SYSTEM_MINIMIZESTART = 0x0016;
    private static final int EVENT_SYSTEM_MINIMIZEEND = 0x0017;
    private static final int EVENT_OBJECT_DESTROY = 0x8001;
    private static final int EVENT_OBJECT_SHOW = 0x8002;
    private static final int EVENT_OBJECT_HIDE = 0x8003;

    private static final int WINEVENT_OUTOFCONTEXT = 0x0000;
    private static final int WINEVENT_SKIPOWNPROCESS = 0x0002;

    private static final int PM_REMOVE = 0x0001;

    /**
     * How long after the keeper itself moves the window that its position and its
     * show events are still attributed to the keeper rather than to the user.
     */
    private static final long HOLD_NANOS = 900_000_000L;

    /** The invariant is re-checked at least this often, events or not. */
    private static final long RECHECK_NANOS = 300_000_000L;

    /** Cadence while the window is on screen and we are staying out of the way. */
    private static final long IDLE_TICK_MS = 120;

    /** Cadence while the window is parked or missing. */
    private static final long TICK_MS = 60;

    /** How long to wait before looking for a missing window again. */
    private static final long RELOOK_NANOS = 1_000_000_000L;

    private interface U32x extends StdCallLibrary {
        U32x INSTANCE = Native.load("user32", U32x.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean PeekMessageW(Pointer msg, HWND hwnd, int min, int max, int remove);

        boolean TranslateMessage(Pointer msg);

        Pointer DispatchMessageW(Pointer msg);

        Pointer SetWinEventHook(int min, int max, Pointer hmod, WinEventProc cb,
                                int pid, int tid, int flags);

        boolean UnhookWinEvent(Pointer hook);

        int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);

        HWND GetForegroundWindow();
    }

    /** The OUTOFCONTEXT signature. It may only observe, and must return fast. */
    public interface WinEventProc extends StdCallLibrary.StdCallCallback {
        void invoke(Pointer hook, int event, Pointer hwnd, int idObject, int idChild,
                    int idEventThread, int dwmsEventTime);
    }

    // ------------------------------------------------------------------- state

    private final Win32Windows windows = new Win32Windows();
    private final String windowClass;
    private final String windowTitle;
    private final Listener listener;

    private volatile Thread thread;
    private volatile boolean running;

    /** Written by the hook callback, read by the keeper loop. */
    private volatile boolean dirty;

    // Everything below is touched only by the keeper thread.
    private Win32Windows.Win win;
    private Win32Windows.Home home;
    private Rectangle parkedRect;
    private boolean userWantsToSee;
    /** Last known "is the watched window foreground" — edge detection. */
    private boolean fgSeen;
    private int hookedPid = -1;
    private Pointer hookMotion;
    private Pointer hookObject;
    /** Strong reference: a collected callback would crash the JNA call. */
    private WinEventProc callback;
    private long holdUntil;
    private long nextRecheck;
    private Mode mode = Mode.OFF;

    public WindowKeeper(String windowClass, String windowTitle, Listener listener) {
        this.windowClass = windowClass;
        this.windowTitle = windowTitle;
        this.listener = listener;
    }

    public Mode mode() {
        return mode;
    }

    // ----------------------------------------------------------------- thread

    public synchronized void start() {
        if (!Win32Windows.supported() || running) return;
        running = true;
        thread = new Thread(this::loop, "window-keeper");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void loop() {
        // A 256-byte scratch buffer for MSG. The fields are never read, only the
        // message is pumped, so the struct layout does not have to be modelled.
        Memory msg = new Memory(256);
        try {
            while (running) {
                pump(msg);

                boolean changed = dirty || System.nanoTime() >= nextRecheck;
                dirty = false;
                if (changed) {
                    enforce();
                } else if (userWantsToSee) {
                    // A signal whose hold window had not expired when it arrived.
                    enforce();
                }

                long tick = mode == Mode.PARKED || mode == Mode.LOST
                    ? TICK_MS : IDLE_TICK_MS;
                if (!sleepQuietly(tick)) return;
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "窗口守护线程异常退出", t);
        } finally {
            release();
        }
    }

    private void pump(Memory msg) {
        // Bounded, so a flood of events cannot starve the invariant check.
        for (int i = 0; i < 64; i++) {
            if (!U32x.INSTANCE.PeekMessageW(msg, null, 0, 0, PM_REMOVE)) return;
            U32x.INSTANCE.TranslateMessage(msg);
            U32x.INSTANCE.DispatchMessageW(msg);
        }
    }

    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // -------------------------------------------------------------- invariant

    /**
     * Restore the one invariant the rest of the app relies on: the watched window
     * is visible to Windows, is not minimized, and is either parked past the
     * screen edge or exactly where the user left it.
     *
     * Keeper thread only.
     */
    private void enforce() {
        nextRecheck = System.nanoTime() + RECHECK_NANOS;

        if (win == null || !windows.alive(win)) {
            win = windows.resolve(windowClass, windowTitle);
            if (win == null) {
                if (mode != Mode.LOST) {
                    home = null;
                    parkedRect = null;
                    publish(Mode.LOST, "找不到窗口「" + windowTitle + "」，它可能已经关闭。");
                }
                nextRecheck = System.nanoTime() + RELOOK_NANOS;
                return;
            }
        }
        ensureHook();

        // Foreground is tracked as an EDGE, not a state. A state check is not
        // enough: while the window is being parked it may be foreground for a
        // moment, and a state check would read that as the user asking to see it
        // and hand it straight back.
        HWND fg = U32x.INSTANCE.GetForegroundWindow();
        boolean fgNow = fg != null && Pointer.nativeValue(fg.getPointer())
            == Pointer.nativeValue(win.hwnd().getPointer());
        boolean fgRose = fgNow && !fgSeen;
        fgSeen = fgNow;
        boolean wants = userWantsToSee || (fgRose && !holding());
        userWantsToSee = false;

        if (windows.isIconic(win) || !windows.isVisible(win)) {
            // The user put it away. This is the case the class exists for.
            parkHere();
            return;
        }

        if (wants) {
            // The user asked to see the chat. Give it back where it belongs —
            // Qt remembers the parked coordinates, so without this the window
            // would come back off screen and look as if it had vanished.
            wakeHome();
            publish(Mode.YIELDED, null);
            nextRecheck = System.nanoTime() + RELOOK_NANOS;
            return;
        }

        Rectangle now = windows.rectOf(win);
        if (now == null) return;

        if (parkedRect != null && Win32Windows.sameRect(now, parkedRect)) {
            publish(Mode.PARKED, null);
            return;
        }

        if (holding()) return;

        if (home == null) {
            // First sight of a window the user has left on screen. Adopt where it
            // is and leave it alone: parking is a reaction to the user putting the
            // chat away, not something done to a window they are using.
            home = windows.home(win);
            if (home != null) parkedRect = windows.parkRect(home);
            publish(Mode.YIELDED, null);
            return;
        }

        if (Win32Windows.sameRect(now, home.rect())) {
            publish(Mode.YIELDED, null);
            return;
        }

        // Visible, somewhere that is neither the parked spot nor home. Treat it
        // as the user having moved the chat, and keep hands off.
        home = windows.home(win);
        if (home != null) parkedRect = windows.parkRect(home);
        publish(Mode.YIELDED, null);
    }

    private boolean holding() {
        return System.nanoTime() < holdUntil;
    }

    /**
     * Move the window past the screen edge.
     *
     * [home] and [parkedRect] are re-read whenever the window is not already
     * parked, so a window the user moved or resized while it was on screen is
     * parked at the size it has now and restored where the user left it.
     */
    private void parkHere() {
        if (mode != Mode.PARKED || parkedRect == null) {
            Win32Windows.Home h = windows.home(win);
            if (h == null) return;
            home = h;
            parkedRect = windows.parkRect(h);
        }
        holdUntil = System.nanoTime() + HOLD_NANOS;
        if (!windows.place(win, parkedRect)) {
            LOG.log(Level.FINE, "停到屏外失败：{0}", win.title());
        }
        publish(Mode.PARKED, null);
    }

    /** Put the window back exactly where the user had it. */
    private void wakeHome() {
        if (home == null) return;
        Rectangle now = windows.rectOf(win);
        if (Win32Windows.sameRect(now, home.rect())) return;
        holdUntil = System.nanoTime() + HOLD_NANOS;
        if (!windows.place(win, home.rect())) {
            LOG.log(Level.FINE, "唤醒回原位失败：{0}", win.title());
            return;
        }
        // [home] still says "it was minimized when we took it over", which is no
        // longer true: the user is looking at it now. Re-read it, or the exit
        // path would hand back a window the user had already woken up.
        Win32Windows.Home fresh = windows.home(win);
        if (fresh != null) home = fresh;
    }

    private void publish(Mode next, String detail) {
        if (mode == next && detail == null) return;
        mode = next;
        if (listener != null) {
            try {
                listener.onMode(next, detail);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "keeper listener 抛异常", t);
            }
        }
    }

    // ------------------------------------------------------------------ hooks

    /** Install, or re-install after the chat app restarted, the event hooks. */
    private void ensureHook() {
        IntByReference pid = new IntByReference();
        U32x.INSTANCE.GetWindowThreadProcessId(win.hwnd(), pid);
        int wanted = pid.getValue();
        if (wanted > 0 && wanted == hookedPid && hookMotion != null) return;

        unhook();
        if (wanted <= 0) return;

        long watched = Pointer.nativeValue(win.hwnd().getPointer());
        callback = new WinEventProc() {
            @Override
            public void invoke(Pointer h, int event, Pointer hwnd, int idObject,
                               int idChild, int idEventThread, int dwmsEventTime) {
                // Observe only. Doing work here re-enters through the events our
                // own window operations generate.
                dirty = true;
                if (hwnd == null || Pointer.nativeValue(hwnd) != watched) return;
                if (event != EVENT_SYSTEM_FOREGROUND && event != EVENT_OBJECT_SHOW) return;
                // `holdUntil` is written by this same thread (parking happens in
                // the loop, and this callback is called from the pump inside it),
                // so the check is race-free. Without it, the
                // ShowWindow(SW_SHOWNOACTIVATE) inside `place()` raises a SHOW
                // event of our own, which would then be honoured as "the user
                // wants to see the chat" one hold-window later.
                if (System.nanoTime() >= holdUntil) userWantsToSee = true;
            }
        };
        int flags = WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS;
        // One wide range covers foreground / minimize-start / minimize-end, which
        // is everything needed for "the user put it away" and "the user wants it
        // back"; it is narrowed to the chat process by `idProcess`.
        hookMotion = U32x.INSTANCE.SetWinEventHook(EVENT_SYSTEM_FOREGROUND,
            EVENT_SYSTEM_MINIMIZEEND, null, callback, wanted, 0, flags);
        hookObject = U32x.INSTANCE.SetWinEventHook(EVENT_OBJECT_DESTROY,
            EVENT_OBJECT_HIDE, null, callback, wanted, 0, flags);
        hookedPid = wanted;
        LOG.log(Level.FINE, "窗口守护已挂钩 pid={0}", wanted);
    }

    private void unhook() {
        if (hookMotion != null) {
            U32x.INSTANCE.UnhookWinEvent(hookMotion);
            hookMotion = null;
        }
        if (hookObject != null) {
            U32x.INSTANCE.UnhookWinEvent(hookObject);
            hookObject = null;
        }
        callback = null;
        hookedPid = -1;
    }

    /** Give the window back to the user in the state it was taken from. */
    private void release() {
        try {
            unhook();
            if (win == null || home == null || !windows.alive(win)) return;
            windows.place(win, home.rect());
            if (home.iconic()) {
                windows.minimize(win);
            } else if (!home.visible()) {
                windows.hide(win);
            }
            LOG.log(Level.FINE, "窗口已还原到 {0}", home.rect());
        } catch (Throwable t) {
            LOG.log(Level.FINE, "还原窗口失败", t);
        } finally {
            mode = Mode.OFF;
        }
    }
}
