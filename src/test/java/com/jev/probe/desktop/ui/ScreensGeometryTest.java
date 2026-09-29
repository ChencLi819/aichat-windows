package com.jev.probe.desktop.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多屏几何的单元测试：换算规则与尺寸适配。
 *
 * <p>用例里的数字全部来自本机实测（双屏混合 DPI：主屏 125% 1920×1080、
 * 副屏 150% 2560×1440 且在主屏左侧），即 AWT 报主屏 0,0 1536×864、副屏
 * -2560,0 1707×960。实测映射表（物理 → 逻辑）：-2560→-2560、-1280→-1707、
 * -520→-1200、0→0、1000→800。
 */
class ScreensGeometryTest {

    /** 主屏：逻辑 0,0 1536×864，scale 1.25，物理 0,0 1920×1080，工作区扣掉 60px 任务栏。 */
    private static final Screens.Monitor PRIMARY = new Screens.Monitor(
        new Rectangle(0, 0, 1536, 864),
        new Rectangle(0, 0, 1920, 1080),
        new Rectangle(0, 0, 1920, 1020),
        1.25, true);

    /** 副屏（左）：逻辑 -2560,0 1707×960，scale 1.5，物理 -2560,0 2560×1440。 */
    private static final Screens.Monitor SECONDARY = new Screens.Monitor(
        new Rectangle(-2560, 0, 1707, 960),
        new Rectangle(-2560, 0, 2560, 1440),
        new Rectangle(-2560, 0, 2560, 1368),
        1.5, false);

    @Test
    @DisplayName("物理→逻辑：与实测映射表一致（跨 seam 不跳）")
    void mapsPhysicalToLogicalLikeWindowsDoes() {
        assertEquals(-2560, SECONDARY.toLogical(new Rectangle(-2560, 100, 400, 300)).x);
        assertEquals(-1707, SECONDARY.toLogical(new Rectangle(-1280, 100, 400, 300)).x);
        assertEquals(-1200, SECONDARY.toLogical(new Rectangle(-520, 100, 400, 300)).x);
        assertEquals(0, PRIMARY.toLogical(new Rectangle(0, 100, 400, 300)).x);
        assertEquals(800, PRIMARY.toLogical(new Rectangle(1000, 100, 400, 300)).x);
    }

    @Test
    @DisplayName("逻辑→物理→逻辑 往返无损（窗口 setBounds 的结果与请求一致）")
    void roundTripsThroughPhysicalSpace() {
        for (Screens.Monitor m : new Screens.Monitor[] {PRIMARY, SECONDARY}) {
            Rectangle logical = new Rectangle(m.logical().x + 120, m.logical().y + 80, 460, 720);
            Rectangle back = m.toLogical(m.toPhysical(logical));
            assertEquals(logical, back, "屏幕 " + m.logical() + " 往返后应保持不变");
        }
    }

    @Test
    @DisplayName("适配：放得下时尺寸不变（偏差 0%，满足 ≤15%）")
    void keepsSizeWhenItFits() {
        Rectangle want = new Rectangle(0, 0, 460, 720);
        Rectangle fitted = Screens.fitInto(want, PRIMARY, 360, 470);
        assertEquals(460, fitted.width);
        assertEquals(720, fitted.height);
        assertEquals(0, Math.abs(fitted.width - want.width) / (double) want.width, 1e-9);
    }

    @Test
    @DisplayName("适配：比目标屏还大时只钳越界的那一边，另一边保持用户拉出来的尺寸")
    void clampsOnlyTheDimensionThatOverflows() {
        // 2010×720 的窗口放在 1536×816 的工作区里：只应被钳到 1536 宽，高度不动
        Rectangle fitted = Screens.fitInto(new Rectangle(0, 0, 2010, 720), PRIMARY, 360, 470);
        assertEquals(1536, fitted.width, "越界的宽度钳到工作区宽度");
        assertEquals(720, fitted.height, "没动过的高度必须保持不变");
        assertTrue(fitted.width <= PRIMARY.workLogical().width);
        assertTrue(fitted.height <= PRIMARY.workLogical().height);

        // 高于屏幕时同理：只钳高度
        Rectangle tall = Screens.fitInto(new Rectangle(0, 0, 460, 1200), PRIMARY, 360, 470);
        assertEquals(460, tall.width);
        assertEquals(816, tall.height);

        // 屏幕比最小尺寸还小时以“放得下”优先
        Screens.Monitor tiny = new Screens.Monitor(
            new Rectangle(0, 0, 300, 260), new Rectangle(0, 0, 375, 325),
            new Rectangle(0, 0, 375, 325), 1.25, true);
        Rectangle onTiny = Screens.fitInto(new Rectangle(0, 0, 460, 720), tiny, 360, 470);
        assertTrue(onTiny.width <= 300 && onTiny.height <= 260, "小屏上也必须放得下");
    }

    @Test
    @DisplayName("钳位：窗口被拖到屏幕外时收回到工作区内")
    void keepsWindowInsideTheWorkArea() {
        Rectangle off = new Rectangle(1400, 800, 460, 720);
        Rectangle placed = Screens.keepInside(off, PRIMARY);
        Rectangle work = PRIMARY.workLogical();
        assertTrue(placed.x >= work.x && placed.y >= work.y);
        assertTrue(placed.x + placed.width <= work.x + work.width);
        assertTrue(placed.y + placed.height <= work.y + work.height);
        assertEquals(off.width, placed.width);
        assertEquals(off.height, placed.height);
    }
}