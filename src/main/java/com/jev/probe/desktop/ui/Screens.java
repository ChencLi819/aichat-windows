package com.jev.probe.desktop.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.ptr.IntByReference;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * 多显示器几何：把 Win32 的物理像素与 AWT 的逻辑坐标互相换算，并做屏幕适配。
 *
 * <p>为什么必须有这一层。本机实测（双屏、混合 DPI，主屏 125% 1920×1080、副屏
 * 150% 2560×1440，副屏在主屏左侧）：
 *
 * <pre>
 *   AWT 报告的主屏   : 0,0 1536×864   scale 1.25   （= 物理 / 1.25）
 *   AWT 报告的副屏   : -2560,0 1707×960 scale 1.5  （宽 = 物理 / 1.5，但原点用的是物理值）
 * </pre>
 *
 * 也就是说 AWT 的逻辑空间在 seam 处是<b>断裂</b>的：副屏的逻辑区间是
 * [-2560, -853]，主屏是 [0, 1536]，中间 [-853, 0] 落在“空洞”里，任何落在这段
 * 的逻辑坐标会被 Windows 随手映射到某一侧（实测：-400 → 物理 +680，-853 → 物理 0），
 * 于是窗口在跨屏拖拽时会突然跳开。
 *
 * <p>实测出的单屏映射规则是线性的（logical→physical）：
 *
 * <pre>
 *   physical = (logical - logicalOrigin) * scale + physicalOrigin
 * </pre>
 *
 * 反过来即
 *
 * <pre>
 *   logical  = logicalOrigin + (physical - physicalOrigin) / scale
 * </pre>
 *
 * <p>因此本类的作用就是：物理矩形（连续、无 seam）→ 目标屏的 (logicalOrigin,
 * physicalOrigin, scale) → 正确的逻辑矩形。这样 {@code setBounds} 的结果与请求的
 * 物理位置一致，拖拽在跨屏时保持平滑。
 *
 * <p>物理侧的 (physicalOrigin, workArea) 来自 Win32（{@code GetMonitorInfo}），
 * 逻辑侧的 (bounds, scale) 来自 AWT 的 {@code GraphicsConfiguration}，两者按
 * “物理尺寸 == 逻辑尺寸 × scale” 匹配（主屏再用 (0,0) 原点确认）。拿不到 Win32 时
 * 全部退化为主屏规则，调用方需自行降级（见 {@link #supported()}）。
 */
public final class Screens {

    /** 一台显示器的几何：逻辑 bounds / 物理 bounds / 物理工作区 / DPI 缩放。 */
    public record Monitor(
        Rectangle logical,
        Rectangle physical,
        Rectangle workPhysical,
        double scale,
        boolean primary
    ) {

        /** 逻辑坐标下的工作区（去掉任务栏等）。 */
        public Rectangle workLogical() {
            Point w = fromPhysicalCorner(workPhysical.x, workPhysical.y);
            return new Rectangle(w.x, w.y,
                Math.max(1, (int) Math.round(workPhysical.width / scale)),
                Math.max(1, (int) Math.round(workPhysical.height / scale)));
        }

        /** 物理矩形 → 本屏的逻辑矩形（AWT 的 setBounds 用这个坐标系）。 */
        public Rectangle toLogical(Rectangle phys) {
            Point tl = fromPhysicalCorner(phys.x, phys.y);
            return new Rectangle(tl.x, tl.y,
                Math.max(1, (int) Math.round(phys.width / scale)),
                Math.max(1, (int) Math.round(phys.height / scale)));
        }

        /** 逻辑矩形 → 本屏的物理矩形。 */
        public Rectangle toPhysical(Rectangle log) {
            return new Rectangle(
                physical.x + (int) Math.round((log.x - logical.x) * scale),
                physical.y + (int) Math.round((log.y - logical.y) * scale),
                Math.max(1, (int) Math.round(log.width * scale)),
                Math.max(1, (int) Math.round(log.height * scale)));
        }

        private Point fromPhysicalCorner(int px, int py) {
            return new Point(
                logical.x + (int) Math.round((px - physical.x) / scale),
                logical.y + (int) Math.round((py - physical.y) / scale));
        }
    }

    /**
     * 只用到的四个 Win32 调用 + 一个 MONITORINFO，自己声明。
     *
     * <p>刻意不用 `com.sun.jna.platform.win32.User32`：那个接口把几百个方法都映射了
     * 一遍，实测首次访问 `User32.INSTANCE` 要 265ms，而它落在窗口显示的关键路径上
     * （面板的初始摆放）。这里的小接口把这次初始化压到几毫秒，且和 `Win32Windows`
     * 里已存在的同类声明风格一致。
     */
    private interface U32 extends StdCallLibrary {
        U32 INSTANCE = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean EnumDisplayMonitors(Pointer hdc, Pointer clip, MonitorProc proc, Pointer data);

        boolean GetMonitorInfo(Pointer hmonitor, MONINFO info);

        int MonitorFromPoint(POINT pt, int flags);

        boolean GetWindowRect(Pointer hwnd, WinDef.RECT rect);
    }

    /** `MONITORENUMPROC`：返回 0 停止枚举，非 0 继续。 */
    interface MonitorProc extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer hmonitor, Pointer hdc, Pointer clipRect, Pointer data);
    }

    /** Win32 的 POINT（物理像素）。 */
    public static class POINT extends Structure {
        public static final List<String> FIELDS = List.of("x", "y");
        public int x;
        public int y;

        @Override
        protected List<String> getFieldOrder() {
            return FIELDS;
        }
    }

    /** Win32 的 MONITORINFO：cbSize 必须由调用方填成结构体大小。 */
    public static class MONINFO extends Structure {
        public int cbSize;
        public WinDef.RECT rcMonitor = new WinDef.RECT();
        public WinDef.RECT rcWork = new WinDef.RECT();
        public int dwFlags;

        public MONINFO() {
            cbSize = size();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cbSize", "rcMonitor", "rcWork", "dwFlags");
        }
    }

    private static final int MONITORINFOF_PRIMARY = 1;

    /** 拓扑缓存时长：拖拽每秒问几十次，而显示器热插拔是低频事件。 */
    private static final long CACHE_MS = 1500;

    private static List<Monitor> cache = List.of();
    private static long cacheAt;

    private Screens() {
    }

    /** 有没有 Win32 显示器信息可用；false 时调用方走纯 AWT 的旧路径。 */
    public static boolean supported() {
        try {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前所有显示器（物理来自 Win32，逻辑来自 AWT）。 */
    public static synchronized List<Monitor> all() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cacheAt < CACHE_MS) return cache;
        List<Monitor> fresh = probe();
        if (!fresh.isEmpty()) {
            cache = fresh;
            cacheAt = now;
        }
        return cache.isEmpty() ? fallback() : cache;
    }

    /** 窗口所在的那台显示器（按窗口与各屏的重叠面积，等价于 Win32 的 NEAREST 语义）。 */
    public static Monitor of(Window window) {
        if (window != null) {
            Rectangle phys = physicalOf(window);
            if (phys != null) return atRect(phys);
            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            if (gc != null) {
                Monitor m = byLogical(gc.getBounds());
                if (m != null) return m;
            }
        }
        return primary();
    }

    /** 包含（或最接近）该物理点的显示器。 */
    public static Monitor atPoint(Point physical) {
        Monitor best = null;
        long bestD = Long.MAX_VALUE;
        for (Monitor m : all()) {
            Rectangle r = m.physical();
            if (r.contains(physical)) return m;
            long dx = Math.max(Math.max(r.x - physical.x, 0), physical.x - (r.x + r.width));
            long dy = Math.max(Math.max(r.y - physical.y, 0), physical.y - (r.y + r.height));
            long d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best == null ? primary() : best;
    }

    /** 物理矩形（通常是一个窗口）落在哪台显示器上：按与各屏的交集面积择优。 */
    public static Monitor atRect(Rectangle physical) {
        Monitor best = null;
        long bestArea = -1;
        for (Monitor m : all()) {
            Rectangle r = m.physical();
            Rectangle inter = r.intersection(physical);
            long area = inter.isEmpty() ? 0 : (long) inter.width * inter.height;
            if (area > bestArea) {
                bestArea = area;
                best = m;
            }
        }
        return best == null ? primary() : best;
    }

    public static Monitor primary() {
        for (Monitor m : all()) {
            if (m.primary()) return m;
        }
        return all().get(0);
    }

    /**
     * 把逻辑矩形适配进目标屏：放得下就原样返回（尺寸不变＝偏差 0），超出就按
     * 方向钳到工作区内。
     *
     * <p>为什么按方向钳位而不是等比缩小：等比会把用户没动过的那一边也一起改掉
     * ——把窗口左边框往左拖到超宽之后，高度会跟着少掉两成，看起来像窗口自己在
     * 缩水。按方向钳位只限制真的越界的那一边，另一边的尺寸完全保持用户拉出来
     * 的样子。小于最小尺寸时以“放得下”优先（屏幕比最小尺寸还小属于极端情况）。
     *
     * <p>DPI 变化本身不需要改逻辑尺寸：AWT 会按目标屏的 scale 渲染，观感尺寸
     * 不变；这里修的只是“比目标屏还大”这种真正的越界。
     */
    public static Rectangle fitInto(Rectangle logical, Monitor target, int minW, int minH) {
        Dimension size = fitSize(logical.width, logical.height, target.workLogical(), minW, minH);
        return new Rectangle(logical.x, logical.y, size.width, size.height);
    }

    /**
     * 「不小于最小尺寸、也不超过工作区」的尺寸规则——初始摆放与拖拽后的适配共用
     * 这一份实现（{@link #fitInto} 只是把它套到某台屏上）。
     */
    public static Dimension fitSize(int width, int height, Rectangle work, int minW, int minH) {
        int w = Math.min(Math.max(minW, width), Math.max(1, work.width));
        int h = Math.min(Math.max(minH, height), Math.max(1, work.height));
        return new Dimension(Math.max(1, w), Math.max(1, h));
    }

    /** 把矩形收进目标屏的工作区：装得下就整体移进去，装不下就保证左上角可见。 */
    public static Rectangle keepInside(Rectangle logical, Monitor target) {
        Rectangle work = target.workLogical();
        int x = logical.x;
        int y = logical.y;
        int slackX = Math.max(0, work.width - logical.width);
        int slackY = Math.max(0, work.height - logical.height);
        x = Math.max(work.x, Math.min(work.x + slackX, x));
        y = Math.max(work.y, Math.min(work.y + slackY, y));
        return new Rectangle(x, y, logical.width, logical.height);
    }

    /** 窗口的物理矩形（Win32 GetWindowRect），拿不到返回 null。 */
    public static Rectangle physicalOf(Window window) {
        if (!supported() || window == null) return null;
        try {
            WinDef.HWND hwnd = new WinDef.HWND(Native.getWindowPointer(window));
            WinDef.RECT r = new WinDef.RECT();
            if (!U32.INSTANCE.GetWindowRect(hwnd.getPointer(), r)) return null;
            return new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 后台预热拓扑。启动阶段刻意不碰 Win32（面板的初始摆放只用 AWT，见
     * {@code AssistantPanel.placeTopRight}），但第一次拖拽会需要这份数据；
     * 在这里提前算好，用户真正拖窗口时就不会有一次几百毫秒的卡顿。
     */
    public static void warmUp() {
        if (!supported()) return;
        Thread t = new Thread(Screens::all, "screens-warmup");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------- 探测与匹配

    /** Win32 侧的一台显示器：物理显示区 + 物理工作区 + 是否主屏。 */
    private record Raw(Rectangle monitor, Rectangle work, boolean primary) {
    }

    private static List<Monitor> probe() {
        List<Raw> raw = new ArrayList<>();
        if (!supported()) return List.of();
        try {
            U32.INSTANCE.EnumDisplayMonitors(null, null, (hmon, hdc, clip, data) -> {
                MONINFO mi = new MONINFO();
                if (U32.INSTANCE.GetMonitorInfo(hmon, mi)) {
                    raw.add(new Raw(rectOf(mi.rcMonitor), rectOf(mi.rcWork),
                        (mi.dwFlags & MONITORINFOF_PRIMARY) != 0));
                }
                return 1;
            }, null);
        } catch (Throwable t) {
            return List.of();
        }
        List<Monitor> out = new ArrayList<>();
        Rectangle primaryPhys = null;
        for (Raw r : raw) {
            if (r.primary()) primaryPhys = r.monitor();
        }
        GraphicsConfiguration primaryGc = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDefaultConfiguration();
        for (Raw r : raw) {
            GraphicsConfiguration gc = match(r.monitor(), r.primary(), primaryPhys, primaryGc);
            if (gc == null) continue;
            double scale = gc.getDefaultTransform().getScaleX();
            if (scale <= 0) scale = 1.0;
            out.add(new Monitor(gc.getBounds(), r.monitor(), r.work(), scale, r.primary()));
        }
        return out;
    }

    private static Rectangle rectOf(WinDef.RECT r) {
        return new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top);
    }

    /**
     * 给一台物理显示器找对应的 AWT GraphicsConfiguration。
     *
     * 判据是尺寸：物理尺寸 == 逻辑尺寸 × scale（实测主屏 1536×864×1.25 = 1920×1080，
     * 副屏 1707×960×1.5 ≈ 2560×1440）。多台同尺寸的屏幕无法区分时，再用“相对主屏
     * 的方向”兜底；仍无法确定就返回主屏配置（至少坐标规则和主屏一致）。
     */
    private static GraphicsConfiguration match(
        Rectangle phys, boolean primary, Rectangle primaryPhys, GraphicsConfiguration primaryGc
    ) {
        List<GraphicsConfiguration> candidates = new ArrayList<>();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            double s = gc.getDefaultTransform().getScaleX();
            if (s <= 0) continue;
            Rectangle log = gc.getBounds();
            if (Math.abs(Math.round(log.width * s) - phys.width) > 2) continue;
            if (Math.abs(Math.round(log.height * s) - phys.height) > 2) continue;
            candidates.add(gc);
        }
        if (candidates.isEmpty()) return primary ? primaryGc : null;
        if (primary) {
            for (GraphicsConfiguration gc : candidates) {
                if (gc.getBounds().x == 0 && gc.getBounds().y == 0) return gc;
            }
        }
        if (candidates.size() > 1 && primaryPhys != null) {
            int physDir = Integer.signum(phys.x - primaryPhys.x);
            for (GraphicsConfiguration gc : candidates) {
                if (Integer.signum(gc.getBounds().x) == physDir && physDir != 0) return gc;
            }
        }
        return candidates.get(0);
    }

    private static Monitor byLogical(Rectangle logical) {
        for (Monitor m : all()) {
            if (m.logical().contains(logical.x + Math.max(1, logical.width / 2),
                    logical.y + Math.max(1, logical.height / 2))) {
                return m;
            }
        }
        return null;
    }

    /** 只剩 AWT 时（非 Windows / 无 JNA）：主屏按 scale 换算，其余交给 AWT 自己。 */
    private static List<Monitor> fallback() {
        List<Monitor> out = new ArrayList<>();
        GraphicsDevice def = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            double s = gc.getDefaultTransform().getScaleX();
            if (s <= 0) s = 1.0;
            Rectangle log = gc.getBounds();
            Rectangle phys = new Rectangle(
                (int) Math.round(log.x * s), (int) Math.round(log.y * s),
                (int) Math.round(log.width * s), (int) Math.round(log.height * s));
            out.add(new Monitor(log, phys, phys, s, gd == def));
        }
        return out;
    }
}