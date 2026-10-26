package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备份健康分级。阈值与措辞都锁在这里。
 *
 * <p>这类「监控监控者的」功能有两个失败方向，都要用测试堵死：
 * 一是阈值被放宽到永不报警（页面永远绿，备份断了半年没人知道）；
 * 二是阈值收得太紧，每天凌晨固定误报一次，用不了三天就被人当背景噪音关掉。
 * 36 小时这个取值就是为了同时避开这两头——测试把边界钉死，改阈值必须同时改这里。
 */
class BackupHealthTest {

    private static BackupHealth.Verdict judge(String lastStatus, Integer hours) {
        return BackupHealth.judge(lastStatus, hours);
    }

    @Test
    @DisplayName("没有任何记录：不报警，但明确说清楚是「还没跑过」而不是「一切正常」")
    void noRecord() {
        BackupHealth.Verdict v = judge(null, null);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_NO_RECORD);
        assertThat(v.alert()).isFalse();
        assertThat(v.flags()).hasSize(1);
        assertThat(v.flags().get(0)).contains("还没有任何备份执行记录");
    }

    @Test
    @DisplayName("刚跑完：正常")
    void freshSuccess() {
        BackupHealth.Verdict v = judge("SUCCESS", 0);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_OK);
        assertThat(v.alert()).isFalse();
        assertThat(v.flags().get(0)).contains("不到 1 小时");
    }

    @Test
    @DisplayName("边界：35 小时仍判正常——每天一次的计划，凌晨看页面不该红")
    void justUnderStale() {
        assertThat(judge("SUCCESS", BackupHealth.STALE_HOURS - 1).level())
                .isEqualTo(BackupHealth.LEVEL_OK);
    }

    @Test
    @DisplayName("边界：正好 36 小时转为「已错过一次」")
    void exactlyStale() {
        BackupHealth.Verdict v = judge("SUCCESS", BackupHealth.STALE_HOURS);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_STALE);
        assertThat(v.alert()).isTrue();
    }

    @Test
    @DisplayName("边界：71 小时还是 STALE，72 小时升级为 OVERDUE")
    void overdueBoundary() {
        assertThat(judge("SUCCESS", BackupHealth.OVERDUE_HOURS - 1).level())
                .isEqualTo(BackupHealth.LEVEL_STALE);
        assertThat(judge("SUCCESS", BackupHealth.OVERDUE_HOURS).level())
                .isEqualTo(BackupHealth.LEVEL_OVERDUE);
    }

    @Test
    @DisplayName("超过两天换算成天，不说「已 168 小时」")
    void longGapUsesDays() {
        BackupHealth.Verdict v = judge("SUCCESS", 168);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_OVERDUE);
        assertThat(v.flags().get(0)).contains("7 天").doesNotContain("168 小时");
    }

    @Test
    @DisplayName("最近一次失败，但上次成功还很新：照报失败，同时点明现有备份可用")
    void failedButRecentSuccess() {
        BackupHealth.Verdict v = judge(BackupHealth.LEVEL_FAILED, 10);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_FAILED);
        assertThat(v.alert()).isTrue();
        assertThat(v.flags()).anyMatch(f -> f.contains("现有备份还能用"));
    }

    @Test
    @DisplayName("最近一次失败且从未成功过：直接说「库里没有任何可用备份」")
    void failedNeverSucceeded() {
        BackupHealth.Verdict v = judge(BackupHealth.LEVEL_FAILED, null);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_FAILED);
        assertThat(v.flags()).anyMatch(f -> f.contains("没有任何可用备份"));
    }

    @Test
    @DisplayName("最近一次失败且上次成功已超窗口：要求人工补一次")
    void failedAndStaleSuccess() {
        BackupHealth.Verdict v = judge(BackupHealth.LEVEL_FAILED, 100);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_FAILED);
        assertThat(v.flags()).anyMatch(f -> f.contains("人工补一次"));
    }

    @Test
    @DisplayName("成功记录存在却拿不到成功时间：保守判 OVERDUE，不放过")
    void successWithoutTimestampIsTreatedAsOverdue() {
        BackupHealth.Verdict v = judge("SUCCESS", null);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_OVERDUE);
        assertThat(v.alert()).isTrue();
    }

    @Test
    @DisplayName("负数小时（时钟漂移）夹到 0，不显示「-3 小时前」")
    void negativeHoursClampedToZero() {
        BackupHealth.Verdict v = judge("SUCCESS", -3);

        assertThat(v.level()).isEqualTo(BackupHealth.LEVEL_OK);
        assertThat(v.flags().get(0)).contains("不到 1 小时").doesNotContain("-");
    }

    @Test
    @DisplayName("时长说法：1 小时、47 小时、48 小时起换天")
    void humanReadableDuration() {
        assertThat(BackupHealth.human(0)).isEqualTo("不到 1 小时");
        assertThat(BackupHealth.human(1)).isEqualTo("1 小时");
        assertThat(BackupHealth.human(47)).isEqualTo("47 小时");
        assertThat(BackupHealth.human(48)).isEqualTo("2 天");
        assertThat(BackupHealth.human(73)).isEqualTo("3 天");
    }

    @Test
    @DisplayName("排序权重：故障排在正常前面")
    void rankOrdersTroubleFirst() {
        assertThat(BackupHealth.rank(BackupHealth.LEVEL_FAILED))
                .isLessThan(BackupHealth.rank(BackupHealth.LEVEL_OVERDUE));
        assertThat(BackupHealth.rank(BackupHealth.LEVEL_OVERDUE))
                .isLessThan(BackupHealth.rank(BackupHealth.LEVEL_STALE));
        assertThat(BackupHealth.rank(BackupHealth.LEVEL_STALE))
                .isLessThan(BackupHealth.rank(BackupHealth.LEVEL_NO_RECORD));
        assertThat(BackupHealth.rank(BackupHealth.LEVEL_NO_RECORD))
                .isLessThan(BackupHealth.rank(BackupHealth.LEVEL_OK));
    }
}
