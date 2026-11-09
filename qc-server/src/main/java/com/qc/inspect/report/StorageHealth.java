package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 磁盘与容量的健康分级。
 *
 * <p>这件事和备份健康是同一类：**坏掉的东西不会自己喊**。磁盘从 80% 到 100% 的过程中
 * 系统一切正常，没有任何报错，直到某个瞬间 PostgreSQL 写 WAL 失败——
 * 那时候表现是「全站保存都报 500」，而不是「磁盘满了」。等到有人把这两件事联系起来，
 * 已经过去了半天。
 *
 * <p>所以这里判两件事，而且**绝对空间优先于百分比**：
 * <ol>
 *   <li>还剩多少可用空间。50 GB 的盘用了 90% 还剩 5 GB，没事；
 *       20 GB 的盘用了 90% 只剩 2 GB，pg_dump 一次就可能顶穿</li>
 *   <li>按最近的存图速度还能撑几天。只报「剩 2 GB」人不会紧张，
 *       报「按现在的速度还能撑 3 天」才会有人今天就动手</li>
 * </ol>
 *
 * <p>为什么不把「使用率超过 90%」当故障：大盘上那是误报，小盘上又太晚。
 * 百分比只是在文案里给人一个直观感受，不参与定级。
 */
public final class StorageHealth {

    public static final String LEVEL_OK = "OK";
    /** 该安排了：空间偏紧，或按速度撑不过一周 */
    public static final String LEVEL_WARN = "WARN";
    /** 今天就处理：空间见底，或按速度撑不过两天 */
    public static final String LEVEL_CRITICAL = "CRITICAL";
    /** 读不到盘的信息，无法判断。**不能当成 OK** */
    public static final String LEVEL_UNKNOWN = "UNKNOWN";

    private static final long MEGA = 1024L * 1024;
    private static final long GIGA = 1024L * 1024 * 1024;

    /** 可用低于 3 GB：还能用，但下一次大批量拍照或一次备份就可能顶到线 */
    public static final long WARN_USABLE = 3 * GIGA;
    /** 可用低于 1 GB：PostgreSQL 写 WAL 随时可能失败，这已经不是「提醒」而是故障 */
    public static final long CRITICAL_USABLE = 1 * GIGA;

    /** 按当前速度撑不过一周，就该动手清 */
    public static final int WARN_DAYS = 7;
    /** 撑不过两天，今天不处理明天就是故障 */
    public static final int CRITICAL_DAYS = 2;

    /**
     * 每天增长不足 100 KB 视为「没在长」。不算出天数，是为了避免
     * 「按当前速度还能撑 12000 天」这种正确的废话——那种话会让人不再看这一栏。
     */
    private static final long MIN_GROWTH = 100 * 1024;

    /** 十年以上就不报天数了，同样是避免废话 */
    private static final int MAX_DAYS = 3650;

    private StorageHealth() {
    }

    /**
     * @param totalBytes       存储盘总容量，读不到传 0
     * @param usableBytes      当前可用字节数，读不到传负数
     * @param dbBytes          数据库占用
     * @param imageCount       照片张数
     * @param imageBytes       照片总字节数
     * @param recentImageBytes 最近 {@code recentDays} 天新增的照片字节数
     * @param recentDays       统计窗口天数；传 0 表示不估速度
     */
    public record Facts(long totalBytes, long usableBytes, long dbBytes,
                        long imageCount, long imageBytes,
                        long recentImageBytes, int recentDays) {
    }

    /**
     * @param level      四档取一，见常量
     * @param flags      给人看的判断，按严重度排列，可直接展示
     * @param alert      是否需要在页面上占醒目位置、并推到管理员手机
     * @param usedPercent 已用百分比，保留一位小数
     * @param dailyGrowthBytes 按最近窗口算出的日均增长；估不出来时为 null
     * @param daysLeft   按当前速度还能撑几天；估不出来时为 null
     */
    public record Verdict(String level, List<String> flags, boolean alert,
                          long usedBytes, double usedPercent,
                          Long dailyGrowthBytes, Integer daysLeft) {
    }

    public static Verdict judge(Facts f) {
        if (f.totalBytes() <= 0 || f.usableBytes() < 0) {
            return new Verdict(LEVEL_UNKNOWN,
                    List.of("读不到存储盘的容量。可能是存储目录不存在、容器没挂载，"
                            + "或者跑在只读文件系统上——这几种情况都比「空间不够」更该先查"),
                    true, 0, 0, null, null);
        }

        long usable = Math.min(f.usableBytes(), f.totalBytes());
        long used = f.totalBytes() - usable;
        double percent = Math.round(used * 1000.0 / f.totalBytes()) / 10.0;

        Long daily = dailyGrowth(f);
        Integer days = daysLeft(usable, daily);

        String usage = "可用 " + HumanSize.of(usable) + " / 共 " + HumanSize.of(f.totalBytes())
                + "（已用 " + HumanSize.percent(percent) + "%）";

        List<String> flags = new ArrayList<>(4);
        flags.add(usage);

        if (usable < CRITICAL_USABLE) {
            flags.add("可用空间不足 " + HumanSize.of(CRITICAL_USABLE)
                    + "：PostgreSQL 写日志随时可能失败，那时不是「变慢」，是整个系统保存不进去。"
                    + "先删旧照片与旧备份，再确认没有别的程序在写盘");
            addGrowth(flags, f, daily, days, "，撑不到明天了");
            addImageShare(flags, f, used);
            return new Verdict(LEVEL_CRITICAL, List.copyOf(flags), true, used, percent, daily, days);
        }

        if (days != null && days < CRITICAL_DAYS) {
            flags.add("按最近 " + f.recentDays() + " 天的存图速度，还能撑 " + days
                    + " 天——等它自己满就是故障，今天就得处理");
            addImageShare(flags, f, used);
            return new Verdict(LEVEL_CRITICAL, List.copyOf(flags), true, used, percent, daily, days);
        }

        if (usable < WARN_USABLE) {
            flags.add("可用空间不足 " + HumanSize.of(WARN_USABLE)
                    + "：现在还能用，但下一次大批量拍照或一次备份就可能顶到线");
            addGrowth(flags, f, daily, days, "");
            addImageShare(flags, f, used);
            return new Verdict(LEVEL_WARN, List.copyOf(flags), true, used, percent, daily, days);
        }

        if (days != null && days < WARN_DAYS) {
            flags.add("按最近 " + f.recentDays() + " 天的存图速度，还能撑 " + days
                    + " 天，趁还没紧张先把旧照片清一清");
            addImageShare(flags, f, used);
            return new Verdict(LEVEL_WARN, List.copyOf(flags), true, used, percent, daily, days);
        }

        addGrowth(flags, f, daily, days, "");
        return new Verdict(LEVEL_OK, List.copyOf(flags), false, used, percent, daily, days);
    }

    /** 排序权重：越小越该先看。页面上把 OK 排在 CRITICAL 前面是种冒犯 */
    public static int rank(String level) {
        return switch (level) {
            case LEVEL_CRITICAL -> 0;
            case LEVEL_WARN -> 1;
            case LEVEL_UNKNOWN -> 2;
            default -> 3;
        };
    }

    /**
     * 日增估算。只用照片增量——数据库的增长（日志表、检验明细）没有可靠的
     * 「保留多久就删」的口径，估出来反而误导人。
     */
    private static Long dailyGrowth(Facts f) {
        if (f.recentDays() <= 0 || f.recentImageBytes() <= 0) {
            return null;
        }
        long perDay = f.recentImageBytes() / f.recentDays();
        return perDay < MIN_GROWTH ? null : perDay;
    }

    private static Integer daysLeft(long usable, Long daily) {
        if (daily == null || daily <= 0) {
            return null;
        }
        long d = usable / daily;
        return d > MAX_DAYS ? null : (int) d;
    }

    private static void addGrowth(List<String> flags, Facts f, Long daily, Integer days, String tail) {
        if (daily == null) {
            return;
        }
        if (days == null) {
            flags.add("最近 " + f.recentDays() + " 天平均每天新增 " + HumanSize.of(daily) + tail);
            return;
        }
        flags.add("最近 " + f.recentDays() + " 天平均每天新增 " + HumanSize.of(daily)
                + "，按这个速度还能撑 " + days + " 天" + tail);
    }

    /**
     * 照片是不是主要占用。
     *
     * <p>只说「空间不够」人还是不知道先删什么。指出「照片占已用的 78%」，
     * 处理动作就唯一了——而这个系统里能安全删的，也确实只有旧照片和旧备份。
     */
    private static void addImageShare(List<String> flags, Facts f, long used) {
        if (used <= 0 || f.imageBytes() <= 0) {
            return;
        }
        long share = Math.round(f.imageBytes() * 100.0 / used);
        if (share < 50) {
            return;
        }
        flags.add("其中照片 " + f.imageCount() + " 张占 " + HumanSize.of(f.imageBytes())
                + "，是已用空间的 " + share + "%——清理时先从这里下手");
    }
}
