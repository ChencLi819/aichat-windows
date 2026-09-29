package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.capture.ScreenGrabber;
import com.jev.probe.desktop.capture.VisionReader;
import com.jev.probe.desktop.config.CaptureTarget;
import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.kb.ContextSessions;
import com.jev.probe.desktop.kb.KbCounts;
import com.jev.probe.desktop.kb.KbStore;
import com.jev.probe.desktop.kb.SelfCheck;
import com.jev.probe.desktop.llm.LlmJudgeClient;
import com.jev.probe.desktop.llm.ReplyClient;
import com.jev.probe.desktop.ui.kit.Area;
import com.jev.probe.desktop.ui.kit.Btn;
import com.jev.probe.desktop.ui.kit.Callout;
import com.jev.probe.desktop.ui.kit.Card;
import com.jev.probe.desktop.ui.kit.DialogShell;
import com.jev.probe.desktop.ui.kit.Field;
import com.jev.probe.desktop.ui.kit.Input;
import com.jev.probe.desktop.ui.kit.Kit;
import com.jev.probe.desktop.ui.kit.Password;
import com.jev.probe.desktop.ui.kit.Pill;
import com.jev.probe.desktop.ui.kit.Segmented;
import com.jev.probe.desktop.ui.kit.Slider;
import com.jev.probe.desktop.ui.kit.Stepper;
import com.jev.probe.desktop.ui.kit.Surface;
import com.jev.probe.desktop.ui.kit.Switch;
import com.jev.probe.desktop.ui.kit.Toast;
import com.jev.probe.desktop.ui.kit.Wrap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置 — the 900×640 navigated settings dialog.
 *
 * Five pages behind a 200px left rail: 接口 · 监听与分析 · 知识与记忆 · 外观 ·
 * 高级. Every control is one of the design system's own widgets
 * ({@link Switch}, {@link Stepper}, {@link Slider}, {@link Segmented},
 * {@link Input}, {@link Password}, {@link Area}, {@link Btn}), because FR-SET-11
 * forbids mixing the system look-and-feel into a self-drawn screen — which is
 * exactly what the old 580×780 form did with its `JCheckBox` / `JToggleButton` /
 * `JSlider` mixture.
 *
 * <p>Three things the old form could not do and this one is built around:
 * <ul>
 *   <li>the three API routes are ONE shared card builder (FR-SET-02), so a
 *       change to the route layout cannot drift between 判断 / 生成 / 视觉;</li>
 *   <li>the theme, the font-size preset and the panel opacity apply the moment
 *       they change (FR-SET-08) — no restart, no "重启后生效";</li>
 *   <li>connectivity tests run against a SCRATCH config seeded from the boxes,
 *       so a key typed in to try it is never written to the real file.</li>
 * </ul>
 *
 * <p>The page skeletons are transcribed from `SETTINGS_HTML` in
 * `design/demo/assets/js/app.js`, which is the authority for what is on each
 * page and in which order: 就绪清单 then the three route cards; 监听区域 ·
 * 行为 · 关系与范围 (with 监听范围 nested inside it, as the prototype nests it);
 * 知识库统计 · 历史开关 · 上下文预算 · 已注入会话; 主题 · 不透明度 · 字号;
 * 代理 · 诊断 · 危险区.
 *
 * <p>Validation is inline: a stepper outside its range paints red, says so under
 * itself, and blocks 保存 (FR-SET-05) instead of the old silent fall-back to the
 * previous value.
 */
public final class SettingsDialog {

    private static final Logger LOG = Logger.getLogger("AICHAT");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("MM-dd HH:mm");

    private static final int NAV_WIDTH = 200;
    private static final int DIALOG_W = 900;
    private static final int DIALOG_H = 640;
    /** `.route__fields { grid-template-columns: 1fr 200px }`. */
    private static final int ROUTE_MODEL_W = 200;
    /** `.settings__nav { gap: 2px }` — the hairline between two nav rows. */
    private static final int NAV_GAP = 2;

    private final Window owner;
    private final Config config;
    private final Runnable onSaved;
    private final IntConsumer onOpacityPreview;

    private DialogShell shell;
    /** The scroll view's view — `.settings__content` is the page host inside it. */
    private JScrollPane scroll;
    private final JPanel pageHost = new JPanel(new BorderLayout());
    private final List<JComponent> pages = new ArrayList<>();
    private final List<NavItem> navItems = new ArrayList<>();
    private int currentPage;

    // ------------------------------------------------------------ 接口 controls
    private final RouteCard judgeCard;
    private final RouteCard replyCard;
    private final RouteCard visionCard;

    // --------------------------------------------------- 监听与分析 controls
    private final Switch autoAnalyze;
    private final Switch parkOffScreen;
    private final Stepper interval;
    private final Input convName;
    private final Input defaultRel;
    private final Switch relFollow;
    private final Area whitelist = new Area(2, 20);

    // --------------------------------------------------- 知识与记忆 controls
    private final Switch contextOn;
    private final Stepper historyCount;
    private final Input contextMax;
    private JLabel kbCountsLabel;
    private JLabel sessionTitle;
    private Card sessionCard;

    // ------------------------------------------------------------ 外观 controls
    private final Segmented themeSeg = new Segmented("浅色", "深色", "跟随系统").fixedWidth(260);
    private final Segmented fontSeg = new Segmented("标准", "大").fixedWidth(200);
    private final Slider opacitySlider;
    private JLabel opacityLabel;

    // ------------------------------------------------------------ 高级 controls
    private final Switch proxyOn;
    private final Input proxyHost;
    private final Stepper proxyPort;
    private final ResultArea selfCheckResult = new ResultArea();
    private JLabel dangerCountsLabel;

    // ------------------------------------------------------------- save state
    /** Inline range rules, re-pushed on 保存 so an untouched bad value still blocks. */
    private final List<RangeRule> rangeRules = new ArrayList<>();
    private final List<Supplier<String>> dirtySources = new ArrayList<>();
    private final List<String> dirtyInitial = new ArrayList<>();

    private SettingsDialog(
        Window owner, Config config, Runnable onSaved, IntConsumer onOpacityPreview
    ) {
        this.owner = owner;
        this.config = config;
        this.onSaved = onSaved;
        this.onOpacityPreview = onOpacityPreview;

        judgeCard = new RouteCard("判断接口", "必填", true, Config.TEXT_PRESETS, false,
            "测试判断", config.judgeBaseUrl(), config.judgeModel(), config.storedJudgeKey());
        replyCard = new RouteCard("生成接口", "候选回复 / 问 AI", false, Config.TEXT_PRESETS, true,
            "测试生成", config.replyBaseUrl(), config.replyModel(), config.replyKey());
        visionCard = new RouteCard("视觉接口", "必填", true, Config.VISION_PRESETS, false,
            "测试视觉", config.visionBaseUrl(), config.visionModel(), config.visionKey());

        autoAnalyze = new Switch(config.autoAnalyze());
        parkOffScreen = new Switch(config.parkOffScreen());
        interval = new Stepper(1, 60, config.watchIntervalSeconds());
        convName = new Input(config.conversationName()).grow();
        defaultRel = new Input(config.relationship());
        relFollow = new Switch(true);
        whitelist.text(String.join("\n", config.whitelist()));
        whitelist.placeholder("林小满、灯塔项目群");

        contextOn = new Switch(config.contextEnabled());
        historyCount = new Stepper(0, 100, config.contextHistoryCount());
        contextMax = new Input(String.valueOf(config.contextMaxTokens()));

        opacitySlider = new Slider(60, 100, config.panelOpacity());
        opacityLabel = themedLabel("不透明度：" + config.panelOpacity() + "%",
            Font.PLAIN, Tokens.FS_NOTE, Tokens::text2);

        proxyOn = new Switch(config.proxyEnabled());
        proxyHost = new Input(config.proxyHost()).grow();
        proxyPort = new Stepper(1, 65535, config.proxyPort());

        wireAppearance();
        wireProbes();
        buildDirtySources();
        buildShell();
        buildPages();
        refreshCounts();
        refreshSessions();
        snapshotDirty();
        showPage(0);
    }

    /**
     * Show the dialog and block until it is dismissed.
     *
     * @param onSaved run after the values were written; may be null.
     * @param onOpacityPreview live preview of the opacity slider; may be null.
     */
    public static void show(
        Window owner, Config config, Runnable onSaved, IntConsumer onOpacityPreview
    ) {
        new SettingsDialog(owner, config, onSaved, onOpacityPreview).shell.show();
    }

    // ---------------------------------------------------------------- chrome

    private void buildShell() {
        shell = DialogShell.modal(owner, "设置", DIALOG_W, DIALOG_H);
        shell.onClose(this::requestClose);

        // The rail belongs BETWEEN the header and the footer: in the prototype
        // `.dialog__head` and `.dialog__foot` both span the full 900px and only
        // `.settings__main` is split. DialogShell has no slot for that — its
        // CENTER is the scrolling body — so the scroll pane is lifted out of
        // CENTER and put beside the rail in a row that takes its place. Dropping
        // the rail into the root's WEST instead, which is the obvious first move,
        // stretches it the full height of the window and indents the 设置 title
        // and the footer buttons by 200px.
        scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, shell.body());
        JPanel root = (JPanel) shell.dialog().getContentPane();
        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        root.remove(scroll);
        main.add(scroll, BorderLayout.CENTER);
        main.add(buildNav(), BorderLayout.WEST);
        root.add(main, BorderLayout.CENTER);

        // `.dialog__foot`: 取消 on the left of 保存, both right-aligned. Without
        // this pair the only way out was Esc and the header's close button, and
        // `save()` was unreachable.
        Btn cancel = new Btn("取消", Btn.Variant.SECONDARY);
        cancel.onClick(this::requestClose);
        Btn save = new Btn("保存", Btn.Variant.PRIMARY);
        save.onClick(this::save);
        shell.footer(cancel, save);

        // `shell.show()` centres on the owner before making the window visible,
        // and the owner is the floating panel, which lives near the right edge
        // of the screen. The centring therefore has to be corrected afterwards —
        // but only between "made visible" and "first paint", which is exactly
        // when the shown event fires, so the window never appears off-screen.
        shell.dialog().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                placeOnScreen();
            }
        });
    }

    /**
     * `.settings__nav` — 200px of surface-1, a 1px divider on its right, 36px
     * rows 2px apart.
     *
     * Built as a sibling of the scrolling content rather than as a child of it,
     * so it stays put while a page scrolls.
     */
    private JPanel buildNav() {
        Surface nav = new Surface((g2, w, h, t) -> {
            g2.setColor(t.surface1());
            g2.fillRect(0, 0, w, h);
            g2.setColor(t.border1());
            g2.fillRect(w - 1, 0, 1, h);
        });
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP3, Tokens.SP3, Tokens.SP3));
        nav.setPreferredSize(new Dimension(NAV_WIDTH, 100));

        String[] labels = {"接口", "监听与分析", "知识与记忆", "外观", "高级"};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) nav.add(Box.createVerticalStrut(NAV_GAP));
            NavItem item = new NavItem(i, labels[i]);
            navItems.add(item);
            nav.add(item);
        }
        return nav;
    }

    private void buildPages() {
        // `.settings__content { padding: var(--sp-5) var(--sp-6) }` — 16px top
        // and bottom, 20px left and right.
        pageHost.setOpaque(false);
        pageHost.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        pageHost.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP5, Tokens.SP6, Tokens.SP5, Tokens.SP6));

        pages.add(apiPage());
        pages.add(watchPage());
        pages.add(memoryPage());
        pages.add(lookPage());
        pages.add(advPage());

        shell.body().add(pageHost);
        shell.body().revalidate();
    }

    /**
     * Swaps the visible page and sends the reader back to its top.
     *
     * One page at a time rather than a `CardLayout`: CardLayout sizes every card
     * to the tallest one it holds, so the short pages — 外观 is barely half a
     * screen — were laid out inside a body as tall as 接口. That alone left dead
     * space under them; combined with a scroll offset that survived the switch,
     * it is what made 外观 look like an empty window that only responded to a
     * manual scroll to the top. The readiness checklist's jump targets go through
     * here too, so they behave the same way as a click on the rail.
     */
    private void showPage(int index) {
        if (index < 0 || index >= pages.size()) return;
        currentPage = index;
        pageHost.removeAll();
        pageHost.add(pages.get(index), BorderLayout.NORTH);
        pageHost.revalidate();
        pageHost.repaint();
        for (NavItem n : navItems) n.repaint();
        resetScroll();
    }

    /**
     * Puts the viewport back at the top of the page that just became visible.
     *
     * The reset is issued twice on purpose: swapping the page revalidates the
     * body, and the view's new size is not known until that layout pass has run,
     * by which point a `setViewPosition` issued here can already have been
     * clamped against the previous page's extent. The deferred call re-applies it
     * once the swap has settled.
     */
    private void resetScroll() {
        scroll.getViewport().setViewPosition(new Point(0, 0));
        SwingUtilities.invokeLater(
            () -> scroll.getViewport().setViewPosition(new Point(0, 0)));
    }

    /**
     * Pulls the dialog back inside the visible part of the screen it opened on.
     *
     * `DialogShell.show()` centres on the owner, and the owner is the floating
     * panel, which parks itself at `screen.width - 460 - 48`. Centring a 900px
     * dialog on that pushes its right edge past the screen — on a 1536-wide
     * display it lands with ~10px to spare, and on 1366 it hangs off the edge
     * with the 保存 button out of reach. The shell is shared and must not be
     * changed, so the arithmetic happens here, against the screen that actually
     * contains the owner rather than against the panel's own rectangle.
     *
     * The size the shell installed is kept: the design fixes the window at
     * 900×640 and `getPreferredSize()` here is the sum of the header, body and
     * footer preferences, which is not that size. It is only trimmed — never
     * below the shell's own minimum — when the screen is too small to hold the
     * design size at all, since moving an oversized window around does not make
     * it fit.
     */
    private void placeOnScreen() {
        JDialog dialog = shell.dialog();
        // 用 Screens 取「宿主面板所在那块屏」的工作区：混合 DPI 下 AWT 的逻辑
        // 坐标与物理像素不一致，只有它能同时给出正确的屏幕归属与可用区域。
        Window owner = dialog.getOwner();
        Screens.Monitor target = Screens.of(owner != null ? owner : dialog);
        Rectangle usable = target.workLogical();

        Dimension size = dialog.getSize();
        if (size.width > usable.width || size.height > usable.height) {
            Dimension floor = dialog.getMinimumSize();
            dialog.setSize(
                Math.max(Math.min(size.width, usable.width), floor.width),
                Math.max(Math.min(size.height, usable.height), floor.height));
            size = dialog.getSize();
        }
        dialog.setLocation(
            clamp(dialog.getX(), usable.x, usable.x + usable.width - size.width),
            clamp(dialog.getY(), usable.y, usable.y + usable.height - size.height));
    }

    /** `hi` below `lo` means the window is wider than the screen; keep `lo`. */
    private static int clamp(int value, int lo, int hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, value));
    }

    // ------------------------------------------------------------- 接口 page

    private JPanel apiPage() {
        JPanel page = Theme.column();
        page.add(DialogShell.pageHeader("接口",
            "三路接口都是 OpenAI 兼容的 /chat/completions，只有地址和模型不同。一把密钥就能跑完整条链路。"));
        page.add(DialogShell.gap(Tokens.SP5));
        page.add(readyRow());
        // `.ready { margin-bottom: var(--sp-5) }`, on top of the 16px the page
        // description already carries.
        page.add(DialogShell.gap(Tokens.SP5));
        // No `.group__title` here in the prototype: the three cards are the group.
        page.add(group(
            judgeCard.build(),
            DialogShell.gap(Tokens.SP4),
            replyCard.build(),
            DialogShell.gap(Tokens.SP4),
            visionCard.build()));
        return page;
    }

    /** FR-SET-09: three clickable readiness cards, each jumping to its page. */
    private JComponent readyRow() {
        boolean judgeOk = config.hasKey();
        boolean visionOk = new VisionReader(config).isConfigured();
        boolean regionOk = config.captureTarget() != null;

        JPanel row = new JPanel(new GridLayout(1, 3, Tokens.SP3, 0));
        row.setOpaque(false);
        row.add(new ReadyItem(judgeOk ? "判断接口 已连通" : "判断接口 未配置密钥",
            judgeOk, () -> showPage(0)));
        row.add(new ReadyItem(visionOk ? "视觉接口 已连通" : "视觉接口 未配置",
            visionOk, () -> showPage(0)));
        row.add(new ReadyItem(regionOk ? "监听区域 已绑定" : "监听区域 需重新框选",
            regionOk, () -> showPage(1)));

        JPanel wrap = new JPanel(new BorderLayout(Tokens.SP3, 0));
        wrap.setOpaque(false);
        wrap.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        wrap.add(row, BorderLayout.CENTER);
        if (judgeOk && visionOk && regionOk) {
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, Tokens.SP4));
            east.setOpaque(false);
            east.add(new Pill("已就绪", Pill.Kind.SUCCESS));
            wrap.add(east, BorderLayout.EAST);
        }
        // After the children: a BoxLayout clamps a child to its maximum size, so
        // measuring before the row is in would collapse it to nothing.
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
        return wrap;
    }

    // ------------------------------------------------- 监听与分析 page

    private JPanel watchPage() {
        JPanel page = Theme.column();
        page.add(DialogShell.pageHeader("监听与分析",
            "决定它看哪里、多久看一次、以及什么时候自动分析。"));
        page.add(DialogShell.gap(Tokens.SP5));

        page.add(DialogShell.groupTitle("监听区域"));
        page.add(DialogShell.gap(Tokens.SP3));
        page.add(group(cardWith(regionRow())));

        page.add(DialogShell.groupTitle("行为"));
        page.add(DialogShell.gap(Tokens.SP3));
        page.add(group(cardWith(
            switchRow("对方发消息时自动分析", "关闭后只有手动点击才分析", autoAnalyze),
            switchRow("最小化时移到屏幕外", "最小化或收进托盘后它不再罢工，改为躲到屏幕外继续监听；"
                + "把它叫回来会自动回到原位", parkOffScreen),
            stepperRow("监听间隔", "1–60 秒，越小越灵敏、也越费接口", interval),
            inputRow("会话名称", "截图读不到标题时使用", convName, 220))));

        // 监听范围 is a SECOND heading inside 关系与范围, not a group of its own —
        // that is the nesting the prototype's DOM has, and it is what keeps the
        // white-list card visually attached to the scope it belongs to.
        page.add(DialogShell.groupTitle("关系与范围"));
        page.add(DialogShell.gap(Tokens.SP3));
        page.add(group(
            relationCallout(),
            DialogShell.gap(Tokens.SP4),
            cardWith(
                defaultRelationField(),
                switchRow("关系随会话自动切换",
                    "切换聊天窗口时，自动套用该会话人的关系；认不出人时回落默认关系", relFollow)),
            DialogShell.gap(Tokens.SP5),
            DialogShell.groupTitle("监听范围"),
            DialogShell.gap(Tokens.SP3),
            cardWith(new Field("会话白名单（每行一个关键词，空 = 所有会话）", whitelist))));
        return page;
    }

    private JComponent regionRow() {
        CaptureTarget target = config.captureTarget();
        String title;
        String desc;
        if (target == null) {
            title = "尚未选择监听区域";
            desc = "点「重新框选」框住聊天窗口内部的聊天区";
        } else {
            title = target.describe();
            String cls = target.windowClass();
            desc = (cls == null || cls.isBlank() ? "" : "窗口类 " + cls + " · ")
                + "区域 " + target.width() + "×" + target.height();
        }
        Btn again = new Btn("重新框选", Btn.Variant.SECONDARY);
        again.onClick(this::rechooseRegion);
        return DialogShell.row(
            labelledRow(title, mutedLabel(desc)), again);
    }

    /**
     * Hands control back for a fresh region pick.
     *
     * The picker itself belongs to the panel/`ChatPipeline` (「⋯ → 选择区域」);
     * the entry point's frozen signature carries no region hook, so this saves
     * what is on screen, tells the caller to reconfigure, and closes — which is
     * what makes the pipeline re-read the (new) target.
     */
    private void rechooseRegion() {
        if (!validate()) return;
        writeConfig();
        snapshotDirty();
        if (onSaved != null) onSaved.run();
        shell.dialog().dispose();
    }

    private JComponent relationCallout() {
        Callout callout = new Callout(Callout.Kind.INFO, "关系按「会话人」分别设置",
            "伴侣、同事、客户……不同聊天对象的关系不同，分析语气与建议也随之不同。\n"
                + "关系保存在各自的联系人卡片上，切换会话时自动跟随；此项只是没认出联系人时的兜底。");
        Btn manage = new Btn("按联系人管理关系 →", Btn.Variant.SECONDARY, Btn.Size.SM);
        manage.onClick(this::openKnowledge);
        return callout.action(manage);
    }

    private JComponent defaultRelationField() {
        defaultRel.setPreferredSize(new Dimension(200, Input.HEIGHT));
        defaultRel.setMinimumSize(new Dimension(200, Input.HEIGHT));
        defaultRel.placeholder("留空 = 视为「未说明」");
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(defaultRel);
        return new Field("默认关系（仅在未识别到联系人时兜底）", wrap);
    }

    // ------------------------------------------------- 知识与记忆 page

    private JPanel memoryPage() {
        JPanel page = Theme.column();
        page.add(DialogShell.pageHeader("知识与记忆",
            "笔记会被注入分析；联系人用于跨会话认人。默认不记录任何聊天历史。"));
        page.add(DialogShell.gap(Tokens.SP5));

        kbCountsLabel = mutedLabel("");
        Btn open = new Btn("打开知识库", Btn.Variant.SECONDARY);
        open.onClick(this::openKnowledge);
        page.add(group(cardWith(
            labelledRow("知识库", kbCountsLabel, open),
            switchRow("记录聊天历史", "只存本机，用于关联上下文", contextOn),
            stepperRow("注入最近历史条数", "0–100", historyCount),
            validatedInputRow("单个会话上下文上限（tokens）", "1,000 – 2,000,000",
                contextMax, 140, this::contextMaxError))));

        // `.group__title` and its card live in the same group, and the card keeps
        // the prototype's own 8/12 padding rather than the default 12 all round.
        sessionTitle = DialogShell.groupTitle("已注入的会话（0）");
        sessionCard = Card.plain().pad(Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4);
        page.add(group(sessionTitle, DialogShell.gap(Tokens.SP3), sessionCard));
        return page;
    }

    private String contextMaxError() {
        String raw = contextMax.value().trim().replace(",", "");
        if (raw.isEmpty()) return "请填写数字";
        try {
            int v = Integer.parseInt(raw);
            if (v < Config.MIN_CONTEXT_MAX_TOKENS || v > Config.MAX_CONTEXT_MAX_TOKENS) {
                return "需要在 1,000–2,000,000 之间";
            }
            return null;
        } catch (NumberFormatException e) {
            return "请填写数字";
        }
    }

    private void refreshCounts() {
        KbCounts c = KbStore.get().counts();
        if (kbCountsLabel != null) {
            kbCountsLabel.setText("当前 " + c.notes() + " 条笔记、"
                + c.contacts() + " 个联系人、" + c.logLines() + " 条历史");
        }
        if (dangerCountsLabel != null) {
            dangerCountsLabel.setText("删除 " + c.notes() + " 条笔记、"
                + c.contacts() + " 个联系人、" + c.logLines() + " 条历史，不可恢复");
        }
    }

    private void refreshSessions() {
        List<ContextSessions.SessionSummary> all = ContextSessions.summary();
        if (sessionTitle != null) sessionTitle.setText("已注入的会话（" + all.size() + "）");
        if (sessionCard == null) return;
        sessionCard.removeAll();
        if (all.isEmpty()) {
            sessionCard.add(mutedLabel("当前没有注入任何会话上下文"));
        } else {
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) sessionCard.add(DialogShell.gap(Tokens.SP2));
                ContextSessions.SessionSummary s = all.get(i);
                Btn clear = new Btn("清除", Btn.Variant.DANGER, Btn.Size.SM);
                clear.onClick(() -> {
                    // FR-COM-03: dropping a session's injected history is
                    // destructive, so it asks first — the danger zone is not the
                    // only place that can delete something.
                    boolean yes = Confirm.ask(shell.dialog(), "清除会话上下文",
                        "将删除「" + s.title() + "」已注入的 " + s.entries()
                            + " 条上下文，之后的判断不再参考这段对话。不可恢复。",
                        "清除", Confirm.Kind.DANGER);
                    if (!yes) return;
                    ContextSessions.clear(s.title());
                    refreshSessions();
                });
                sessionCard.add(DialogShell.row(DialogShell.labelled(s.title(),
                    s.entries() + " 条 · " + STAMP.format(new Date(s.injectedAt()))), clear));
            }
        }
        sessionCard.pinHeight();
        sessionCard.revalidate();
        sessionCard.repaint();
    }

    // ------------------------------------------------------------ 外观 page

    private JPanel lookPage() {
        JPanel page = Theme.column();
        page.add(DialogShell.pageHeader("外观", "切换即时生效，无需重启。"));
        page.add(DialogShell.gap(Tokens.SP5));

        page.add(group(
            DialogShell.groupTitle("主题"),
            DialogShell.gap(Tokens.SP3),
            cardWith(DialogShell.row(DialogShell.labelled("配色", null), themeSeg))));

        page.add(group(
            DialogShell.groupTitle("悬浮面板"),
            DialogShell.gap(Tokens.SP3),
            cardWith(
                opacityBlock(),
                DialogShell.row(DialogShell.labelled("字号",
                    "大字号会同步放大分析结果与候选回复"), fontSeg))));
        return page;
    }

    private void wireAppearance() {
        themeSeg.setSelected(themeIndex(config.themeMode()));
        themeSeg.onChange(i -> {
            String mode = switch (i) {
                case 1 -> Config.THEME_DARK;
                case 2 -> Config.THEME_SYSTEM;
                default -> Config.THEME_LIGHT;
            };
            config.themeMode(mode);
            ThemeManager.setMode(mode);
            shell.dialog().repaint();
        });

        fontSeg.setSelected(config.isFontLarge() ? 1 : 0);
        fontSeg.onChange(i -> {
            boolean large = i == 1;
            config.fontScale(large ? Config.FONT_LARGE : Config.FONT_STANDARD);
            ThemeManager.setFontScale(
                large ? ThemeManager.SCALE_LARGE : ThemeManager.SCALE_STANDARD);
            shell.dialog().repaint();
        });

        opacitySlider.onChange(v -> {
            opacityLabel.setText("不透明度：" + v + "%");
            config.panelOpacity(v);
            if (onOpacityPreview != null) onOpacityPreview.accept(v);
        });
    }

    private JComponent opacityBlock() {
        opacitySlider.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        JPanel col = Theme.column();
        col.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        col.add(opacityLabel);
        col.add(DialogShell.gap(Tokens.SP2));
        col.add(opacitySlider);
        col.setMaximumSize(new Dimension(Integer.MAX_VALUE, col.getPreferredSize().height));
        return col;
    }

    private static int themeIndex(String mode) {
        if (Config.THEME_DARK.equalsIgnoreCase(mode)) return 1;
        if (Config.THEME_SYSTEM.equalsIgnoreCase(mode)) return 2;
        return 0;
    }

    // ------------------------------------------------------------ 高级 page

    private JPanel advPage() {
        JPanel page = Theme.column();
        page.add(DialogShell.pageHeader("高级", "网络、诊断与数据管理。"));
        page.add(DialogShell.gap(Tokens.SP5));

        page.add(group(
            DialogShell.groupTitle("网络代理（VPN / 加速器）"),
            DialogShell.gap(Tokens.SP3),
            cardWith(
                switchRow("启用代理", "所有模型请求都会走代理", proxyOn),
                grid2(new Field("地址", proxyHost.grow()),
                    new Field("端口", proxyPort)))));

        page.add(group(
            DialogShell.groupTitle("诊断"),
            DialogShell.gap(Tokens.SP3),
            cardWith(selfCheckRow())));

        // The danger card carries its own 危险区 heading (`.danger-zone
        // .group__title`), so it is the whole group.
        page.add(group(dangerZone()));
        return page;
    }

    private JComponent selfCheckRow() {
        Btn run = new Btn("运行自检", Btn.Variant.SECONDARY);
        run.onClick(() -> {
            run.setEnabled(false);
            selfCheckResult.show("自检中…", ResultArea.State.NEUTRAL);
            Thread t = new Thread(() -> {
                String report;
                try {
                    report = SelfCheck.run();
                } catch (Exception ex) {
                    report = "自检失败（1）：" + describe(ex);
                }
                String out = report;
                SwingUtilities.invokeLater(() -> {
                    run.setEnabled(true);
                    selfCheckResult.show(out, out.startsWith("自检失败")
                        ? ResultArea.State.BAD : ResultArea.State.OK);
                });
            }, "settings-selfcheck");
            t.setDaemon(true);
            t.start();
        });
        selfCheckResult.show("自检会检查：密钥、视觉接口、监听区域、窗口可见性",
            ResultArea.State.NEUTRAL);

        JPanel row = new JPanel(new BorderLayout(Tokens.SP3, 0));
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.add(run, BorderLayout.WEST);
        row.add(selfCheckResult, BorderLayout.CENTER);
        return row;
    }

    private JComponent dangerZone() {
        Card danger = Card.danger();
        danger.add(themedLabel("危险区", Font.BOLD, Tokens.FS_NOTE, Tokens::danger));
        danger.add(DialogShell.gap(Tokens.SP4));

        dangerCountsLabel = mutedLabel("");
        Btn wipe = new Btn("清空…", Btn.Variant.DANGER_SOLID);
        wipe.onClick(() -> {
            KbCounts c = KbStore.get().counts();
            boolean yes = Confirm.ask(shell.dialog(), "清空知识库与聊天历史",
                "将删除 " + c.notes() + " 条笔记、" + c.contacts() + " 个联系人、"
                    + c.logLines() + " 条聊天历史。密钥、白名单等设置不受影响。不可恢复。",
                "清空", Confirm.Kind.DANGER);
            if (!yes) return;
            KbStore.get().clearAll();
            refreshCounts();
            refreshSessions();
            Toast.show(owner, "已清空知识库与历史");
        });
        danger.add(labelledRow("清空知识库与聊天历史", dangerCountsLabel, wipe));
        danger.add(DialogShell.gap(Tokens.SP4));

        Btn restore = new Btn("恢复默认…", Btn.Variant.DANGER);
        restore.onClick(() -> {
            boolean yes = Confirm.ask(shell.dialog(), "恢复默认设置",
                "外观、行为、关系、白名单与代理都会恢复为默认值；密钥、接口地址与知识库保留。不可恢复。",
                "恢复默认", Confirm.Kind.DANGER);
            if (!yes) return;
            restoreDefaults();
        });
        danger.add(labelledRow("恢复默认设置", mutedLabel("保留密钥与知识库"), restore));
        return danger;
    }

    /** Every non-secret, non-route setting back to its shipped value. */
    private void restoreDefaults() {
        config.autoAnalyze(true);
        config.parkOffScreen(true);
        config.watchIntervalSeconds(1);
        config.conversationName("");
        config.relationship(Config.DEFAULT_REL);
        config.whitelist(Set.of());
        config.contextEnabled(false);
        config.contextHistoryCount(30);
        config.contextMaxTokens(Config.DEFAULT_CONTEXT_MAX_TOKENS);
        config.proxyEnabled(false);
        config.proxyHost("");
        config.proxyPort(7890);
        config.themeMode(Config.THEME_LIGHT);
        config.fontScale(Config.FONT_STANDARD);
        config.panelOpacity(96);

        autoAnalyze.setOn(true);
        parkOffScreen.setOn(true);
        interval.setValue(1);
        convName.text("");
        defaultRel.text(Config.DEFAULT_REL);
        relFollow.setOn(true);
        whitelist.text("");
        contextOn.setOn(false);
        historyCount.setValue(30);
        contextMax.text(String.valueOf(Config.DEFAULT_CONTEXT_MAX_TOKENS));
        proxyOn.setOn(false);
        proxyHost.text("");
        proxyPort.setValue(7890);
        themeSeg.setSelected(0);
        fontSeg.setSelected(0);
        opacitySlider.setValue(96);
        opacityLabel.setText("不透明度：96%");
        ThemeManager.setMode(Config.THEME_LIGHT);
        ThemeManager.setFontScale(ThemeManager.SCALE_STANDARD);
        if (onOpacityPreview != null) onOpacityPreview.accept(96);
        if (onSaved != null) onSaved.run();
        snapshotDirty();
        Toast.show(owner, "已恢复默认设置");
        shell.dialog().repaint();
    }

    // ------------------------------------------------------------- fragments

    /**
     * `.group` — a stack with a 24px bottom margin.
     *
     * Written out here rather than taken from {@code DialogShell.group}, which
     * wraps at 20: the reference gives `.group` 24px, and the difference is
     * visible where two groups meet, because neither carries a top margin.
     */
    private static JPanel group(JComponent... children) {
        JPanel p = Theme.column();
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (JComponent c : children) p.add(c);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, Tokens.SP7, 0));
        return p;
    }

    /** A card whose rows sit 12px apart, as the prototype's `.row` spacing does. */
    private static Card cardWith(JComponent... children) {
        Card card = Card.plain();
        for (int i = 0; i < children.length; i++) {
            if (i > 0) card.add(DialogShell.gap(Tokens.SP4));
            children[i].setAlignmentX(JComponent.LEFT_ALIGNMENT);
            card.add(children[i]);
        }
        return card;
    }

    private static JComponent switchRow(String title, String desc, Switch sw) {
        return DialogShell.row(DialogShell.labelled(title, desc), sw);
    }

    /**
     * A stepper row. Its range error lives in a label-less {@link Field} so it
     * appears inline under the control (FR-SET-05) rather than in a popup.
     *
     * The row's own maximum height is released because `DialogShell.row` bakes
     * its height from the preferred sizes it sees at construction — with the
     * error line hidden that is one line, and the red text would then be clipped
     * the moment it appears.
     */
    private JComponent stepperRow(String title, String desc, Stepper stepper) {
        Field field = new Field(null, stepper);
        rangeRules.add(new RangeRule(field, stepper::error));
        stepper.onValidate(field::error);
        JPanel row = DialogShell.row(DialogShell.labelled(title, desc), field);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private JComponent validatedInputRow(
        String title, String desc, Input input, int width, Supplier<String> error
    ) {
        input.setPreferredSize(new Dimension(width, Input.HEIGHT));
        input.setMinimumSize(new Dimension(width, Input.HEIGHT));
        Field field = new Field(null, input);
        rangeRules.add(new RangeRule(field, error));
        input.onChange(() -> field.error(error.get()));
        JPanel row = DialogShell.row(DialogShell.labelled(title, desc), field);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private static JComponent inputRow(String title, String desc, Input input, int width) {
        input.setPreferredSize(new Dimension(width, Input.HEIGHT));
        input.setMinimumSize(new Dimension(width, Input.HEIGHT));
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(input);
        return DialogShell.row(DialogShell.labelled(title, desc), wrap);
    }

    private static JComponent labelledRow(String title, JLabel desc, JComponent... rest) {
        JPanel lab = DialogShell.labelled(title, null);
        lab.add(desc);
        return DialogShell.row(lab, rest);
    }

    private static JComponent grid2(JComponent left, JComponent right) {
        JPanel grid = new JPanel(new GridLayout(1, 2, Tokens.SP3, 0));
        grid.setOpaque(false);
        grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        grid.add(left);
        grid.add(right);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    /** A 12px muted line — the `.row__desc` of a row built by hand. */
    private static JLabel mutedLabel(String text) {
        return themedLabel(text, Font.PLAIN, Tokens.FS_CAPTION, Tokens::text3);
    }

    /**
     * A label that re-reads the palette and the 标准/大 size on every theme
     * change. Any colour or font set once at construction would be frozen, which
     * is the whole reason the old screen could not switch theme in place.
     *
     * <p>回调按组件生命周期登记（{@link ThemeManager#bind}）：设置窗每开一次都会
     * 重建整套控件，用全局 addListener 会在每次关闭后留下永远不释放的回调。
     */
    private static JLabel themedLabel(
        String text, int style, float size, Function<Tokens, Color> color
    ) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(style, size));
        label.setForeground(color.apply(Theme.tokens()));
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        Runnable restyle = () -> {
            label.setFont(Theme.font(style, size));
            label.setForeground(color.apply(Theme.tokens()));
        };
        ThemeManager.bind(label, restyle);
        return label;
    }

    // ---------------------------------------------------------------- probes

    private void wireProbes() {
        judgeCard.probe = () -> runOnScratch(c -> {
            if (c.judgeKey().isBlank()) return "请先填密钥";
            if (c.judgeBaseUrl().isBlank()) return "请填地址";
            if (c.judgeModel().isBlank()) return "请填写模型名";
            return new LlmJudgeClient(c).ping();
        });
        replyCard.probe = () -> runOnScratch(c -> {
            if (c.effectiveReplyKey().isBlank()) return "请先填密钥（或填判断接口密钥）";
            if (c.replyBaseUrl().isBlank()) return "请填地址";
            if (c.replyModel().isBlank()) return "请填写模型名";
            long t0 = System.currentTimeMillis();
            String out = new ReplyClient(c).ping();
            return "成功 " + (System.currentTimeMillis() - t0) + "ms · 返回：" + preview(out, 60);
        });
        visionCard.probe = () -> runOnScratch(c -> {
            if (c.effectiveVisionKey().isBlank()) return "请先填密钥（或填回复/判断接口密钥）";
            if (c.visionBaseUrl().isBlank()) return "请填地址";
            if (c.visionModel().isBlank()) return "请填写模型名";
            CaptureTarget target = config.captureTarget();
            if (target == null) return "先在面板上点「选择区域」框住聊天窗口，再回来测试";
            try {
                return new VisionReader(c).ping(new ScreenGrabber().grab(target));
            } catch (IOException e) {
                return "失败：无法截图 " + e.getMessage();
            }
        });
    }

    /** Writes every route box plus the proxy boxes into a throwaway config. */
    private void seedScratch(Config c) {
        c.judgeBaseUrl(judgeCard.base.value());
        c.judgeKey(judgeCard.key.value());
        c.judgeModel(judgeCard.model.value());
        c.replyBaseUrl(replyCard.base.value());
        c.replyKey(replyCard.isInheriting() ? "" : replyCard.key.value());
        c.replyModel(replyCard.model.value());
        c.visionBaseUrl(visionCard.base.value());
        c.visionKey(visionCard.key.value());
        c.visionModel(visionCard.model.value());
        c.proxyEnabled(proxyOn.isOn());
        c.proxyType(config.proxyType());
        c.proxyHost(proxyHost.value());
        c.proxyPort(proxyPort.value());
        // Not on a route page, but the probe path reads them.
        c.analysisWindow(config.analysisWindow());
        c.contextHistoryCount(config.contextHistoryCount());
        c.contextMaxTokens(config.contextMaxTokens());
        c.maxOutputTokens(config.maxOutputTokens());
    }

    /**
     * Runs [probe] against a scratch config seeded from the form, then removes
     * the scratch file. A key typed into the form is therefore never written to
     * the real config just to test it.
     */
    private String runOnScratch(Function<Config, String> probe) {
        Path file = null;
        try {
            file = Files.createTempFile("aichat-settings-test", ".json");
            Config scratch = new Config(file);
            seedScratch(scratch);
            return probe.apply(scratch);
        } catch (Exception e) {
            return "失败：" + describe(e);
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

    // ------------------------------------------------------------ save/cancel

    private void save() {
        if (!validate()) return;
        writeConfig();
        snapshotDirty();
        if (onSaved != null) onSaved.run();
        Toast.show(owner, "设置已保存");
        shell.dialog().dispose();
    }

    /** Pushes every range rule and refuses to close while any field is bad. */
    private boolean validate() {
        for (RangeRule rule : rangeRules) rule.field().error(rule.error().get());
        for (RangeRule rule : rangeRules) {
            if (rule.field().hasError()) return false;
        }
        return true;
    }

    private void writeConfig() {
        config.judgeProvider(judgeCard.provider());
        config.judgeBaseUrl(judgeCard.base.value());
        config.judgeKey(judgeCard.key.value());
        config.judgeModel(judgeCard.model.value());
        config.replyBaseUrl(replyCard.base.value());
        config.replyKey(replyCard.isInheriting() ? "" : replyCard.key.value());
        config.replyModel(replyCard.model.value());
        config.visionBaseUrl(visionCard.base.value());
        config.visionKey(visionCard.key.value());
        config.visionModel(visionCard.model.value());

        config.proxyEnabled(proxyOn.isOn());
        config.proxyHost(proxyHost.value());
        config.proxyPort(proxyPort.value());

        config.autoAnalyze(autoAnalyze.isOn());
        config.parkOffScreen(parkOffScreen.isOn());
        config.watchIntervalSeconds(interval.value());
        config.conversationName(convName.value());
        config.relationship(defaultRel.value());
        config.whitelist(splitLines(whitelist.value()));

        config.contextEnabled(contextOn.isOn());
        config.contextHistoryCount(historyCount.value());
        config.contextMaxTokens(parseAmount(contextMax.value(), config.contextMaxTokens()));
        // Theme / font / opacity are persisted the moment they change (FR-SET-08).
    }

    /** Esc, 取消 and the close button: ask before throwing away unsaved edits. */
    private void requestClose() {
        if (isDirty()) {
            boolean yes = Confirm.ask(shell.dialog(), "放弃未保存的修改？",
                "关闭后本次修改不会保存。", "放弃修改", Confirm.Kind.DANGER);
            if (!yes) return;
        }
        shell.dialog().dispose();
    }

    private void buildDirtySources() {
        for (RouteCard c : List.of(judgeCard, replyCard, visionCard)) {
            dirty(c.base::value);
            dirty(c.model::value);
            dirty(c.key::value);
        }
        dirty(() -> String.valueOf(replyCard.isInheriting()));
        dirty(() -> String.valueOf(proxyOn.isOn()));
        dirty(proxyHost::value);
        dirty(() -> String.valueOf(proxyPort.value()));
        dirty(() -> String.valueOf(autoAnalyze.isOn()));
        dirty(() -> String.valueOf(parkOffScreen.isOn()));
        dirty(() -> String.valueOf(interval.value()));
        dirty(convName::value);
        dirty(defaultRel::value);
        dirty(() -> String.valueOf(relFollow.isOn()));
        dirty(whitelist::value);
        dirty(() -> String.valueOf(contextOn.isOn()));
        dirty(() -> String.valueOf(historyCount.value()));
        dirty(contextMax::value);
    }

    private void dirty(Supplier<String> value) {
        dirtySources.add(value);
    }

    private void snapshotDirty() {
        dirtyInitial.clear();
        for (Supplier<String> s : dirtySources) dirtyInitial.add(s.get());
    }

    private boolean isDirty() {
        for (int i = 0; i < dirtySources.size(); i++) {
            if (!Objects.equals(dirtyInitial.get(i), dirtySources.get(i).get())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- knowledge

    /**
     * The knowledge dialog lives in another screen that is being rewritten in
     * parallel, so it is looked up rather than linked against: a direct call
     * would couple this file to a signature that is still moving, and a missing
     * one must not take the settings screen down with it.
     */
    private void openKnowledge() {
        try {
            Class.forName("com.jev.probe.desktop.ui.KnowledgeDialog")
                .getMethod("show", Window.class)
                .invoke(null, shell.dialog());
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            // No knowledge screen to hand off to: close so the caller can open it.
            requestClose();
        } catch (ReflectiveOperationException e) {
            LOG.fine("打开知识库失败：" + e.getClass().getSimpleName());
        }
    }

    // --------------------------------------------------------------- helpers

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
    }

    private static String preview(String text, int n) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private static String latency(String report) {
        Matcher m = Pattern.compile("(\\d+)\\s*ms").matcher(report);
        return m.find() ? " · " + m.group(1) + "ms" : "";
    }

    private static int parseAmount(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim().replace(",", ""));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Set<String> splitLines(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** One inline range check, re-run on 保存 and while the user types. */
    private record RangeRule(Field field, Supplier<String> error) {
    }

    // ------------------------------------------------------------ nav item

    /** `.nav-item`: 36px, 6px radius, brand-weak + brand when active. */
    private final class NavItem extends JComponent {

        private final int index;
        private final String text;
        private boolean hover;

        NavItem(int index, String text) {
            this.index = index;
            this.text = text;
            setOpaque(false);
            setAlignmentX(JComponent.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                    showPage(index);
                }
            });
            ThemeManager.bind(this, this::repaint);
        }

        /**
         * `.settings__nav` is a flex column, so every row stretches across it.
         * Claiming the rail's full inner width here is what makes the active
         * pill a full-width row instead of a tag wrapped around its own label,
         * which is how the rail reads in the reference.
         */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(NAV_WIDTH - Tokens.SP3 * 2, 36);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 36);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                boolean active = index == currentPage;
                if (active) {
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM, t.brandWeak());
                } else if (hover) {
                    Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM, t.surface2());
                }
                g2.setFont(Theme.font(active ? Font.BOLD : Font.PLAIN, Tokens.FS_BODY));
                g2.setColor(active ? t.brand() : hover ? t.text1() : t.text2());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, Tokens.SP3,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }

    // --------------------------------------------------------- readiness item

    /** `.ready__item` — a dot plus a line of text, and the whole card is the link. */
    private static final class ReadyItem extends JComponent {

        private static final int DOT = 8;
        private static final int HEIGHT = 34;

        private final String text;
        private final boolean ok;
        private final Runnable action;
        private boolean hover;

        ReadyItem(String text, boolean ok, Runnable action) {
            this.text = text;
            this.ok = ok;
            this.action = action;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                    if (action != null) action.run();
                }
            });
            ThemeManager.bind(this, this::repaint);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
            return new Dimension(DOT + Tokens.SP2 + fm.stringWidth(text) + Tokens.SP3 * 2,
                HEIGHT);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                Color wash = ok ? t.successWeak() : t.warningWeak();
                Color ink = ok ? t.success() : t.warning();
                Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_MD, wash);
                if (hover) {
                    Kit.stroke(g2, 0, 0, getWidth(), getHeight(), Tokens.R_MD, t.border2());
                }
                int y = (getHeight() - DOT) / 2;
                g2.setColor(ink);
                g2.fillOval(Tokens.SP3, y, DOT, DOT);
                g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, Tokens.SP3 + DOT + Tokens.SP2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------ status line

    /** `.route__status`: a 7px dot and the last test's verdict. */
    private static final class RouteStatus extends JComponent {

        private enum State { UNTESTED, OK, BAD }

        private static final int DOT = 7;

        private State state = State.UNTESTED;
        private String text = "未测试";

        RouteStatus() {
            setOpaque(false);
            ThemeManager.bind(this, this::repaint);
        }

        void set(State next, String label) {
            state = next;
            text = label;
            revalidate();
            repaint();
        }

        private Color color() {
            Tokens t = Theme.tokens();
            return switch (state) {
                case OK -> t.success();
                case BAD -> t.danger();
                default -> t.text3();
            };
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
            return new Dimension(DOT + Tokens.SP2 + fm.stringWidth(text),
                Math.max(16, fm.getHeight()));
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
                Color ink = color();
                int y = (getHeight() - DOT) / 2;
                g2.setColor(ink);
                g2.fillOval(0, y, DOT, DOT);
                g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, DOT + Tokens.SP2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------ test result

    /**
     * `.test-result`: a soft box that starts at 44px and grows to show the whole
     * report. The old form clamped it to one 430px line, which is exactly the
     * length a stack trace or a provider's JSON error never fits into
     * (FR-SET-04). It stays a real text area, so a long error can be selected
     * and copied out.
     *
     * FR-SET-04 sets the floor at 44px, not the prototype's 34px: two lines of
     * `--fs-note` (18px each) plus the box's own vertical padding need 44px
     * before the second line starts clipping, and a one-line result still reads
     * as a deliberate panel rather than a stray label.
     */
    private static final class ResultArea extends Area {

        private enum State { NEUTRAL, OK, BAD }

        /** FR-SET-04: the result area never renders shorter than this. */
        private static final int MIN_H = 44;
        private static final int FALLBACK_W = 400;

        private State state = State.NEUTRAL;

        ResultArea() {
            super(2, 20);
            readOnly();
            setAlignmentX(JComponent.LEFT_ALIGNMENT);
            ThemeManager.bind(this, this::restyle);
            restyle();
        }

        void show(String text, State state) {
            this.state = state;
            setText(text == null ? "" : text);
            setForeground(color());
            invalid(state == State.BAD);
            revalidate();
            repaint();
        }

        private void restyle() {
            setForeground(color());
            repaint();
        }

        private Color color() {
            Tokens t = Theme.tokens();
            return switch (state) {
                case OK -> t.success();
                case BAD -> t.danger();
                default -> t.text2();
            };
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, heightFor());
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(10, MIN_H);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, heightFor());
        }

        /** The height the current text needs at the width it is being shown at. */
        private int heightFor() {
            String text = getText();
            if (text == null || text.isEmpty()) return MIN_H;
            int width = getWidth() > 0
                ? getWidth() - getInsets().left - getInsets().right
                : FALLBACK_W;
            width = Math.max(40, width);
            FontMetrics fm = getFontMetrics(getFont());
            int lineHeight = fm.getHeight();
            int lines = 0;
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) {
                    lines++;
                    continue;
                }
                int used = 0;
                for (int i = 0; i < paragraph.length(); i++) {
                    int cw = fm.charWidth(paragraph.charAt(i));
                    if (used + cw > width && used > 0) {
                        lines++;
                        used = 0;
                    }
                    used += cw;
                }
                lines++;
            }
            return Math.max(MIN_H, lines * lineHeight + getInsets().top + getInsets().bottom);
        }
    }

    // -------------------------------------------------------------- route card

    /**
     * One `.card.route`. All three routes are built from this one class, which is
     * what FR-SET-02 asks for: the 判断 / 生成 / 视觉 cards cannot drift apart,
     * because there is only one layout.
     *
     * The card follows the prototype's own row order — head with the status lamp,
     * the preset pills (wrapping, as `.route__presets` does), the 沿用 switch on
     * the 生成 card, the `1fr 200px` field pair, the key field, and the test row
     * with its result box — so a difference between the three cards is a
     * difference in their data, never in their structure.
     */
    private final class RouteCard {

        private final String name;
        private final String pillText;
        private final boolean brandPill;
        private final List<Config.Preset> presets;
        private final boolean reply;
        private final String testLabel;
        private final Input base;
        private final Input model;
        private final Password key;
        private final Switch inherit;
        private final RouteStatus status = new RouteStatus();
        private final ResultArea result = new ResultArea();
        private final Wrap presetHost = new Wrap(Tokens.SP2, Tokens.SP2);
        private final JPanel keyRow = new JPanel(new BorderLayout(Tokens.SP2, 0));
        private final JPanel revealHost = new JPanel(new BorderLayout());
        private Btn testBtn;
        private Supplier<String> probe;
        /** The key held aside while 「沿用判断接口密钥」 is on. */
        private String stashedKey;

        RouteCard(
            String name, String pillText, boolean brandPill, List<Config.Preset> presets,
            boolean reply, String testLabel, String baseValue, String modelValue, String keyValue
        ) {
            this.name = name;
            this.pillText = pillText;
            this.brandPill = brandPill;
            this.presets = presets;
            this.reply = reply;
            this.testLabel = testLabel;
            this.base = new Input(baseValue).grow();
            this.model = new Input(modelValue).grow();
            this.key = new Password(keyValue).grow();
            this.stashedKey = keyValue == null ? "" : keyValue;
            this.inherit = reply ? new Switch(stashedKey.isBlank()) : null;
            presetHost.span();
        }

        boolean isInheriting() {
            return inherit != null && inherit.isOn();
        }

        String provider() {
            return presets.get(presetIndex()).provider();
        }

        Card build() {
            JPanel headLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SP3, 0));
            headLeft.setOpaque(false);
            headLeft.add(themedLabel(name, Font.BOLD, Tokens.FS_BODY, Tokens::text1));
            headLeft.add(brandPill ? Pill.brand(pillText) : Pill.neutral(pillText));

            JPanel head = new JPanel(new BorderLayout(Tokens.SP3, 0));
            head.setOpaque(false);
            head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            head.add(headLeft, BorderLayout.CENTER);
            head.add(status, BorderLayout.EAST);

            renderPresets();
            renderReveal();
            if (inherit != null) {
                inherit.onChange(this::applyInherit);
                applyInherit(inherit.isOn());
            }

            keyRow.setOpaque(false);
            keyRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            keyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Input.HEIGHT));
            keyRow.add(key, BorderLayout.CENTER);
            keyRow.add(revealHost, BorderLayout.EAST);
            Field keyField = new Field("密钥", keyRow);
            keyField.hint("密钥只写进本机配置文件，不上传、不进日志。");

            testBtn = new Btn(testLabel, Btn.Variant.SECONDARY);
            testBtn.onClick(this::runTest);
            JPanel testRow = new JPanel(new BorderLayout(Tokens.SP3, 0));
            testRow.setOpaque(false);
            testRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            testRow.add(testBtn, BorderLayout.WEST);
            testRow.add(result, BorderLayout.CENTER);

            result.show("尚未测试", ResultArea.State.NEUTRAL);

            Card card = Card.plain();
            card.add(head);
            card.add(DialogShell.gap(Tokens.SP4));
            card.add(presetHost);
            card.add(DialogShell.gap(Tokens.SP4));
            if (inherit != null) {
                card.add(DialogShell.row(
                    DialogShell.labelled("沿用判断接口密钥", "打开后无需重复填写"), inherit));
                card.add(DialogShell.gap(Tokens.SP4));
            }
            card.add(fieldsRow());
            card.add(DialogShell.gap(Tokens.SP4));
            card.add(keyField);
            card.add(DialogShell.gap(Tokens.SP4));
            card.add(testRow);
            return card;
        }

        /** `grid-template-columns: 1fr 200px`, so the model box is a fixed 200. */
        private JComponent fieldsRow() {
            Field baseField = new Field("Base URL（填到 /v1 为止）", base);
            Field modelField = new Field("模型", model);
            int h = modelField.getPreferredSize().height;
            modelField.setPreferredSize(new Dimension(ROUTE_MODEL_W, h));
            modelField.setMinimumSize(new Dimension(ROUTE_MODEL_W, h));

            JPanel row = new JPanel(new BorderLayout(Tokens.SP3, 0));
            row.setOpaque(false);
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            row.add(baseField, BorderLayout.CENTER);
            row.add(modelField, BorderLayout.EAST);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            return row;
        }

        /** The preset hit by the current boxes, else the 自定义 one at the end. */
        private int presetIndex() {
            String b = base.value().trim().replaceAll("/+$", "");
            String m = model.value().trim();
            for (int i = 0; i < presets.size(); i++) {
                Config.Preset p = presets.get(i);
                if (!p.base().isBlank() && p.base().equals(b) && p.model().equals(m)) return i;
            }
            return presets.size() - 1;
        }

        /**
         * Rebuilds the pills. A {@link Btn}'s variant is fixed at construction,
         * so the selected one cannot be restyled in place — the row is re-made,
         * which is what the prototype's class swap does too.
         */
        private void renderPresets() {
            presetHost.removeAll();
            int hit = presetIndex();
            for (int i = 0; i < presets.size(); i++) {
                Config.Preset p = presets.get(i);
                Btn pill = new Btn(p.label(),
                    i == hit ? Btn.Variant.PRIMARY : Btn.Variant.SECONDARY, Btn.Size.SM);
                pill.onClick(() -> {
                    if (!p.base().isBlank()) base.text(p.base());
                    if (!p.model().isBlank()) model.text(p.model());
                    renderPresets();
                });
                presetHost.add(pill);
            }
            presetHost.revalidate();
            presetHost.repaint();
        }

        /** FR-SET-03: the 显示/隐藏 toggle beside the key box. */
        private void renderReveal() {
            revealHost.removeAll();
            Btn toggle = new Btn(key.isRevealed() ? "隐藏" : "显示", Btn.Variant.SECONDARY);
            toggle.onClick(() -> {
                key.reveal(!key.isRevealed());
                renderReveal();
            });
            revealHost.add(toggle, BorderLayout.CENTER);
            revealHost.revalidate();
            revealHost.repaint();
        }

        /** Disabled and showing 「已沿用判断接口密钥」 while the switch is on. */
        private void applyInherit(boolean on) {
            if (inherit == null) return;
            if (on) {
                stashedKey = key.value();
                key.text("");
                key.setEnabled(false);
                key.placeholder("已沿用判断接口密钥");
            } else {
                key.setEnabled(true);
                key.placeholder("");
                key.text(stashedKey);
            }
        }

        /** A model call can take 30 seconds; the button stays disabled while it runs. */
        private void runTest() {
            if (probe == null) return;
            testBtn.setEnabled(false);
            result.show("测试中…", ResultArea.State.NEUTRAL);
            Thread t = new Thread(() -> {
                String report;
                try {
                    report = probe.get();
                } catch (Exception ex) {
                    report = "失败：" + describe(ex);
                }
                String out = report;
                SwingUtilities.invokeLater(() -> {
                    testBtn.setEnabled(true);
                    if (out.startsWith("成功")) {
                        status.set(RouteStatus.State.OK, "已连通" + latency(out));
                        result.show(out, ResultArea.State.OK);
                    } else if (out.startsWith("失败")) {
                        status.set(RouteStatus.State.BAD, "失败");
                        result.show(out, ResultArea.State.BAD);
                    } else {
                        status.set(RouteStatus.State.BAD, "失败");
                        result.show(out, ResultArea.State.NEUTRAL);
                    }
                });
            }, "settings-probe");
            t.setDaemon(true);
            t.start();
        }
    }
}
