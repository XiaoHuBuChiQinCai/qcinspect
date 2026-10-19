package com.qc.inspect.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整批判定测试：先按件判，再按件数判批。
 *
 * <p>最容易被写错的地方是 <b>ngCount（不合格行数）和 failedQty（不合格件数）混用</b>：
 * 一件工件有两个项超差，行数是 2，但它只算 1 件不合格。
 * 允收数 Ac 比的是件数，不是项数——混淆了会把整批的判定结论算反。
 */
@DisplayName("整批判定 InspectionJudge")
class InspectionJudgeTest {

    /** 外径 49.90 ~ 50.10 */
    private static final ItemSpec DIA = ItemSpec.number(new BigDecimal("49.90"), new BigDecimal("50.10"));

    private static ItemSpec number(String min, String max) {
        return ItemSpec.number(new BigDecimal(min), new BigDecimal(max));
    }

    private static JudgeTarget t(int pieceNo, String name, boolean critical, ItemSpec spec, String value) {
        return new JudgeTarget(null, null, pieceNo, name, critical, spec, List.of(value), null);
    }

    private static JudgeTarget ok(int pieceNo) {
        return t(pieceNo, "外径", false, DIA, "50.00");
    }

    @Test
    @DisplayName("空单 → 判不合格（没有数据就不能判合格）")
    void emptyIsFail() {
        InspectionOutcome o = InspectionJudge.judge(List.of(), 0);
        assertThat(o.pass()).isFalse();
        assertThat(o.reason()).contains("无法判定");
    }

    @Test
    @DisplayName("null 输入 → 判不合格，不抛异常")
    void nullIsFail() {
        InspectionOutcome o = InspectionJudge.judge(null, 0);
        assertThat(o.pass()).isFalse();
        assertThat(o.failedQty()).isZero();
    }

    @Test
    @DisplayName("单件全部合格 → 批合格")
    void singlePiecePass() {
        InspectionOutcome o = InspectionJudge.judge(List.of(ok(1)), 0);
        assertThat(o.pass()).isTrue();
        assertThat(o.passedQty()).isEqualTo(1);
        assertThat(o.failedQty()).isZero();
        assertThat(o.reason()).contains("1 件全部合格");
    }

    @Test
    @DisplayName("单件有项超差且 Ac=0 → 批不合格")
    void singlePieceFail() {
        InspectionOutcome o = InspectionJudge.judge(
                List.of(t(1, "外径", false, DIA, "50.50")), 0);
        assertThat(o.pass()).isFalse();
        assertThat(o.failedQty()).isEqualTo(1);
        assertThat(o.ngCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ac 的语义是「允许的不合格件数」：5 件里 1 件坏，Ac=1 → 允收")
    void oneFailedWithinAccept() {
        List<JudgeTarget> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(i == 3 ? t(i, "外径", false, DIA, "50.30") : ok(i));
        }
        InspectionOutcome o = InspectionJudge.judge(list, 1);

        assertThat(o.pass()).isTrue();
        assertThat(o.passedQty()).isEqualTo(4);
        assertThat(o.failedQty()).isEqualTo(1);
        assertThat(o.pieces()).hasSize(5);
        assertThat(o.reason()).contains("第 3 件").contains("未超过允收数 1 件");
    }

    @Test
    @DisplayName("同样 5 件 1 件坏，Ac=0 → 拒收")
    void oneFailedRejectAtZero() {
        List<JudgeTarget> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(i == 3 ? t(i, "外径", false, DIA, "50.30") : ok(i));
        }
        InspectionOutcome o = InspectionJudge.judge(list, 0);
        assertThat(o.pass()).isFalse();
        assertThat(o.reason()).contains("超过允收数 0 件");
    }

    @Test
    @DisplayName("5 件里 2 件坏，Ac=1 → 拒收")
    void twoFailedOverAccept() {
        List<JudgeTarget> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(i == 2 || i == 4 ? t(i, "外径", false, DIA, "50.30") : ok(i));
        }
        InspectionOutcome o = InspectionJudge.judge(list, 1);
        assertThat(o.pass()).isFalse();
        assertThat(o.failedQty()).isEqualTo(2);
        assertThat(o.reason()).contains("第 2、4 件");
    }

    @Test
    @DisplayName("允收数为负数 → 按 0 处理，不会出现「负一件不合格也算合格」")
    void negativeAcceptTreatedAsZero() {
        InspectionOutcome o = InspectionJudge.judge(
                List.of(ok(1), t(2, "外径", false, DIA, "51.00")), -5);
        assertThat(o.acceptQty()).isZero();
        assertThat(o.pass()).isFalse();
    }

    @Test
    @DisplayName("一件有 2 项超差 → 行数 2、件数 1（两个计数不能混用）")
    void ngCountIsRowLevel() {
        List<JudgeTarget> list = List.of(
                t(1, "外径", false, DIA, "50.50"),
                t(1, "长度", false, number("10", "20"), "99"));
        InspectionOutcome o = InspectionJudge.judge(list, 0);

        assertThat(o.ngCount()).isEqualTo(2);
        assertThat(o.failedQty()).isEqualTo(1);
        assertThat(o.pieces()).hasSize(1);
        // 一件两项超差，Ac=1 时仍然允收（比的是件数）
        assertThat(InspectionJudge.judge(list, 1).pass()).isTrue();
    }

    @Test
    @DisplayName("多点项里有 1 点超差 → 只算 1 行 NG，不按测点数放大")
    void multiPointCountsAsOneRow() {
        ItemSpec spec = ItemSpec.numberMulti(3, new BigDecimal("49.90"), new BigDecimal("50.10"));
        JudgeTarget target = new JudgeTarget(null, null, 1, "三点外径", false, spec,
                List.of("50.00", "50.50", "50.00"), null);

        InspectionOutcome o = InspectionJudge.judge(List.of(target), 0);
        assertThat(o.ngCount()).isEqualTo(1);
        assertThat(o.failedQty()).isEqualTo(1);
    }

    @Test
    @DisplayName("关键项 NG → 该件理由直接点出关键项，而不是罗列所有 NG 项")
    void criticalItemReason() {
        List<JudgeTarget> list = List.of(
                t(1, "外径", false, DIA, "50.50"),
                t(1, "气密性", true, ItemSpec.bool(false), "true"));
        InspectionOutcome o = InspectionJudge.judge(list, 0);

        PieceOutcome p = o.pieces().get(0);
        assertThat(p.pass()).isFalse();
        assertThat(p.criticalNgCount()).isEqualTo(1);
        assertThat(p.reason()).contains("关键项不合格");
        assertThat(o.criticalNgCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("关键项 NG 但件数在允收范围内 → 批仍合格，但理由必须点出关键项")
    void criticalWithinAcceptStillHints() {
        List<JudgeTarget> list = List.of(
                ok(1),
                t(2, "气密性", true, ItemSpec.bool(false), "true"),
                ok(3));
        InspectionOutcome o = InspectionJudge.judge(list, 3);

        assertThat(o.pass()).isTrue();
        assertThat(o.reason()).contains("关键项问题").contains("第 2 件");
    }

    @Test
    @DisplayName("无不合格件时理由不夹杂关键项提示")
    void noHintWhenAllPass() {
        InspectionOutcome o = InspectionJudge.judge(List.of(ok(1), ok(2)), 0);
        assertThat(o.pass()).isTrue();
        assertThat(o.reason()).doesNotContain("关键项问题");
    }

    @Test
    @DisplayName("件号乱序输入 → 结果按件号升序输出，理由里的件号也按顺序")
    void piecesSortedByPieceNo() {
        InspectionOutcome o = InspectionJudge.judge(
                List.of(ok(3), ok(1), ok(2)), 0);
        assertThat(o.pieces()).extracting(PieceOutcome::pieceNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("合格件数 + 不合格件数 恒等于总件数")
    void quantityBalance() {
        List<JudgeTarget> list = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            list.add(i % 3 == 0 ? t(i, "外径", false, DIA, "51.00") : ok(i));
        }
        InspectionOutcome o = InspectionJudge.judge(list, 1);
        assertThat(o.passedQty() + o.failedQty()).isEqualTo(o.pieces().size()).isEqualTo(10);
    }

    @Test
    @DisplayName("漏填实测值也算不合格件，不能因为「没填」就跳过")
    void blankValueCountsAsFail() {
        InspectionOutcome o = InspectionJudge.judge(
                List.of(ok(1), t(2, "外径", false, DIA, "")), 0);
        assertThat(o.pass()).isFalse();
        assertThat(o.failedQty()).isEqualTo(1);
    }
}
