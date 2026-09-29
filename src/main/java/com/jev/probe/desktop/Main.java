package com.jev.probe.desktop;

import com.jev.probe.desktop.capture.ScreenGrabber;
import com.jev.probe.desktop.config.CaptureTarget;
import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.ui.AssistantPanel;
import com.jev.probe.desktop.ui.Icons;
import com.jev.probe.desktop.ui.KnowledgeDialog;
import com.jev.probe.desktop.ui.OnboardingDialog;
import com.jev.probe.desktop.ui.RegionSelector;
import com.jev.probe.desktop.ui.Screens;
import com.jev.probe.desktop.ui.SettingsDialog;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.UiTrace;
import com.jev.probe.desktop.ui.kit.Menu;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Entry point.
 *
 * Startup order matters: the palette first (every component reads it), then the
 * two things the app cannot work without — a configured judge route and a
 * capture region. A first run goes through the three-step walkthrough rather
 * than being dropped straight into the settings form (FR-OB-01).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // FIRST statement in the process, because
        // `sun.awt.windows.TranslucentWindowPainter` reads it in its STATIC
        // INITIALISER — i.e. the moment the first translucent window is shown —
        // and nothing after that can change which painter was chosen.
        //
        // The panel is a per-pixel translucent window, and on Windows that means
        // the peer owns one ARGB back buffer which is uploaded through
        // `UpdateLayeredWindow`. `TranslucentWindowPainter.createInstance` picks
        // an ACCELERATED uploader (`VIOptD3DWindowPainter`) whenever the window's
        // GraphicsConfiguration is D3D-accelerated, and that path loses repaints:
        // typing into a text field with an IME, so that the field paints composed
        // text, leaves the whole window showing a stale/blank buffer. Measured on
        // this machine, same panel, same synthetic composition event:
        //
        //     default (D3D uploader) ......... mean 212.1, 68% white  <- broken
        //     -Dsun.java2d.twp.forcesw=true ... mean 241.0, 87% white  <- correct
        //     -Dsun.java2d.d3d=false .......... also correct
        //     -Dsun.java2d.opengl=false ....... still broken
        //
        // `twp.forcesw` is the surgical one: it only makes translucent windows
        // use the BufferedImage uploader, leaving D3D on for every other window.
        // It was reproduced with a bare 20-line Swing window too, so this is the
        // platform's uploader and not anything this app paints. A full repaint
        // does NOT recover from the broken state, so the painter has to be
        // avoided rather than worked around.
        //
        // build.gradle.kts passes the same flag at JVM start for the packaged
        // .exe; setting it here as well is what covers `gradlew run`, and keeps
        // the reason attached to the code it protects.
        System.setProperty("sun.java2d.twp.forcesw", "true");

        // One line, ASCII only, that says which charsets this run actually got:
        // a garbled log is otherwise a two-minute guess. NOTE that
        // `file.encoding` must stay at the platform default here — pinning it to
        // UTF-8 is what makes Windows draw every native AWT menu label as tofu
        // boxes (see build.gradle.kts, which explains the GDI path).
        System.out.println("[aichat] encoding: file=" + System.getProperty("file.encoding")
            + " stdout=" + System.getProperty("sun.stdout.encoding")
            + " jnu=" + System.getProperty("sun.jnu.encoding")
            + " twp.forcesw=" + System.getProperty("sun.java2d.twp.forcesw"));

        // Off unless `-Daichat.uitrace=1`: counts dirty regions and invalid
        // components per second, which is what tells a repaint/layout loop apart
        // from a capture artefact. Installed before the first window exists, so
        // nothing painted early goes uncounted.
        UiTrace.install();

        // Before any component exists: the native look for everything this app
        // does not paint itself — text field caret behaviour, the scrollbars in
        // the dialogs. Swing's default is Metal, which reads as a 2005
        // application sitting next to the rest of the desktop.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Nothing here depends on which look is active; keep the default.
        }

        Config config = new Config();

        // The palette must be settled BEFORE any component exists, because the
        // panel, the dialogs and the region overlay all read it while painting.
        // It is applied through ThemeManager rather than poked into static
        // fields, so the settings page can change it later without a restart.
        ThemeManager.setMode(config.themeMode());
        ThemeManager.setFontScale(config.isFontLarge()
            ? ThemeManager.SCALE_LARGE : ThemeManager.SCALE_STANDARD);

        // FR-OB-01: a first run — nothing configured yet — gets the three-step
        // walkthrough rather than a wall of settings. A run that already has a
        // key (an upgrade, or a user coming back) is left alone.
        if (!config.hasKey() && !config.onboarded()) {
            OnboardingDialog.show(null, config,
                () -> config.onboarded(true),
                () -> chooseRegionBlocking(config));
            if (config.hasKey()) config.onboarded(true);
        }

        // The walkthrough writes the judge key; a run that escaped it without one
        // is still unconfigured, and the settings form is the only way in.
        if (!config.hasKey()) {
            SettingsDialog.show(null, config, null, null);
        }

        // The chat window and the area inside it are remembered once and kept;
        // asking every launch would be infuriating.
        if (config.captureTarget() == null) {
            chooseRegionBlocking(config);
        }

        SwingUtilities.invokeLater(() -> start(config));
    }

    /** The 「选择区域」 flow, before a panel exists to hide. */
    private static void chooseRegionBlocking(Config config) {
        RegionSelector.Selection sel = RegionSelector.select();
        if (sel.target() != null) config.captureTarget(sel.target());
    }

    private static void start(Config config) {
        ScreenGrabber grabber = new ScreenGrabber();
        AssistantPanel panel = new AssistantPanel();
        panel.setOpacity(config.panelOpacity());
        panel.setEvidenceVisible(config.evidenceVisible());
        panel.setCaptureInfo(captureLabel(config.captureTarget()));
        ChatPipeline pipeline = new ChatPipeline(config, grabber, panel);

        panel.setOnManualAnalyze(pipeline::manualAnalyze);
        panel.setOnReanalyze(pipeline::manualAnalyze);
        panel.setOnChooseRegion(pipeline::chooseRegion);
        panel.setOnTogglePause(pipeline::togglePause);
        panel.setOnToggleSession(pipeline::toggleSession);
        panel.setOnSaveContact(pipeline::saveCurrentAsContact);
        panel.setOnSaveContactWithRelation(pipeline::saveCurrentAsContact);
        panel.setOnRelationApply(pipeline::setRelationship);
        panel.setOnRelationClear(pipeline::clearRelationship);
        panel.setOnRegenerateReply(pipeline::regenerateReply);
        panel.setOnAskSubmitted(pipeline::ask);
        panel.setOnOpenKnowledge(() -> withPanelUnpinned(panel,
            () -> KnowledgeDialog.show(panel.frame(), 1, pipeline::syncRelationship)));
        panel.setOnOpenSettings(() -> withPanelUnpinned(panel, () -> {
            SettingsDialog.show(panel.frame(), config, pipeline::reconfigure,
                panel::setOpacity);
            // The 外观 page or the 关系与范围 page may have changed something the
            // panel is showing.
            panel.setEvidenceVisible(config.evidenceVisible());
            pipeline.syncRelationship();
        }));

        // The two window buttons do different things, as they do in any desktop
        // app: minimize parks the panel and leaves the assistant running, close
        // ends the run. The tray is the way back — iconifying is not an option
        // here, because an undecorated frame gets no taskbar button to restore
        // from (verified on Windows 11: nothing appears in the taskbar).
        TrayIcon tray = installTray(panel, pipeline);
        panel.setOnMinimize(() -> panel.setVisible(false));
        panel.setOnClose(() -> quit(panel, pipeline, tray));
        // A logoff, a Ctrl+C or a Task-Manager kill never reaches either of the
        // two closes above. There is no work to save on the way out, but the
        // window still has to come down: see [quit].
        Runtime.getRuntime().addShutdownHook(
            new Thread(panel::dispose, "aichat-shutdown"));

        panel.setVisible(true);
        pipeline.start();
        // 后台预热多屏拓扑：跨屏拖拽要用 Win32 的物理矩形，第一次访问要几百
        // 毫秒（JNA 初始化），放在这里就不会变成用户第一次拖窗口时的卡顿。
        Screens.warmUp();
    }

    /**
     * FR-COM-02: the panel is explicitly stepped down while a dialog of ours is
     * open, rather than relying on the dialogs happening to be shown later. An
     * always-on-top panel covers a dialog centred on it and swallows its clicks.
     */
    private static void withPanelUnpinned(AssistantPanel panel, Runnable body) {
        panel.setAlwaysOnTop(false);
        try {
            body.run();
        } finally {
            panel.setAlwaysOnTop(true);
            panel.toFront();
        }
    }

    /** `微信 · 412×386`, or null when nothing is bound yet. */
    private static String captureLabel(CaptureTarget t) {
        return t == null ? null : t.windowTitle() + " · " + t.width() + "×" + t.height();
    }

    /**
     * The one way out, shared by the window's close button and the tray's 退出.
     *
     * <p>Order matters. The panel is a per-pixel translucent, always-on-top,
     * undecorated frame: calling {@code System.exit} while it is still mapped
     * kills the process with the window on screen, and nothing repaints the
     * desktop underneath it — its pixels stay behind until something else
     * happens to cover that area. The tray icon has the same problem in the
     * notification area, where Windows only removes it if the app asks. So:
     * stop the work, take the icon out, take the window down, flush the platform
     * queue, and only then exit.
     */
    private static void quit(AssistantPanel panel, ChatPipeline pipeline, TrayIcon icon) {
        try {
            pipeline.close();
        } catch (Exception ignored) {
            // The process is going away regardless; a failed teardown here must
            // not strand the user in a window that no longer responds.
        }
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Exception ignored) {
            }
        }
        panel.dispose();
        try {
            // Drains the native event queue, so the window teardown above has
            // actually been carried out before the JVM disappears.
            Toolkit.getDefaultToolkit().sync();
        } catch (Exception ignored) {
        }
        System.exit(0);
    }

    /**
     * The tray icon, or null when the platform has no tray to put it in.
     *
     * <p>The popup is OUR OWN menu ({@link Menu}), not AWT's {@code PopupMenu},
     * for two reasons — and the second one is a bug report:
     *
     * <ol>
     *   <li>A native menu is drawn by Windows, so it was the one surface in the
     *       app that ignored the design system and read as Windows chrome
     *       sitting next to the panel. {@link Menu} says the same thing about
     *       the in-panel menus.</li>
     *   <li>Native menu labels go through GDI, and this JDK hands GDI a font
     *       family resolved against the JVM's charset: with
     *       {@code -Dfile.encoding=UTF-8} on a zh-CN Windows, 「显示面板」 was
     *       four hollow tofu boxes. That override is gone from
     *       build.gradle.kts, but a menu this app draws itself cannot regress
     *       that way whatever the JVM is started with — and it renders Chinese
     *       with the very same Java2D font stack as the rest of the UI, which
     *       has always been correct.</li>
     * </ol>
     */
    private static TrayIcon installTray(AssistantPanel panel, ChatPipeline pipeline) {
        if (!SystemTray.isSupported()) return null;
        try {
            SystemTray tray = SystemTray.getSystemTray();
            // The icon does not exist yet while its own listener is being built,
            // so 退出 reads it back through this holder rather than capturing it.
            TrayIcon[] holder = new TrayIcon[1];
            TrayIcon icon = new TrayIcon(trayIcon(), "AIChat 助手", null);
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    showTrayMenu(e, panel, pipeline, holder[0]);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    showTrayMenu(e, panel, pipeline, holder[0]);
                }
            });
            icon.addActionListener(e -> showPanel(panel));
            tray.add(icon);
            holder[0] = icon;
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The notification area's context menu, anchored where the pointer is.
     *
     * <p>Both press and release are routed here because which one carries the
     * popup trigger is platform dependent ({@link MouseEvent#isPopupTrigger}).
     * The anchor comes from the pointer rather than from the event: a
     * {@code TrayIcon} is not a {@code Component}, so its events have no
     * component-relative geometry to convert.
     */
    private static void showTrayMenu(MouseEvent e, AssistantPanel panel,
                                     ChatPipeline pipeline, TrayIcon icon) {
        if (!e.isPopupTrigger()) return;
        PointerInfo pointer = MouseInfo.getPointerInfo();
        Point at = pointer == null ? new Point(0, 0) : pointer.getLocation();
        Menu.showAt(at, List.of(
            Menu.Item.of("显示面板", () -> showPanel(panel)),
            // The Android bubble menu's 「把当前会话存为联系人」, which has no
            // bubble to live in here — the tray is the equivalent hiding place.
            Menu.Item.of("把当前会话存为联系人", () -> pipeline.saveCurrentAsContact()),
            Menu.Item.separator(),
            Menu.Item.of("退出", () -> quit(panel, pipeline, icon))));
    }

    private static void showPanel(AssistantPanel panel) {
        SwingUtilities.invokeLater(() -> {
            panel.setVisible(true);
            panel.toFront();
        });
    }

    /** The app mark at tray size, from the same vector art as the title bar. */
    private static Image trayIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            Icons.brand(16).paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return img;
    }
}
