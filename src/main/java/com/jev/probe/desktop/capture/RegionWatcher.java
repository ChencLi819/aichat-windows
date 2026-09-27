package com.jev.probe.desktop.capture;

import com.jev.probe.desktop.config.CaptureTarget;

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches one window area and reports when its content has settled into a new
 * state — the desktop equivalent of the Android service's "a new message
 * arrived" trigger.
 *
 * How it decides, and why each step is there:
 *
 * 1. Every [intervalMs] it grabs the area and takes a coarse fingerprint.
 *    Nothing else happens on an unchanged screen: **no vision call, no tokens**.
 *    That is the whole reason the loop is affordable — the expensive part runs
 *    only when something actually moved.
 * 2. A change must exceed [changeThresholdBits] to count. A single caret or a
 *    repainted clock is well under it.
 * 3. A change is not reported immediately: a new message animates in, and a
 *    screenshot taken mid-animation would be half-drawn. The watcher waits for
 *    the fingerprint to stop moving for [settlePolls] polls, then reports once.
 *
 * The listener runs on the watcher's own thread — a UI listener must marshal to
 * the event dispatch thread itself.
 */
public final class RegionWatcher implements AutoCloseable {

    /** Called once per settled change, on the watcher thread. */
    public interface Listener {
        void onRegionSettled();
    }

    /**
     * Called on the watcher thread when capture starts failing — the window was
     * closed, or Windows refused to render it. Fired once per failure run, not
     * once per poll, so a chat that stays away does not spam the panel.
     */
    public interface ProblemListener {
        void onCaptureFailed(String reason);
    }

    private static final Logger LOG = Logger.getLogger("AICHAT");

    private final ScreenGrabber grabber;
    private final ScheduledExecutorService pool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "region-watcher");
                t.setDaemon(true);
                return t;
            });

    private volatile CaptureTarget target;
    private volatile int intervalMs;
    private volatile int changeThresholdBits;
    private volatile int settlePolls;
    private volatile Listener listener;
    private volatile ProblemListener problemListener;

    /** Fingerprint of the last state we reported; 0 means "nothing yet". */
    private long stableHash;
    private boolean hasStable;
    /** Fingerprint of the previous poll, for the settle test. */
    private long lastHash;
    private int stableStreak;
    private boolean pendingChange;
    /** Whether the failure now in progress has already been reported. */
    private boolean failureAnnounced;
    private volatile boolean paused;
    private ScheduledFuture<?> task;

    public RegionWatcher(ScreenGrabber grabber, CaptureTarget target, Listener listener) {
        this(grabber, target, listener, 1000, 4, 2);
    }

    public RegionWatcher(ScreenGrabber grabber,
                         CaptureTarget target,
                         Listener listener,
                         int intervalMs,
                         int changeThresholdBits,
                         int settlePolls) {
        this.grabber = grabber;
        this.target = target;
        this.listener = listener;
        this.intervalMs = Math.max(200, intervalMs);
        this.changeThresholdBits = Math.max(1, changeThresholdBits);
        this.settlePolls = Math.max(1, settlePolls);
    }

    public void start() {
        if (task != null) return;
        task = pool.scheduleWithFixedDelay(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        ScheduledFuture<?> t = task;
        task = null;
        if (t != null) t.cancel(false);
    }

    /** Re-arm after a manual read, so the next poll compares against what was
     *  just analysed instead of reporting the same screen a second time. */
    public void resync() {
        hasStable = false;
        pendingChange = false;
        stableStreak = 0;
    }

    /** Suspend polling without tearing the watcher down (e.g. while a vision
     *  call is in flight, so two reads can never overlap). */
    public void setPaused(boolean p) {
        this.paused = p;
    }

    public void setTarget(CaptureTarget t) {
        this.target = t;
        resync();
    }

    public CaptureTarget getTarget() {
        return target;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setProblemListener(ProblemListener l) {
        this.problemListener = l;
    }

    private void tick() {
        if (paused) return;
        CaptureTarget t = target;
        if (t == null) return;
        try {
            BufferedImage img = grabber.grab(t);
            if (img == null) {
                // Minimized: the chat window is on the taskbar, so there is no
                // conversation on screen and nothing can have changed. Skipping
                // in silence is the point — announcing it would put an error on
                // the panel once per second for something that is not wrong.
                return;
            }
            failureAnnounced = false;
            evaluate(ScreenGrabber.fingerprint(img));
        } catch (Exception e) {
            // A grab can fail while the desktop is switching modes (UAC prompt,
            // resolution change, RDP), or because the chat window is simply
            // gone. Never let that kill the loop — but do say so once, rather
            // than leaving the panel looking like nothing is happening.
            LOG.log(Level.FINE, "抓屏失败，跳过本次轮询", e);
            announceFailure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void announceFailure(String reason) {
        if (failureAnnounced) return;
        failureAnnounced = true;
        ProblemListener l = problemListener;
        if (l == null) return;
        try {
            l.onCaptureFailed(reason);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "监听故障回调异常", e);
        }
    }

    /** The state machine behind [tick] — separated so it can be unit tested. */
    void evaluate(long fp) {
        if (!hasStable) {
            // First sight of this region (or a resync): adopt it as the baseline
            // and report nothing. The user has not received a new message yet.
            stableHash = fp;
            lastHash = fp;
            hasStable = true;
            pendingChange = false;
            stableStreak = 0;
            return;
        }
        int moved = ScreenGrabber.distance(fp, stableHash);
        if (moved < changeThresholdBits) {
            // Back to the known state (or never really left it).
            if (pendingChange && fp == stableHash) {
                pendingChange = false;
                stableStreak = 0;
            }
            return;
        }
        // It differs from the last reported state. Wait for it to stop moving.
        if (fp == lastHash) {
            stableStreak++;
        } else {
            stableStreak = 0;
        }
        lastHash = fp;
        pendingChange = true;
        if (stableStreak >= settlePolls - 1) {
            stableHash = fp;
            pendingChange = false;
            stableStreak = 0;
            Listener l = listener;
            if (l != null) {
                try {
                    l.onRegionSettled();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "监听回调异常", e);
                }
            }
        }
    }

    @Override
    public void close() {
        stop();
        pool.shutdownNow();
    }

    /** True while a change is on screen but has not settled yet. */
    public boolean isPending() {
        return pendingChange;
    }
}
