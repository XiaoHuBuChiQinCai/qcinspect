package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待检批次分级。阈值与措辞都锁在这里——这类「提醒」功能最容易失控：
 * 阈值被悄悄放宽到永远不触发，页面一片正常，实际再也不会提醒任何人。
 */
class PendingBatchInsightTest {

    private static PendingBatchInsight.Verdict judge(int idleDays, int threshold, int voidLots,
                                                     int shipments, int shippedQty) {
        return PendingBatchInsight.judge(idleDays, threshold, voidLots, shipments, shippedQty);
    }

    @Test
    @DisplayName("已出货却没有检验结论：最高级别，哪怕今天刚建批")
    void shippedWithoutInspection() {
        PendingBatchInsight.Verdict v = judge(0, 2, 0, 1, 50);

        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_SHIPPED);
        assertThat(v.urgent()).isTrue();
        // 数字要具体：光说「已出货未检」，主管还得自己点进去数
        assertThat(v.flags()).anyMatch(f -> f.contains("1 单") && f.contains("50 件"));
    }

    @Test
    @DisplayName("已出货 + 超期：两条都要说，不能只报一条")
    void shippedAndOverdue() {
        PendingBatchInsight.Verdict v = judge(7, 2, 0, 2, 120);

        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_SHIPPED);
        assertThat(v.flags()).hasSize(2);
        assertThat(v.flags().get(0)).contains("已出货");
        assertThat(v.flags().get(1)).contains("7 天");
    }

    @Test
    @DisplayName("停留满阈值判超期")
    void overdueAtThreshold() {
        assertThat(judge(2, 2, 0, 0, 0).level()).isEqualTo(PendingBatchInsight.LEVEL_OVERDUE);
    }

    @Test
    @DisplayName("差一天不算超期：跨一天可能是排产")
    void dueButNotOverdue() {
        PendingBatchInsight.Verdict v = judge(1, 2, 0, 0, 0);

        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_DUE);
        assertThat(v.urgent()).isTrue();
    }

    @Test
    @DisplayName("当天建批不报警：下午到货、明天再检是正常节奏")
    void sameDayIsFine() {
        PendingBatchInsight.Verdict v = judge(0, 2, 0, 0, 0);

        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_OK);
        assertThat(v.urgent()).isFalse();
        assertThat(v.flags()).anyMatch(f -> f.contains("正常"));
    }

    @Test
    @DisplayName("作废过检验单要单独点名：批次看着在检，其实一张有效单都没有")
    void voidedLotsAreCalledOut() {
        PendingBatchInsight.Verdict v = judge(0, 2, 2, 0, 0);

        assertThat(v.flags()).anyMatch(f -> f.contains("2 张") && f.contains("作废"));
        // 当天发现的作废不算紧急，但提示必须出现，否则这一条就没有出口
        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_OK);
    }

    @Test
    @DisplayName("阈值传 0 或负数按 1 天处理：等于关掉判断会让人以为功能坏了")
    void thresholdFloor() {
        assertThat(judge(1, 0, 0, 0, 0).level()).isEqualTo(PendingBatchInsight.LEVEL_OVERDUE);
        assertThat(judge(1, -5, 0, 0, 0).level()).isEqualTo(PendingBatchInsight.LEVEL_OVERDUE);
    }

    @Test
    @DisplayName("排序权重：已出货 < 超期 < 即将超期 < 正常")
    void rankOrder() {
        assertThat(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_SHIPPED))
                .isLessThan(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_OVERDUE));
        assertThat(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_OVERDUE))
                .isLessThan(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_DUE));
        assertThat(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_DUE))
                .isLessThan(PendingBatchInsight.rank(PendingBatchInsight.LEVEL_OK));
    }

    @Test
    @DisplayName("红冲的出货单不算已出货：作废的单没真发货，不能当证据")
    void voidedShipmentIsNotEvidence() {
        // SQL 只把 status = 'NORMAL' 的单算进 shipments，所以这里传 0 就代表全部红冲掉了
        PendingBatchInsight.Verdict v = judge(0, 2, 0, 0, 0);

        assertThat(v.level()).isEqualTo(PendingBatchInsight.LEVEL_OK);
        assertThat(v.flags()).noneMatch(f -> f.contains("已出货"));
    }
}
