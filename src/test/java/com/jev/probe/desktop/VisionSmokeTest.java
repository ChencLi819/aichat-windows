package com.jev.probe.desktop;

import com.jev.probe.desktop.capture.ScreenGrabber;
import com.jev.probe.desktop.capture.VisionReader;
import com.jev.probe.desktop.config.CaptureTarget;
import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.model.Msg;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live smoke test for the capture path: real chat window -> vision model ->
 * messages.
 *
 * Like the Android PipelineTest, it SKIPS itself whenever it cannot do its job
 * (no display, no vision route configured, no target picked) so the suite stays
 * green on any machine and only does real work on a configured desktop.
 *
 * Run with the target taken from the app's own config:
 *   .\gradlew.bat test --tests VisionSmokeTest --console=plain -i
 */
class VisionSmokeTest {

    @Test
    void readsChatFromScreen() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "无图形环境，跳过");
        Config config = new Config();
        VisionReader reader = new VisionReader(config);
        assumeTrue(reader.isConfigured(), "未配置视觉接口，跳过");
        CaptureTarget target = config.captureTarget();
        assumeTrue(target != null, "未选定聊天区域，跳过");

        BufferedImage shot = new ScreenGrabber().grab(target);
        // Keep the exact frame that was sent, so a bad read can be told apart
        // from a bad target by looking at the picture.
        Path dump = Path.of("build", "smoke-shot.png");
        try {
            Files.createDirectories(dump.getParent());
            ImageIO.write(shot, "png", dump.toFile());
            System.out.println("=== 截图已存: " + dump.toAbsolutePath() + " ===");
        } catch (Exception e) {
            System.out.println("截图保存失败: " + e.getMessage());
        }
        VisionReader.Dialog dialog = reader.read(shot);

        List<Msg> msgs = dialog.messages();
        System.out.println("=== 会话标题: [" + dialog.title() + "] ===");
        for (Msg m : msgs) {
            System.out.println(("me".equals(m.side()) ? "我：" : "对方：") + m.text());
        }
        System.out.println("=== 共 " + msgs.size() + " 条消息 ===");
        assertFalse(msgs.isEmpty(), "视觉接口没有读出任何消息");
    }
}
