package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.llm.LlmJudgeClient;
import com.jev.probe.desktop.ui.kit.Btn;
import com.jev.probe.desktop.ui.kit.Card;
import com.jev.probe.desktop.ui.kit.DialogShell;
import com.jev.probe.desktop.ui.kit.Field;
import com.jev.probe.desktop.ui.kit.Glyph;
import com.jev.probe.desktop.ui.kit.Glyphs;
import com.jev.probe.desktop.ui.kit.Input;
import com.jev.probe.desktop.ui.kit.Kit;
import com.jev.probe.desktop.ui.kit.Password;
import com.jev.probe.desktop.ui.kit.Surface;
import com.jev.probe.desktop.ui.kit.Wrap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.awt.Component.CENTER_ALIGNMENT;
import static java.awt.Component.LEFT_ALIGNMENT;

/**
 * The first-run walkthrough: three steps, and a gate on the second one.
 *
 * FR-OB-01 replaces the old "no key yet, so here is the whole settings form" with
 * an introduction, because that form (P-09/P-18) is an information wall in front
 * of a product the user does not understand yet. The three pages are the three
 * things a new install actually needs: what this is, one working model route, and
 * an area to read.
 *
 * Rendered from the prototype's own DOM — `openOnboard()` in
 * `design/demo/assets/js/app.js` — rather than from an approximation of it. The
 * three things that follow from that, and were not true before:
 *
 * 1. ONE footer button, relabelled. `.dialog__foot` holds 「上一步」 and 「下一步」
 *    in a single right-aligned row, and the prototype renames the second one to
 *    「完成」 on the last page. This used to be two buttons in the same slot that
 *    were swapped for visibility, which read as a different control appearing
 *    out of nowhere and made the footer's right edge jump between pages.
 * 2. THE PAGE FILLS THE BODY. `.onboard__page { height: 100% }`; a page pinned to
 *    a fixed 280px used to clip the route card the moment 字号 was set to 大.
 * 3. THE STEP 2 GATE LIVES IN `go()`, not only on the button's enabled state.
 *    Being unable to click 「下一步」 and being unable to REACH page 3 are
 *    different guarantees, and only the second one is FR-OB-03.
 *
 * The step-2 test runs against a SCRATCH config seeded from the field, the same
 * trick {@code SettingsDialog.runOnScratch} uses, so a key typed into the
 * walkthrough is never written to disk just to be tried. It reaches the real
 * config only once it has been shown to work — by 「完成」, or by 「开始框选」, which
 * also leaves the walkthrough and so commits the same route. See
 * {@link #startRegion}.
 *
 * 「开始框选」 hands off to the app's own region picker, exactly as the prototype
 * does: the picker needs the whole screen, and a modal dialog in front of it would
 * swallow its clicks, so the walkthrough steps aside first. See {@link #startRegion}
 * for why that hand-off must NOT go through `invokeLater`.
 */
public final class OnboardingDialog {

    private static final int WIDTH = 680;
    private static final int HEIGHT = 480;

    /**
     * Wrap width for the body copy: the dialog, less its 32px side padding, less
     * a little for the scroll pane's 8px bar. A label wider than the viewport is
     * clipped rather than wrapped — the horizontal bar is disabled — so the width
     * has to be conservative.
     */
    private static final int BODY_WRAP = WIDTH - Tokens.SP8 * 2 - 16;

    /** `.steps { margin-bottom: 6px }`, on top of the body's own 16px gap. */
    private static final int STEPS_TO_PAGE_GAP = Tokens.SP5 + Tokens.SP2;

    /** The four routes the walkthrough offers — the subset that needs one key. */
    private static final List<Preset> PRESETS = List.of(
        new Preset("百炼 DeepSeek V4.1", Config.LLM_BASE_BAILIAN, Config.LLM_MODEL_BAILIAN_DEEPSEEK),
        new Preset("DeepSeek 官方", Config.LLM_BASE_DEEPSEEK, Config.LLM_MODEL_DEEPSEEK_FLASH),
        new Preset("OpenRouter", Config.LLM_BASE_OPENROUTER, Config.LLM_MODEL_OPENROUTER),
        new Preset("自定义", "", ""));

    private record Preset(String label, String base, String model) {
    }

    private final Config config;
    private final Runnable onFinish;
    private final Runnable onChooseRegion;

    private final DialogShell shell;
    private final CardLayout cards = new CardLayout();
    private final JPanel pageHost = new JPanel(cards);
    private final Steps steps = new Steps();
    private final Btn back = new Btn("上一步", Btn.Variant.SECONDARY);
    private final Btn next = new Btn("下一步", Btn.Variant.PRIMARY);

    private final Password key = new Password();
    private final Btn reveal = new Btn("显示", Btn.Variant.SECONDARY, Btn.Size.SM);
    private final Btn testBtn = new Btn("测试连接", Btn.Variant.SECONDARY);
    private final TestLine testLine = new TestLine();
    private final Wrap presetRow = new Wrap(Tokens.SP2, Tokens.SP2);

    /** 1-based; the footer and the indicator both follow it (FR-OB-01). */
    private int step = 1;
    private int presetIndex;
    private boolean keyTested;

    /**
     * The 「开始框选」 hand-off, once it is running. Volatile because it is written
     * on the EDT and read by whoever called {@link #show}.
     */
    private volatile Thread regionThread;

    private OnboardingDialog(Window owner, Config config, Runnable onFinish, Runnable onChooseRegion) {
        this.config = config;
        this.onFinish = onFinish;
        this.onChooseRegion = onChooseRegion;
        this.presetIndex = detectPreset();
        // `.dialog.onboard` has no `.dialog__head`, so neither does this one: the
        // header would cost 56px that step 1's pledges and step 3's region box
        // are drawn against.
        this.shell = DialogShell.modal(owner, "首次使用引导", WIDTH, HEIGHT).headless();
        key.text(config.storedJudgeKey()).placeholder("sk-…");
        build();
    }

    /**
     * @param onFinish run once the walkthrough is committed, after the key written
     *        in step 2 has been saved.
     * @param onChooseRegion the app's existing 「选择区域」 flow; it runs after this
     *        dialog has closed, on a thread of its own because the flow blocks.
     */
    public static void show(Window owner, Config config, Runnable onFinish, Runnable onChooseRegion) {
        OnboardingDialog dialog = new OnboardingDialog(owner, config, onFinish, onChooseRegion);
        dialog.shell.show();
        // 「开始框选」 leaves the walkthrough for good and hands off to a flow that
        // outlives it. Waiting here keeps the caller's own post-walkthrough checks
        // — Main asks for a region when there is none, and for a key when there is
        // none — from firing while the overlay is open. Without it a second
        // fullscreen picker opens on top of the one the user is dragging on.
        dialog.awaitRegion();
    }

    private void awaitRegion() {
        Thread t = regionThread;
        if (t == null) return;
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ build

    private void build() {
        // `.onboard__body { padding: 24px 32px }` — 24 on all four sides, not the
        // 16px bottom this used to have.
        shell.body().setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP7, Tokens.SP8, Tokens.SP7, Tokens.SP8));

        pageHost.setOpaque(false);
        pageHost.setAlignmentX(LEFT_ALIGNMENT);
        // No fixed height: the box column hands the leftover space to whichever
        // child is willing to take it, and this is the one that should. A page
        // whose content outgrows the dialog then simply makes the body taller
        // than its viewport, which the scroll pane already handles.
        pageHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        pageHost.add(pageIntro(), "1");
        pageHost.add(pageRoute(), "2");
        pageHost.add(pageRegion(), "3");

        shell.body().add(steps);
        shell.body().add(DialogShell.gap(STEPS_TO_PAGE_GAP));
        shell.body().add(pageHost);

        back.onClick(() -> go(step - 1));
        next.onClick(this::advance);
        // `.dialog__foot`: one right-aligned row of the two buttons, 12px apart —
        // the same row DialogShell already lays out for its other dialogs.
        shell.footActions().add(back);
        shell.footActions().add(next);

        go(1);
    }

    /**
     * Moves to page [n], clamped to 1…3, and re-derives everything that depends on
     * the current page: the indicator, the two footer buttons, and the step-2 gate.
     *
     * The gate is enforced HERE and not only through {@code next.setEnabled}. A
     * disabled button is a hint to the user; it is not a guarantee about the state
     * machine, and page 3 is the page that writes the key to disk. Any attempt to
     * land on 3 without a passing test falls back to 2.
     */
    private void go(int n) {
        int want = Math.max(1, Math.min(3, n));
        step = want == 3 && !keyTested ? 2 : want;
        cards.show(pageHost, String.valueOf(step));
        steps.setCurrent(step);
        back.setEnabled(step > 1);
        next.label(step == 3 ? "完成" : "下一步");
        next.setEnabled(step != 2 || keyTested);
    }

    /**
     * What 「下一步」/「完成」 does. One handler for both, because it is one button.
     *
     * FR-OB-03 again on the way out: on page 2 without a pass this returns having
     * done nothing, so the gate holds even if the button's enabled state were ever
     * wrong.
     */
    private void advance() {
        if (step == 3) {
            finish();
            return;
        }
        if (step == 2 && !keyTested) return;
        go(step + 1);
    }

    /** The walkthrough's only committing action, reachable only from page 3. */
    private void finish() {
        if (step != 3) return;
        config.judgeBaseUrl(base());
        config.judgeModel(model());
        config.judgeKey(key.value().trim());
        shell.close();
        if (onFinish != null) onFinish.run();
    }

    /**
     * 「开始框选」: commit the route, close the walkthrough, run the region flow.
     *
     * The flow is NOT queued on the EDT, and that is the point of this method.
     * `RegionSelector.select()` does `SwingUtilities.invokeLater(show)` and then
     * waits for the overlay's latch — so running it on the EDT blocks the very
     * thread the overlay is queued on. The latch never counts down, the UI stops
     * responding, and what the user sees is the walkthrough closing onto an empty
     * desktop: no overlay, no exception, nothing in the log. It gets a thread of
     * its own, which is also how `ChatPipeline.chooseRegion` calls it.
     *
     * The route is written here even though 「完成」 was not pressed, because this
     * button ends the walkthrough all the same. It cannot smuggle in an unproven
     * key: page 3 is only reachable through the step-2 gate, which is the test
     * itself. Without this the caller sees "configured nothing yet" the moment the
     * dialog closes and opens the settings form on top of the live overlay.
     *
     * The thread is started BEFORE the close, so that {@link #show} is guaranteed
     * to find it and can wait for it — see {@link #awaitRegion}.
     *
     * Because it runs off the EDT, [onChooseRegion] must not touch Swing directly.
     */
    private void startRegion() {
        config.judgeBaseUrl(base());
        config.judgeModel(model());
        config.judgeKey(key.value().trim());
        if (onChooseRegion != null) {
            Thread t = new Thread(onChooseRegion, "onboard-region");
            t.setDaemon(true);
            regionThread = t;
            t.start();
        }
        shell.close();
    }

    // ------------------------------------------------------------------ pages

    /** Page 1 — `.onboard__page[data-ob=1]`: the pitch and the three pledges. */
    private JComponent pageIntro() {
        JPanel col = Theme.column();
        col.add(heading("AIChat：帮你读懂对方这句话"));
        // `h2` and `p` both carry `margin: 0` in the stylesheet, but a Swing
        // label's box is a few pixels shorter than the CSS line-height it stands
        // in for, so the two lines would touch without this.
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(wrapped("它在你的聊天窗口旁边常驻，读你框选的那块区域，"
            + "告诉你对方真正想要什么、现在该不该回、可以怎么回。"));
        // `.pledge { margin-top: 6px; gap: 6px }`.
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(pledge("不会替你发送消息，也不会自动填入输入框"));
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(pledge("聊天内容只存在你本机，想删随时删"));
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(pledge("密钥只写进本机配置文件，不上传任何服务器"));
        return topAligned(col);
    }

    /** Page 2 — `.onboard__page[data-ob=2]`: presets, one key, and the test. */
    private JComponent pageRoute() {
        JPanel col = Theme.column();
        col.add(heading("接一个模型接口"));
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(wrapped("选一个服务商，填好密钥，点一下测试。回复和视觉留空就会沿用这把密钥。"));
        // `.card { margin-top: 8px }`.
        col.add(DialogShell.gap(Tokens.SP3));

        reveal.onClick(() -> {
            boolean on = !key.isRevealed();
            key.reveal(on);
            // Both words are two characters, so the footer row does not shift.
            reveal.label(on ? "隐藏" : "显示");
        });
        // `.input-group`: the field takes the width, the button keeps its own.
        // Editing the key invalidates an earlier pass, so 「下一步」 cannot be
        // unlocked by a test that no longer describes what will be saved.
        key.getDocument().addDocumentListener(new OnEdit(this::invalidateTest));
        JPanel keyRow = new JPanel(new BorderLayout(Tokens.SP2, 0));
        keyRow.setOpaque(false);
        keyRow.setAlignmentX(LEFT_ALIGNMENT);
        keyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Input.HEIGHT));
        keyRow.add(key, BorderLayout.CENTER);
        keyRow.add(reveal, BorderLayout.EAST);

        testBtn.onClick(this::runTest);
        // `.route__test { display: flex; align-items: center; gap: 12px }`.
        JPanel testRow = new JPanel(new BorderLayout(Tokens.SP4, 0));
        testRow.setOpaque(false);
        testRow.setAlignmentX(LEFT_ALIGNMENT);
        testRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
            Math.max(testLine.getPreferredSize().height, testBtn.getPreferredSize().height)));
        testRow.add(testBtn, BorderLayout.WEST);
        testRow.add(testLine, BorderLayout.CENTER);

        renderPresets();
        Card card = Card.plain();
        card.add(presetRow.span());
        // `.route__presets { margin-bottom: 12px }`.
        card.add(DialogShell.gap(Tokens.SP3));
        card.add(new Field("密钥", keyRow));
        // `.field { margin-bottom: 12px }`.
        card.add(DialogShell.gap(Tokens.SP3));
        card.add(testRow);
        // Deliberately NOT pinned: the preset row reports its height for a
        // fallback width until the first layout pass, so an absolute maximum
        // taken here would freeze the card at a two-line height for good. The
        // card cannot stretch on its own anyway — it is a `BorderLayout.NORTH`
        // child of a `NORTH` slot, which is given exactly its preferred height.
        col.add(card);

        return topAligned(col);
    }

    /** Page 3 — `.onboard__page[data-ob=3]`: the dashed frame and the hand-off. */
    private JComponent pageRegion() {
        JPanel col = Theme.column();
        col.add(heading("框住你要它看的聊天区"));
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(wrapped("点下面的按钮，拖动鼠标把聊天消息那块区域框起来"
            + "（整个框要落在聊天窗口内部）。"));

        Btn choose = new Btn("开始框选", Btn.Variant.PRIMARY);
        choose.onClick(this::startRegion);

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new javax.swing.BoxLayout(inner, javax.swing.BoxLayout.Y_AXIS));
        inner.add(Box.createVerticalGlue());
        GlyphBox mark = new GlyphBox(Glyphs.crop(), 40, Tokens::text2);
        mark.setAlignmentX(CENTER_ALIGNMENT);
        inner.add(mark);
        inner.add(Box.createVerticalStrut(Tokens.SP3));
        inner.add(new Lbl("拖动框住聊天区（整个窗口内部），Esc 取消", 0,
            Tokens.FS_NOTE, Font.PLAIN, Tokens::text2, true));
        inner.add(Box.createVerticalStrut(Tokens.SP3));
        inner.add(centeredRow(choose));
        inner.add(Box.createVerticalGlue());

        // `.onboard__region`: surface-1 behind a 1px dashed border-2, radius 12.
        // Dashes are the one thing the kit's card skins do not express, so the
        // skin is written out here.
        Surface box = new Surface((g2, w, h, t) -> {
            int arc = Kit.arc(w, h, Tokens.R_LG);
            Kit.fill(g2, 0, 0, w, h, Tokens.R_LG, t.surface1());
            g2.setColor(t.border2());
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                1f, new float[]{4f, 4f}, 0f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
        }, new BorderLayout());
        box.add(inner, BorderLayout.CENTER);

        JPanel page = new JPanel(new BorderLayout(0, Tokens.SP3));
        page.setOpaque(false);
        page.add(col, BorderLayout.NORTH);
        page.add(box, BorderLayout.CENTER);
        return page;
    }

    /**
     * A page whose content keeps its own height instead of being stretched.
     *
     * `BorderLayout.NORTH` is the point: a `BoxLayout` column given more height
     * than it needs CENTRES its children, so a page dropped straight into a
     * growing slot would drift into the middle as the dialog gets taller.
     */
    private static JComponent topAligned(JComponent col) {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(col, BorderLayout.NORTH);
        return page;
    }

    // ------------------------------------------------------------------- test

    private void renderPresets() {
        presetRow.removeAll();
        for (int i = 0; i < PRESETS.size(); i++) {
            Preset p = PRESETS.get(i);
            int index = i;
            Btn b = new Btn(p.label(),
                i == presetIndex ? Btn.Variant.PRIMARY : Btn.Variant.SECONDARY, Btn.Size.SM);
            b.onClick(() -> {
                presetIndex = index;
                renderPresets();
                invalidateTest();
            });
            presetRow.add(b);
        }
        presetRow.revalidate();
        presetRow.repaint();
    }

    private void runTest() {
        String typed = key.value().trim();
        if (typed.isBlank()) {
            testLine.set("先填密钥，再点测试", Tone.BAD);
            return;
        }
        String base = base();
        String model = model();
        testBtn.setEnabled(false);
        testLine.set("测试中…", Tone.BUSY);
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            String out = runOnScratch(c -> {
                c.judgeBaseUrl(base);
                c.judgeModel(model);
                c.judgeKey(typed);
                c.proxyEnabled(config.proxyEnabled());
                c.proxyType(config.proxyType());
                c.proxyHost(config.proxyHost());
                c.proxyPort(config.proxyPort());
            }, c -> new LlmJudgeClient(c).ping());
            long ms = System.currentTimeMillis() - start;
            SwingUtilities.invokeLater(() -> {
                testBtn.setEnabled(true);
                boolean ok = out.startsWith("成功");
                keyTested = ok;
                testLine.set(ok ? "成功 " + ms + "ms，可以进入下一步" : out,
                    ok ? Tone.OK : Tone.BAD);
                // Only re-derives the button; page 3 is still gated in go().
                next.setEnabled(step != 2 || ok);
            });
        }, "onboard-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Drops an earlier pass as soon as the route on screen changes. */
    private void invalidateTest() {
        if (!keyTested) return;
        keyTested = false;
        testLine.set("测试通过才能进入下一步", Tone.IDLE);
        next.setEnabled(false);
    }

    /**
     * Runs [probe] against a throwaway config seeded from the walkthrough, then
     * deletes the temp file — so trying a key never writes it to the real one.
     */
    private static String runOnScratch(Consumer<Config> seed, Function<Config, String> probe) {
        Path file = null;
        try {
            file = Files.createTempFile("aichat-onboard-test", ".json");
            Config scratch = new Config(file);
            seed.accept(scratch);
            return probe.apply(scratch);
        } catch (Exception e) {
            return "失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A leftover scratch file in the temp dir is harmless.
                }
            }
        }
    }

    /** The route 「自定义」 stands for: whatever the live config already points at. */
    private String base() {
        Preset p = PRESETS.get(presetIndex);
        return p.base().isBlank() ? config.judgeBaseUrl() : p.base();
    }

    private String model() {
        Preset p = PRESETS.get(presetIndex);
        return p.model().isBlank() ? config.judgeModel() : p.model();
    }

    private int detectPreset() {
        String base = config.judgeBaseUrl();
        String model = config.judgeModel();
        for (int i = 0; i < PRESETS.size(); i++) {
            Preset p = PRESETS.get(i);
            if (!p.base().isBlank() && p.base().equals(base) && p.model().equals(model)) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------------ atoms

    /** `.steps`: three 24px dots, done / current / future, joined by hairlines. */
    private static final class Steps extends JComponent {

        private static final int DOT = 24;

        private int current = 1;

        Steps() {
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(10, DOT));
            setMinimumSize(new Dimension(10, DOT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, DOT));
            ThemeManager.addListener(this::repaint);
        }

        void setCurrent(int step) {
            current = step;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                int gap = Tokens.SP3;
                // `.step-line { flex: 1 }`: whatever is left after the dots and
                // the four gaps, split between the two lines.
                int line = Math.max(8, (getWidth() - DOT * 3 - gap * 4) / 2);
                int x = 0;
                for (int i = 1; i <= 3; i++) {
                    dot(g2, t, x, i);
                    x += DOT;
                    if (i < 3) {
                        x += gap;
                        g2.setColor(t.border2());
                        g2.fillRect(x, DOT / 2, line, 1);
                        x += line + gap;
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private void dot(Graphics2D g2, Tokens t, int x, int i) {
            boolean done = i < current;
            boolean active = i == current;
            Kit.fill(g2, x, 0, DOT, DOT, Tokens.R_FULL,
                done ? t.successWeak() : active ? t.brand() : t.surface2());
            if (done) {
                Graphics2D gi = (Graphics2D) g2.create();
                try {
                    gi.translate(x + (DOT - 14) / 2.0, (DOT - 14) / 2.0);
                    Glyphs.check().paint(gi, 14, t.success());
                } finally {
                    gi.dispose();
                }
                return;
            }
            Graphics2D gi = (Graphics2D) g2.create();
            try {
                gi.setFont(Theme.font(active ? Font.BOLD : Font.PLAIN, Tokens.FS_CAPTION));
                gi.setColor(active ? t.brandInk() : t.text3());
                String label = String.valueOf(i);
                FontMetrics fm = gi.getFontMetrics();
                gi.drawString(label, x + (DOT - fm.stringWidth(label)) / 2,
                    (DOT + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                gi.dispose();
            }
        }
    }

    /** `.test-result`: one line, tinted by the last test's outcome. */
    private enum Tone { IDLE, BUSY, OK, BAD }

    private static final class TestLine extends JComponent {

        /** `.test-result` is `min-height: 34px`, the same box as an `.input`. */
        private static final int HEIGHT = 34;

        private String text = "测试通过才能进入下一步";
        private Tone tone = Tone.IDLE;

        TestLine() {
            setOpaque(false);
            Dimension d = new Dimension(10, HEIGHT);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
            ThemeManager.addListener(this::repaint);
        }

        void set(String text, Tone tone) {
            this.text = text == null ? "" : text;
            this.tone = tone;
            setToolTipText(this.text);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                Color bg = switch (tone) {
                    case OK -> t.successWeak();
                    case BAD -> t.dangerWeak();
                    default -> t.surface1();
                };
                Color fg = switch (tone) {
                    case OK -> t.success();
                    case BAD -> t.danger();
                    default -> t.text2();
                };
                // `.test-result { padding: 6px 8px; border-radius: 6px }`.
                Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM, bg);
                g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
                g2.setColor(fg);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(clip(fm, text, getWidth() - Tokens.SP3 * 2), Tokens.SP3,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }

        /** A failure report can be arbitrarily long; the tooltip keeps all of it. */
        private static String clip(FontMetrics fm, String text, int width) {
            if (fm.stringWidth(text) <= width) return text;
            String out = text;
            while (out.length() > 1 && fm.stringWidth(out + "…") > width) {
                out = out.substring(0, out.length() - 1);
            }
            return out + "…";
        }
    }

    /** A glyph in a fixed box, centred — the kit's `Glyph` as a component. */
    private static final class GlyphBox extends JComponent {

        private final Glyph glyph;
        private final int size;
        private final Function<Tokens, Color> color;

        GlyphBox(Glyph glyph, int size, Function<Tokens, Color> color) {
            this.glyph = glyph;
            this.size = size;
            this.color = color;
            setOpaque(false);
            setAlignmentX(CENTER_ALIGNMENT);
            ThemeManager.addListener(this::repaint);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(size, size);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Graphics2D gi = (Graphics2D) g2.create();
                try {
                    gi.translate((getWidth() - size) / 2.0, (getHeight() - size) / 2.0);
                    glyph.paint(gi, size, color.apply(Theme.tokens()));
                } finally {
                    gi.dispose();
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** `.pledge__item`: a success tick and one promise, on a soft row. */
    private static JComponent pledge(String text) {
        Surface row = new Surface(Surface.subtle(Tokens.R_MD), new BorderLayout(Tokens.SP3, 0));
        row.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));
        row.add(new GlyphBox(Glyphs.check(), 16, Tokens::success), BorderLayout.WEST);
        row.add(new Lbl(text, 0, Tokens.FS_BODY, Font.PLAIN, Tokens::text1, false), BorderLayout.CENTER);
        return pin(row);
    }

    /**
     * Keeps a button at its own width in the middle of a stretched row.
     *
     * `Btn` reports an unbounded maximum width so it can be a block button, which
     * means a box column would stretch it across the whole dialog; the wrapper is
     * what stops that.
     */
    private static JComponent centeredRow(JComponent child) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(CENTER_ALIGNMENT);
        row.add(child);
        return pin(row);
    }

    /** Password has no change hook of its own, so the gate is reset from here. */
    private static final class OnEdit implements DocumentListener {

        private final Runnable action;

        OnEdit(Runnable action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            action.run();
        }
    }

    private static JLabel heading(String text) {
        return new Lbl(text, 0, Tokens.FS_DISPLAY, Font.BOLD, Tokens::text1, false);
    }

    private static JLabel wrapped(String text) {
        return new Lbl(text, BODY_WRAP, Tokens.FS_BODY, Font.PLAIN, Tokens::text2, false);
    }

    /**
     * A label that never stretches in a box column and re-reads the palette when
     * the theme changes. A positive [wrapWidth] turns it into wrapped HTML, which
     * is what keeps a long promise or error from widening the dialog.
     *
     * [centered] does both halves of centring: `alignmentX` places the label in
     * the column, and `horizontalAlignment` places the text inside a label the
     * column has stretched to full width. Setting only the first leaves the text
     * hard against the left edge of a full-width box.
     */
    private static final class Lbl extends JLabel {

        private final float size;
        private final int style;
        private final Function<Tokens, Color> color;
        private final String raw;
        private final int wrapWidth;
        private final boolean centered;

        Lbl(String text, int wrapWidth, float size, int style,
            Function<Tokens, Color> color, boolean centered) {
            super(text == null ? "" : text);
            this.raw = text == null ? "" : text;
            this.wrapWidth = wrapWidth;
            this.size = size;
            this.style = style;
            this.color = color;
            this.centered = centered;
            setAlignmentX(centered ? CENTER_ALIGNMENT : LEFT_ALIGNMENT);
            setHorizontalAlignment(centered ? SwingConstants.CENTER : SwingConstants.LEADING);
            restyle();
            ThemeManager.addListener(this::restyle);
        }

        private void restyle() {
            setFont(Theme.font(style, size));
            setForeground(color.apply(Theme.tokens()));
            setText(wrapWidth > 0
                ? "<html><div style='width:" + wrapWidth + "px"
                    + (centered ? ";text-align:center" : "") + "'>" + escapeHtml(raw) + "</div></html>"
                : raw);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static <T extends JComponent> T pin(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
