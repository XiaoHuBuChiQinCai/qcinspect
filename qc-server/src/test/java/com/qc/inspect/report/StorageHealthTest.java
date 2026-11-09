package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 磁盘容量分级。
 *
 * <p>这个判定的每一条都对应一次真实事故的形状：空间见底之后系统不报错，
 * 只是某天开始所有保存都失败。所以测试重点不在「正常时是不是 OK」，
 * 而在**几个容易被放过的地方**：
 * <ul>
 *   <li>读不到盘容量时不能当成 OK（容器挂错卷就是这么表现的）</li>
 *   <li>绝对空间够、但按增长速度两天就没了，同样要报 CRITICAL</li>
 *   <li>估不出速度时不要在文案里编一个天数</li>
 * </ul>
 */
class StorageHealthTest {

    private static final long MEGA = 1024L * 1024;
    private static final long GIGA = 1024L * 1024 * 1024;

    private static StorageHealth.Facts facts(long total, long usable) {
        return new StorageHealth.Facts(total, usable, 200 * MEGA, 1000, 5 * GIGA, 0, 30);
    }

    private static StorageHealth.Verdict judge(long total, long usable) {
        return StorageHealth.judge(facts(total, usable));
    }

    @Test
    @DisplayName("50 GB 的盘剩 40 GB：正常，不打扰人")
    void healthy() {
        StorageHealth.Verdict v = judge(50 * GIGA, 40 * GIGA);

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_OK);
        assertThat(v.alert()).isFalse();
        assertThat(v.usedBytes()).isEqualTo(10 * GIGA);
        assertThat(v.usedPercent()).isEqualTo(20.0);
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("可用 40.0 GB"));
    }

    @Test
    @DisplayName("剩 2.5 GB 报警：还能用，但下一次备份就可能顶穿")
    void warnByAbsoluteSpace() {
        StorageHealth.Verdict v = judge(50 * GIGA, 2 * GIGA + 512 * MEGA);

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_WARN);
        assertThat(v.alert()).isTrue();
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("下一次大批量拍照"));
    }

    @Test
    @DisplayName("剩 800 MB 是故障级：PostgreSQL 写 WAL 会失败，不是「变慢」")
    void criticalByAbsoluteSpace() {
        StorageHealth.Verdict v = judge(50 * GIGA, 800 * MEGA);

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_CRITICAL);
        assertThat(v.alert()).isTrue();
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("保存不进去"));
    }

    /**
     * 这是「只报剩多少」最不够用的地方：10 GB 听起来很多，
     * 但每天存 2 GB 照片，五天就没了。人不紧张是因为没人把这两个数除一下。
     */
    @Test
    @DisplayName("空间还有 10 GB，但按每天 2 GB 只能撑 5 天 —— 要报 WARN")
    void warnByGrowth() {
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                100 * GIGA, 10 * GIGA, 200 * MEGA,
                50000, 40 * GIGA, 60 * GIGA, 30));

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_WARN);
        assertThat(v.dailyGrowthBytes()).isEqualTo(2 * GIGA);
        assertThat(v.daysLeft()).isEqualTo(5);
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("还能撑 5 天"));
    }

    /**
     * 可用还有 3 GB，超过 WARN 的绝对线，但两天就见底。
     * 这种情况原地等它就是等故障——所以 growth 能把等级顶到 CRITICAL。
     */
    @Test
    @DisplayName("绝对空间还没到红线，但撑不过两天 —— 照样 CRITICAL")
    void criticalByGrowthEvenIfSpaceLooksFine() {
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                100 * GIGA, 3 * GIGA, 200 * MEGA,
                50000, 40 * GIGA, 60 * GIGA, 30));

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_CRITICAL);
        assertThat(v.daysLeft()).isEqualTo(1);
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("今天就得处理"));
    }

    /**
     * 容器把卷挂错、目录不存在、只读文件系统，表现都是「读不到容量」。
     * 这时候显示「正常」是最坏的答案——它让人关掉这个页面。
     */
    @Test
    @DisplayName("读不到容量判 UNKNOWN，不能当 OK 放过")
    void unknownWhenUnreadable() {
        StorageHealth.Verdict zero = judge(0, 0);
        assertThat(zero.level()).isEqualTo(StorageHealth.LEVEL_UNKNOWN);
        assertThat(zero.alert()).as("UNKNOWN 也要出现在醒目位置").isTrue();

        StorageHealth.Verdict negative = judge(50 * GIGA, -1);
        assertThat(negative.level()).isEqualTo(StorageHealth.LEVEL_UNKNOWN);
    }

    @Test
    @DisplayName("估不出速度时不要编天数")
    void noDaysWhenGrowthUnknown() {
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                50 * GIGA, 40 * GIGA, 200 * MEGA, 1000, 5 * GIGA, 0, 30));

        assertThat(v.dailyGrowthBytes()).isNull();
        assertThat(v.daysLeft()).isNull();
        assertThat(v.flags()).noneSatisfy(f -> assertThat(f).contains("还能撑"));

        // 窗口传 0 也表示不估
        StorageHealth.Verdict noWindow = StorageHealth.judge(new StorageHealth.Facts(
                50 * GIGA, 40 * GIGA, 200 * MEGA, 1000, 5 * GIGA, 10 * GIGA, 0));
        assertThat(noWindow.dailyGrowthBytes()).isNull();
    }

    /**
     * 每天几 KB 的增量算出来的天数是四位数，「还能撑 12000 天」这种正确的废话
     * 会让人从此不再看这一栏——而这一栏存在的意义就是让人看一眼。
     */
    @Test
    @DisplayName("增长小到可以忽略时，和「没在长」同样处理")
    void ignoreTinyGrowth() {
        // 30 天累计 1.5 MB ≈ 每天 50 KB，低于 100 KB 的门槛
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                50 * GIGA, 40 * GIGA, 200 * MEGA, 30, 1 * MEGA, 1536 * 1024, 30));

        assertThat(v.dailyGrowthBytes()).isNull();
        assertThat(v.daysLeft()).isNull();
        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_OK);
    }

    @Test
    @DisplayName("增长速度慢到十年以上才满，也不报天数")
    void ignoreFarFuture() {
        // 每天 1 MB，可用 40 GB → 40960 天
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                50 * GIGA, 40 * GIGA, 200 * MEGA, 1000, 5 * GIGA, 30 * MEGA, 30));

        assertThat(v.dailyGrowthBytes()).isEqualTo(1 * MEGA);
        assertThat(v.daysLeft()).isNull();
    }

    @Test
    @DisplayName("照片是主要占用时点名，别让人猜该删什么")
    void pointAtImages() {
        // 已用 10 GB，其中照片 8 GB
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                20 * GIGA, 10 * GIGA, 500 * MEGA, 20000, 8 * GIGA, 0, 30));

        // 可用 10 GB ≥ 3 GB，正常态
        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_OK);
        // 正常态不喊「先删照片」，那属于制造噪音
        assertThat(v.flags()).noneSatisfy(f -> assertThat(f).contains("先从这里下手"));

        // 一旦进入告警态，这条才出现
        StorageHealth.Verdict warn = StorageHealth.judge(new StorageHealth.Facts(
                20 * GIGA, 2 * GIGA, 500 * MEGA, 20000, 15 * GIGA, 0, 30));
        assertThat(warn.flags()).anySatisfy(f -> assertThat(f).contains("先从这里下手"));
        assertThat(warn.flags()).anySatisfy(f -> assertThat(f).contains("83%"));
    }

    @Test
    @DisplayName("照片总量比整盘已用还多时，说清是口径不一致，不要算出「占 1548%」")
    void imageShareOverHundred() {
        // 20 GB 的盘只剩 2 GB，照片记录却写了 30 GB —— 线上实测时真撞到过这个形状
        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                20 * GIGA, 2 * GIGA, 500 * MEGA, 20000, 30 * GIGA, 0, 30));

        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_WARN);
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("比这台盘已用的还多"));
        assertThat(v.flags()).anySatisfy(f -> assertThat(f).contains("先核对口径"));
        // 这时候「先删照片」是错的建议：盘上根本没那么多照片
        assertThat(v.flags()).noneSatisfy(f -> assertThat(f).contains("先从这里下手"));
        assertThat(v.flags()).noneSatisfy(f -> assertThat(f).contains("是已用空间的"));
    }

    @Test
    @DisplayName("可用比总量还大这种脏数据，按满盘理解而不是算出负数已用")
    void clampWeirdNumbers() {
        StorageHealth.Verdict v = judge(10 * GIGA, 20 * GIGA);

        assertThat(v.usedBytes()).isZero();
        assertThat(v.level()).isEqualTo(StorageHealth.LEVEL_OK);
    }

    @Test
    @DisplayName("等级排序：故障排最前，正常排最后")
    void ranking() {
        assertThat(StorageHealth.rank(StorageHealth.LEVEL_CRITICAL)).isLessThan(
                StorageHealth.rank(StorageHealth.LEVEL_WARN));
        assertThat(StorageHealth.rank(StorageHealth.LEVEL_WARN)).isLessThan(
                StorageHealth.rank(StorageHealth.LEVEL_UNKNOWN));
        assertThat(StorageHealth.rank(StorageHealth.LEVEL_UNKNOWN)).isLessThan(
                StorageHealth.rank(StorageHealth.LEVEL_OK));
    }

    @Test
    @DisplayName("体积换算：1 GB 以上换单位，别报成 1024 MB")
    void humanSize() {
        assertThat(HumanSize.of(0)).isEqualTo("0 B");
        assertThat(HumanSize.of(1023)).isEqualTo("1023 B");
        assertThat(HumanSize.of(14812)).isEqualTo("14 KB");
        assertThat(HumanSize.of(1048575)).as("差一个字节也该换说法").isEqualTo("1.0 MB");
        assertThat(HumanSize.of(3072L * 1024)).isEqualTo("3.0 MB");
        assertThat(HumanSize.of(GIGA)).isEqualTo("1.0 GB");
        assertThat(HumanSize.of(50 * GIGA)).isEqualTo("50.0 GB");
        assertThat(HumanSize.of(-1)).isEqualTo("—");

        assertThat(HumanSize.percent(38.0)).isEqualTo("38");
        assertThat(HumanSize.percent(38.5)).isEqualTo("38.5");
    }
}
