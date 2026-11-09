package com.qc.inspect.report;

/**
 * 人类可读的体积。
 *
 * <p>为什么单独拎出来：备份大小、磁盘容量、照片占用三处都要显示体积，
 * 各写一份迟早会出现「备份说 1.2 GB、磁盘说 1224 MB」，看的人得自己换算。
 *
 * <p>几处刻意的取舍：
 * <ul>
 *   <li>小于 1 KB 报字节数。空库备份只有十几 KB，一律按 MB 显示会变成「0.0 MB」——
 *       页面上看着像「根本没备份」，实际是有的</li>
 *   <li>先按 KB 取整再判量级。不然 1048575 字节会报成「1024 KB」，
 *       差一个字节却换了个更长的说法</li>
 *   <li>MB 起保留一位小数。1200 MB 和 300 MB 差着四倍，取整之后都是「1 GB」和「0 GB」</li>
 * </ul>
 */
public final class HumanSize {

    private static final long KILO = 1024L;
    private static final long MEGA = 1024L * 1024;
    private static final long GIGA = 1024L * 1024 * 1024;

    private HumanSize() {
    }

    public static String of(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < KILO) {
            return bytes + " B";
        }
        long kb = Math.round(bytes / (double) KILO);
        if (kb < KILO) {
            return kb + " KB";
        }
        if (bytes < GIGA) {
            // 放大 10 倍再取整 = 四舍五入到 1 位小数
            return (Math.round(bytes / (MEGA / 10.0)) / 10.0) + " MB";
        }
        return (Math.round(bytes / (GIGA / 10.0)) / 10.0) + " GB";
    }

    /** 百分比去掉末尾的 .0：页面上「已用 38%」比「已用 38.0%」干净 */
    public static String percent(double value) {
        if (value != value || value < 0) {
            return "—";
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
