package com.jev.probe.desktop.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 主题回调的生命周期：控件在界面上才响应，随控件销毁一起释放。
 *
 * 设置窗每开一次都会重建整套控件，如果回调用全局 addListener 登记，每次关闭都会
 * 往全局表里留下一个永不释放的回调（并一直持有已经销毁的控件）。
 */
class ThemeBindingTest {

    @Test
    @DisplayName("组件上屏才响应主题变化，销毁后不再响应")
    void bindingFollowsComponentLifetime() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "无图形环境，跳过");
        JLabel label = new JLabel("scoped");
        AtomicInteger hits = new AtomicInteger();
        ThemeManager.bind(label, hits::incrementAndGet);

        // 还没上屏：不应响应（否则就是凭空多出来的全局回调）
        String before = ThemeManager.mode();
        String other = "dark".equals(before) ? "light" : "dark";
        setModeNow(other);
        assertEquals(0, hits.get(), "未上屏的组件不应收到主题回调");

        // 挂进窗口：开始响应
        JWindow window = new JWindow();
        SwingUtilities.invokeAndWait(() -> {
            window.getContentPane().add(label);
            window.pack();
        });
        setModeNow(before);
        assertEquals(1, hits.get(), "上屏后应收到一次主题回调");

        // 窗口销毁：回调随之释放
        SwingUtilities.invokeAndWait(window::dispose);
        setModeNow(other);
        assertEquals(1, hits.get(), "组件销毁后不应再收到主题回调");

        setModeNow(before);
    }

    /** 在 EDT 上切换主题并等它执行完：通知是 EDT 上同步派发的。 */
    private static void setModeNow(String mode) throws Exception {
        SwingUtilities.invokeAndWait(() -> ThemeManager.setMode(mode));
    }
}