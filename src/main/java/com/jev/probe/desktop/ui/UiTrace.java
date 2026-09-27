package com.jev.probe.desktop.ui;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in watchdog for the two failure modes that both end as "the window went
 * blank / the text came out mangled": a REPAINT storm and a LAYOUT storm.
 *
 * <p>Off unless {@code -Daichat.uitrace=1}. Turn it on, type in the box that
 * misbehaves, and watch the numbers:
 *
 * <pre>
 *   [uitrace] dirty/s=3    invalid/s=0      ← idle, correct
 *   [uitrace] dirty/s=4    invalid/s=1      ← one keystroke, correct
 *   [uitrace] dirty/s=900  invalid/s=780    ← a loop: the same edit is causing
 *                                             hundreds of re-layouts a second
 * </pre>
 *
 * <p>Why this exists rather than a code reading: the blank-window bug in this
 * panel's history was a {@code View.setSize} / layout ping-pong, and the honest
 * way to settle whether a round of that is happening NOW is to count it. Static
 * reading can only prove that a loop is absent from the code paths someone
 * thought of; a count proves it for the paths the app actually took.
 *
 * <p>Both counters are plain {@link AtomicLong}s: dirty regions are added from
 * whichever thread repainted, not only from the event thread, and a watchdog
 * that perturbs the thing it is measuring is worse than no watchdog.
 *
 * <p>A drop-in replacement rather than an addition: every call delegates to
 * {@code super}, so switching it on changes nothing but the counting.
 */
public final class UiTrace extends RepaintManager {

    private static final String FLAG = "aichat.uitrace";

    private static final AtomicLong DIRTY = new AtomicLong();
    private static final AtomicLong INVALID = new AtomicLong();

    private UiTrace() {
    }

    /** Replaces the current manager iff the flag is set. Safe to call once. */
    public static void install() {
        if (!"1".equals(System.getProperty(FLAG))) return;
        RepaintManager.setCurrentManager(new UiTrace());
        Thread reporter = new Thread(UiTrace::report, "aichat-uitrace");
        reporter.setDaemon(true);
        reporter.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            System.out.println("[uitrace] total dirty=" + DIRTY.get()
                + " invalid=" + INVALID.get())));
        System.out.println("[uitrace] on — dirty/s 是重绘次数，invalid/s 是失效重排次数");
    }

    @Override
    public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
        DIRTY.incrementAndGet();
        super.addDirtyRegion(c, x, y, w, h);
    }

    @Override
    public synchronized void addInvalidComponent(JComponent invalidComponent) {
        INVALID.incrementAndGet();
        super.addInvalidComponent(invalidComponent);
    }

    /** One line per second, and only for a second that had activity. */
    private static void report() {
        long lastDirty = 0;
        long lastInvalid = 0;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long dirty = DIRTY.get();
            long invalid = INVALID.get();
            long d = dirty - lastDirty;
            long i = invalid - lastInvalid;
            lastDirty = dirty;
            lastInvalid = invalid;
            if (d > 0 || i > 0) {
                System.out.println("[uitrace] dirty/s=" + d + " invalid/s=" + i);
            }
        }
    }
}
