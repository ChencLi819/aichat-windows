package com.jev.probe.desktop;

import com.jev.probe.desktop.capture.RegionWatcher;
import com.jev.probe.desktop.capture.ScreenGrabber;
import com.jev.probe.desktop.capture.VisionReader;
import com.jev.probe.desktop.capture.Win32Windows;
import com.jev.probe.desktop.capture.WindowKeeper;
import com.jev.probe.desktop.config.CaptureTarget;
import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ChatContext;
import com.jev.probe.desktop.kb.Contact;
import com.jev.probe.desktop.kb.ContextBuilder;
import com.jev.probe.desktop.kb.ContextSessions;
import com.jev.probe.desktop.kb.KbStore;
import com.jev.probe.desktop.kb.Relations;
import com.jev.probe.desktop.llm.ChatAiClient;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.model.RankedReply;
import com.jev.probe.desktop.net.ApiException;
import com.jev.probe.desktop.ui.AssistantPanel;
import com.jev.probe.desktop.ui.RegionSelector;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The wiring between the capture layer and the panel.
 *
 * One round of analysis is: settle → screenshot → vision read → (judgment ‖
 * reply drafting) → panel. The judgment and the drafting run on TWO threads at
 * once, exactly as on Android: the judgment is what the user waits to see, and
 * the candidates fill in a moment later instead of doubling the wait.
 *
 * Nothing here may kill the watcher. Every failure inside a round is turned into
 * a line on the panel and the loop is re-armed; a broken API key must not stop
 * the app from noticing the next message.
 */
public final class ChatPipeline implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /** How many of the read messages 「本轮依据」 shows (FR-PANEL-06). */
    private static final int EVIDENCE_COUNT = 5;

    private final Config config;
    private final ScreenGrabber grabber;
    private final AssistantPanel panel;
    private final VisionReader vision;
    private final ChatAiClient ai;

    /** Rounds are serialized here; the two model calls fan out to [callPool]. */
    private final ExecutorService roundPool = daemonPool(1, "chat-round");
    private final ExecutorService callPool = daemonPool(2, "chat-call");
    private final ExecutorService miscPool = daemonPool(1, "chat-misc");

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile boolean userPaused;

    private RegionWatcher watcher;

    /**
     * Keeps the bound window off screen while the user has it put away.
     *
     * Owned here because its lifetime is exactly the monitoring lifetime: it
     * starts with the watcher, stops with it, and puts the window back where it
     * found it when it stops. See {@link WindowKeeper} for why it exists.
     */
    private WindowKeeper keeper;
    /** Last mode reported, so the notices can be tied to real transitions. */
    private volatile WindowKeeper.Mode keeperMode = WindowKeeper.Mode.OFF;

    private volatile ChatSnapshot lastSnapshot;
    private volatile ChatContext lastContext;
    private volatile String lastTitle = "";
    /**
     * The relationship in force for [lastTitle], resolved once per round.
     *
     * A relationship belongs to the person (v1.1 §5.6), so this is NOT read
     * straight off [config]: the same app run has to send 「伴侣」 for one chat and
     * 「同事」 for the next.
     */
    private volatile Relations.Resolved lastRelation;

    public ChatPipeline(Config config, ScreenGrabber grabber, AssistantPanel panel) {
        this.config = config;
        this.grabber = grabber;
        this.panel = panel;
        this.vision = new VisionReader(config);
        this.ai = new ChatAiClient(config);
    }

    private static ExecutorService daemonPool(int n, String name) {
        return Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    // ----------------------------------------------------------------- start

    public void start() {
        rebuildWatcher();
        pushCaptureInfo();
        if (config.captureTarget() == null) {
            post(() -> panel.showNotice("还没有选择聊天区域，点「选择区域」框住聊天窗口内部的聊天区。"));
        } else if (!vision.isConfigured()) {
            post(() -> panel.showNotice("视觉接口还没配好，点 ⚙ 填地址、模型和密钥。"));
        }
    }

    /**
     * Re-read the config after the settings dialog: the interval and the region
     * are baked into the watcher, so it is rebuilt rather than mutated.
     */
    public void reconfigure() {
        rebuildWatcher();
        post(() -> panel.showNotice("设置已更新"));
    }

    private void rebuildWatcher() {
        if (watcher != null) watcher.close();
        watcher = new RegionWatcher(grabber, config.captureTarget(), this::onSettled,
            config.watchIntervalSeconds() * 1000, 4, 2);
        watcher.setProblemListener(reason -> post(() -> panel.showNotice("读不到聊天窗口：" + reason)));
        watcher.start();
        if (userPaused) watcher.setPaused(true);
        rebuildKeeper();
    }

    /**
     * (Re)build the off-screen guard for the window now bound.
     *
     * Rebuilt rather than mutated: the guard is bound to one window identity, and
     * the user can re-bind at any time from 「选择区域」.
     */
    private void rebuildKeeper() {
        stopKeeper();
        if (!config.parkOffScreen() || !Win32Windows.supported()) return;
        CaptureTarget t = config.captureTarget();
        if (t == null || t.windowTitle().isBlank()) return;
        WindowKeeper k = new WindowKeeper(t.windowClass(), t.windowTitle(), this::onKeeperMode);
        keeper = k;
        k.start();
    }

    /** Stop the guard. It puts the window back where the user had it. */
    private void stopKeeper() {
        WindowKeeper k = keeper;
        keeper = null;
        if (k != null) k.close();
        keeperMode = WindowKeeper.Mode.OFF;
    }

    /**
     * The guard changed state. Runs on the keeper thread.
     *
     * Only real transitions are announced, and only the two the user can see
     * happen to their own chat window: it disappearing off screen and it coming
     * back. Anything else would be noise.
     */
    private void onKeeperMode(WindowKeeper.Mode mode, String detail) {
        WindowKeeper.Mode prev = keeperMode;
        keeperMode = mode;
        if (mode == WindowKeeper.Mode.LOST) {
            post(() -> panel.showNotice(detail == null
                ? "聊天窗口已关闭，监听暂停。重新打开它会自动继续。"
                : detail + "监听暂停，重新打开它会自动继续。"));
            return;
        }
        if (mode == WindowKeeper.Mode.PARKED && prev != WindowKeeper.Mode.PARKED) {
            post(() -> panel.showNotice("聊天窗口已移到屏幕外继续监听。"
                + "点托盘图标或任务栏把它叫回来，会自动回到原位。"));
            return;
        }
        if (mode == WindowKeeper.Mode.YIELDED && prev == WindowKeeper.Mode.PARKED) {
            post(() -> panel.showNotice("已把聊天窗口送回原位，监听继续。"));
        }
    }

    /** The watcher found a settled change. Runs on the watcher thread. */
    private void onSettled() {
        if (userPaused) return;
        // 「对方发消息时自动分析」 off: the watcher keeps running, but only a
        // manual press starts a round.
        if (!config.autoAnalyze()) return;
        if (!inFlight.compareAndSet(false, true)) return;
        // Pause before handing off, so the same screen cannot be reported again
        // while this round is still reading it.
        watcher.setPaused(true);
        roundPool.execute(this::runRound);
    }

    // ------------------------------------------------------------- user actions

    /** 「分析当前对话」/「重新分析」 — works even while the watcher is paused. */
    public void manualAnalyze() {
        CaptureTarget target = watcher == null ? config.captureTarget() : watcher.getTarget();
        if (target == null) {
            post(() -> panel.showNotice("请先点「选择区域」框住聊天窗口。"));
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            post(() -> panel.showNotice("正在分析中…"));
            return;
        }
        if (watcher != null) watcher.setPaused(true);
        roundPool.execute(this::runRound);
    }

    /** 「暂停监听」/「继续监听」. */
    public void togglePause() {
        userPaused = !userPaused;
        if (watcher != null) {
            watcher.setPaused(userPaused);
            if (!userPaused) watcher.resync();
        }
        if (userPaused) {
            // Paused means "stop watching", so the chat window is handed back
            // rather than left parked off screen.
            stopKeeper();
        } else {
            rebuildKeeper();
        }
        panel.setPaused(userPaused);
    }

    /**
     * 「选择区域」: hide the panel, let the user drag over the chat, remember the
     * window it landed in plus the offset inside it, and re-arm.
     */
    public void chooseRegion() {
        // Called on the EDT (panel button); hide synchronously so the overlay
        // never covers the chat the user is trying to frame.
        panel.setVisible(false);
        miscPool.execute(() -> {
            RegionSelector.Selection sel = RegionSelector.select();
            if (sel.target() != null) {
                config.captureTarget(sel.target());
                if (watcher != null) {
                    watcher.setTarget(sel.target());
                    watcher.setPaused(userPaused);
                    watcher.resync();
                }
                // The guard is bound to one window identity, so a re-bind has to
                // move it too — and the old window must be handed back first.
                rebuildKeeper();
            }
            SwingUtilities.invokeLater(() -> {
                panel.setVisible(true);
                panel.toFront();
                if (sel.target() != null) {
                    pushCaptureInfo();
                    panel.showNotice("已绑定窗口「" + sel.target().describe() + "」，"
                        + sel.target().width() + "×" + sel.target().height() + " 区域");
                } else if (sel.message() != null) {
                    panel.showNotice(sel.message());
                } else {
                    panel.showNotice("未选择区域。");
                }
            });
        });
    }

    /** Tells the panel where the assistant is reading, for its header's sub-line. */
    private void pushCaptureInfo() {
        CaptureTarget t = config.captureTarget();
        String info = t == null ? null
            : t.windowTitle() + " · " + t.width() + "×" + t.height();
        post(() -> panel.setCaptureInfo(info));
    }

    /** 「记录本会话」/「清除本会话」 — the per-conversation context buffer. */
    public void toggleSession() {
        String title = lastTitle;
        if (title == null || title.isBlank()) {
            panel.showNotice("先分析一次，拿到会话标题后才能记录。");
            return;
        }
        boolean active;
        if (ContextSessions.isActive(title)) {
            ContextSessions.clear(title);
            active = false;
            panel.showNotice("已清除本会话上下文。");
        } else {
            ContextSessions.inject(title, lastSnapshot != null ? lastSnapshot.messages() : List.of());
            active = true;
            panel.showNotice("已开始记录本会话上下文。");
        }
        panel.setSessionActive(active);
    }

    /**
     * 「存为联系人」: file the open conversation into the knowledge base, so its
     * history and notes are matched by name from then on.
     *
     * The store composes the message — only it knows whether this created a
     * contact or folded the title into one that already existed.
     */
    public void saveCurrentAsContact() {
        saveCurrentAsContact(null);
    }

    /**
     * 「存为联系人」 with a relationship to stamp on it.
     *
     * Needed because a relationship is stored on the contact (FR-REL-01): the
     * capsule's menu offers the candidates to someone the knowledge base has
     * never seen, and the only honest way to honour that choice is to create the
     * contact and then apply it.
     */
    public void saveCurrentAsContact(String relation) {
        String title = lastTitle;
        if (title == null || title.isBlank()) {
            panel.showNotice("先分析一次，拿到会话标题后才能存为联系人。");
            return;
        }
        KbStore store = KbStore.get();
        String message = store.saveOrMergeContact(title, "");
        if (relation != null && !relation.isBlank()) {
            Contact contact = store.findContact(title, "");
            if (contact != null) {
                store.saveContact(contact.withRelationship(relation));
                message = message + "，关系设为「" + relation + "」";
            }
        }
        syncRelationship();
        panel.showNotice(message);
    }

    /**
     * FR-REL-03: the panel's capsule wrote a value.
     *
     * There is nowhere else to put it — the relationship belongs to the person —
     * so an unrecognised conversation is told to file itself first rather than
     * silently accepting a value that would be forgotten by the next round.
     */
    public void setRelationship(String value) {
        String title = lastTitle;
        if (title == null || title.isBlank()) {
            post(() -> panel.showNotice("先分析一次，认出会话人后才能设置关系。"));
            return;
        }
        KbStore store = KbStore.get();
        Contact contact = store.findContact(title, "");
        if (contact == null) {
            post(() -> panel.showNotice("「" + title + "」还没存为联系人，先用「⋯ → 存为联系人」。"));
            return;
        }
        store.saveContact(contact.withRelationship(value));
        syncRelationship();
    }

    /** 「清除（回落默认关系）」 — back to the fallback, not to 「未说明」. */
    public void clearRelationship() {
        String title = lastTitle;
        if (title == null || title.isBlank()) return;
        KbStore store = KbStore.get();
        Contact contact = store.findContact(title, "");
        if (contact == null) return;
        store.saveContact(contact.withRelationship(""));
        syncRelationship();
    }

    /**
     * Re-resolve the relationship in force and push it to the panel.
     *
     * Called after any edit — the capsule, the knowledge base's contact card, the
     * settings page (FR-REL-04) — so the header never shows a stale value.
     */
    public void syncRelationship() {
        Relations.Resolved resolved = Relations.resolve(lastTitle, config);
        lastRelation = resolved;
        post(() -> panel.setRelationship(resolved, resolved.contactName() != null));
    }

    /** 「问 AI」: answer the user's own question about this conversation. */
    public void ask(String question) {
        if (question == null || question.isBlank()) {
            panel.showAskError("先输入问题。");
            return;
        }
        panel.showAsking(question);
        roundPool.execute(() -> {
            try {
                ChatSnapshot snap = lastSnapshot;
                ChatContext ctx = lastContext;
                if (snap == null) {
                    // No analysis yet: read the region once so the question has
                    // the conversation to reason about.
                    CaptureTarget target = watcher == null
                        ? config.captureTarget() : watcher.getTarget();
                    if (target == null) {
                        post(() -> panel.showAskError("先点「选择区域」，再分析一次当前对话。"));
                        return;
                    }
                    BufferedImage askShot = grabber.grab(target);
                    if (askShot == null) {
                        // No render target: minimized or hidden, with the
                        // off-screen guard off. Not an error worth a stack trace,
                        // but the user did just ask a question, so say why.
                        post(() -> panel.showAskError("读不到聊天窗口的画面，"
                            + "先把它从最小化或托盘里恢复出来。"));
                        return;
                    }
                    VisionReader.Dialog d = vision.read(askShot);
                    String title = resolveTitle(d.title());
                    snap = new ChatSnapshot(title, d.messages());
                    ctx = buildContext(snap, title);
                    lastSnapshot = snap;
                    lastContext = ctx;
                    lastTitle = title;
                }
                Relations.Resolved relation = Relations.resolve(lastTitle, config);
                lastRelation = relation;
                post(() -> panel.setRelationship(relation, relation.contactName() != null));
                ChatAiClient.AskResult res = ai.ask(question, snap, relation.value(), ctx,
                    (kind, chunk) -> {
                        if ("reasoning".equals(kind)) post(() -> panel.onThinkingDelta(chunk));
                    });
                post(() -> panel.showAnswer(question, res.answer(), res.reasoning()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "ask failed", e);
                post(() -> panel.showAskError("问 AI 失败：" + msg(e)));
            }
        });
    }

    // ------------------------------------------------------------------ round

    /**
     * 「重新生成」 on one candidate.
     *
     * The drafting route returns a set of three; there is no single-line prompt in
     * it and this deliberately does not add one (the model call chain and its
     * prompts are out of scope). It re-drafts with the same prompt and hands the
     * whole fresh set to the panel, which picks the first line that is not already
     * on screen for the clicked slot — so the two the user was reading do not move
     * under the cursor.
     *
     * @param rank 1-based, as shown on the card.
     */
    public void regenerateReply(int rank) {
        ChatSnapshot snap = lastSnapshot;
        if (snap == null) {
            post(() -> panel.showNotice("先分析一次，才有可重新生成的候选回复。"));
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            post(() -> panel.showNotice("正在分析中…"));
            return;
        }
        Relations.Resolved relation = lastRelation == null
            ? Relations.resolve(lastTitle, config) : lastRelation;
        ChatContext ctx = lastContext;
        miscPool.execute(() -> {
            try {
                List<RankedReply> fresh = ai.draftAndRank(snap, relation.value(), ctx);
                post(() -> panel.replaceReply(rank, fresh));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "regenerate failed", e);
                post(() -> panel.showNotice("重新生成失败：" + msg(e)));
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void runRound() {
        try {
            post(panel::showAnalyzing);

            CaptureTarget target = watcher == null ? config.captureTarget() : watcher.getTarget();
            BufferedImage image;
            try {
                image = grabber.grab(target);
            } catch (Exception e) {
                post(() -> panel.showError(msg(e)));
                return;
            }
            if (image == null) {
                // No render target: the window is minimized or hidden and the
                // off-screen guard is off. Not a failure, but the user just asked
                // for an analysis, so say what to do rather than nothing.
                post(() -> panel.showError("读不到聊天窗口的画面。"
                    + "把它从最小化或托盘里恢复出来即可；"
                    + "也可以在设置里打开「最小化时移到屏幕外」，"
                    + "这样它自己会躲到屏幕外继续被监听。"));
                return;
            }
            VisionReader.Dialog dialog;
            try {
                dialog = vision.read(image);
            } catch (ApiException e) {
                post(() -> panel.showError(e.getMessage()));
                return;
            }

            String title = resolveTitle(dialog.title());
            if (!config.isAllowed(title)) {
                // The whitelist used to be stored and never read, so a user who
                // filled it in got analysis on every conversation anyway.
                String why = title.isBlank()
                    ? "读不到会话标题，没法跟白名单比对，这次分析跳过了。"
                        + "可以在设置里填「会话名称」，或把白名单清空。"
                    : "「" + title + "」不在会话白名单里，这次分析跳过了。可在设置里调整。";
                post(() -> panel.showSkipped(why));
                return;
            }
            ChatSnapshot snapshot = new ChatSnapshot(title, dialog.messages());
            ChatContext ctx = buildContext(snapshot, title);
            // v1.1 §5.6: one resolution per round, from the recognised person —
            // a chat with a colleague must not be judged as a chat with a partner.
            Relations.Resolved relation = Relations.resolve(title, config);
            lastSnapshot = snapshot;
            lastContext = ctx;
            lastTitle = title;
            lastRelation = relation;

            boolean session = ContextSessions.isActive(title);
            int notes = ctx.notes().size();
            int history = ctx.history().size();
            List<Msg> evidence = ChatSnapshot.takeLast(snapshot.real(), EVIDENCE_COUNT);
            post(() -> {
                panel.switchConversation(title);
                panel.setRelationship(relation, relation.contactName() != null);
                panel.setEvidence(evidence, config.evidenceVisible());
                panel.setContextInfo(notes, history, session);
                panel.setSessionActive(session);
            });

            // Both calls at once: the judgment is the headline, the candidates
            // arrive a beat later rather than after it.
            CountDownLatch both = new CountDownLatch(2);
            callPool.execute(() -> {
                try {
                    Analysis a = ai.judge(snapshot, relation.value(), ctx, (kind, chunk) -> {
                        if ("reasoning".equals(kind)) post(() -> panel.onThinkingDelta(chunk));
                    });
                    post(() -> {
                        if (a.error() != null) panel.showError(a.error());
                        else panel.showJudgment(a);
                    });
                } catch (Exception e) {
                    post(() -> panel.showError("分析失败：" + msg(e)));
                } finally {
                    both.countDown();
                }
            });
            callPool.execute(() -> {
                try {
                    List<RankedReply> ranked = ai.draftAndRank(snapshot, relation.value(), ctx);
                    post(() -> panel.showReplies(ranked, null));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "draft failed", e);
                    post(() -> panel.showReplies(List.of(), msg(e)));
                } finally {
                    both.countDown();
                }
            });
            both.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "round failed", e);
            post(() -> panel.showError("分析失败：" + msg(e)));
        } finally {
            if (watcher != null && !userPaused) watcher.setPaused(false);
            if (watcher != null) watcher.resync();
            inFlight.set(false);
        }
    }

    /** Title from the vision header, else the user's own conversation name. */
    private String resolveTitle(String visionTitle) {
        if (visionTitle != null && !visionTitle.isBlank()) return visionTitle.trim();
        String fallback = config.conversationName();
        return fallback == null ? "" : fallback.trim();
    }

    /** Knowledge context plus this conversation's own buffer (when recording). */
    private ChatContext buildContext(ChatSnapshot snapshot, String title) {
        ChatContext base = ContextBuilder.build(snapshot, "", config);
        ContextSessions.append(title, snapshot.messages(), config.contextMaxTokens());
        String session = ContextSessions.render(title, config.contextMaxTokens());
        return new ChatContext(base.contact(), base.history(), base.notes(), session);
    }

    private static void post(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (watcher != null) watcher.close();
        // Closing the app hands the chat window back to the user in the state it
        // was borrowed from — it must not be left parked off screen.
        stopKeeper();
        roundPool.shutdownNow();
        callPool.shutdownNow();
        miscPool.shutdownNow();
    }
}
