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

    // ---------------- 数据不完整 vs 指标不合格 ----------------

    private static JudgeTarget tm(int pieceNo, String name, ItemSpec spec, List<String> values) {
        return new JudgeTarget(null, null, pieceNo, name, false, spec, values, null);
    }

    @Test
    @DisplayName("允收数洗不掉漏填：Ac=2 也盖不住 2 件没测")
    void blankCannotBeAbsorbedByAcceptQty() {
        // 5 件里第 3、4 件的外径压根没录。按「不合格件数」算，2 件正好等于 Ac，
        // 会被判合格 —— 但那两件不是做坏了，是没测。记录缺失不能当不良品消化掉。
        List<JudgeTarget> list = List.of(
                ok(1), ok(2), t(3, "外径", false, DIA, ""), t(4, "外径", false, DIA, ""), ok(5));
        InspectionOutcome o = InspectionJudge.judge(list, 2);

        assertThat(o.failedQty()).isEqualTo(2);
        assertThat(o.incompleteCount()).isEqualTo(2);
        assertThat(o.pass()).as("有未录入项时 Ac 不适用").isFalse();
        assertThat(o.reason()).contains("数据不完整").contains("第 3 件").contains("未录入");
    }

    @Test
    @DisplayName("漏填和真超差混在一起，仍然整批不合格")
    void mixedIncompleteAndRealDefectFails() {
        List<JudgeTarget> list = List.of(
                ok(1), t(2, "壁厚", false, DIA, "50.80"), t(3, "外径", false, DIA, ""), ok(4));
        // 1 件超差 + 1 件漏填 = 2 件不合格，Ac=5 足以盖住，但漏填那件必须拦住
        InspectionOutcome o = InspectionJudge.judge(list, 5);

        assertThat(o.failedQty()).isEqualTo(2);
        assertThat(o.incompleteCount()).isEqualTo(1);
        assertThat(o.pass()).isFalse();
    }

    @Test
    @DisplayName("真的指标不合格仍按 Ac 允收，完整性规则不能误伤正常判定")
    void realDefectStillSubjectToAcceptQty() {
        List<JudgeTarget> list = List.of(ok(1), t(2, "外径", false, DIA, "50.50"), ok(3));
        InspectionOutcome o = InspectionJudge.judge(list, 1);

        assertThat(o.incompleteCount()).isZero();
        assertThat(o.pass()).isTrue();
        assertThat(o.reason()).contains("未超过允收数");
    }

    @Test
    @DisplayName("不是有效数字属于数据不完整，不是指标不合格")
    void nonNumericIsIncomplete() {
        InspectionOutcome o = InspectionJudge.judge(List.of(t(1, "外径", false, DIA, "abc")), 5);

        assertThat(o.pass()).isFalse();
        assertThat(o.incompleteCount()).isEqualTo(1);
        assertThat(o.reason()).contains("不是有效数字");
    }

    @Test
    @DisplayName("测点数量不符属于数据不完整")
    void pointCountMismatchIsIncomplete() {
        ItemSpec three = ItemSpec.numberMulti(3, new BigDecimal("10.0"), new BigDecimal("10.2"));
        // 标准要求 3 个测点，只录了 2 个
        InspectionOutcome o = InspectionJudge.judge(
                List.of(tm(1, "外径", three, List.of("10.1", "10.1"))), 3);

        assertThat(o.pass()).isFalse();
        assertThat(o.incompleteCount()).isEqualTo(1);
        assertThat(o.reason()).contains("测点数量不符");
    }

    @Test
    @DisplayName("文本项没填不算数据不完整：它本来就不参与判定")
    void blankTextIsNotIncomplete() {
        JudgeTarget text = new JudgeTarget(null, null, 1, "备注", false, ItemSpec.text(), List.of(), null);
        InspectionOutcome o = InspectionJudge.judge(List.of(ok(1), text), 0);

        assertThat(o.incompleteCount()).isZero();
        assertThat(o.pass()).isTrue();
    }

    @Test
    @DisplayName("数据不完整的理由最多列 5 处，但不能只给个数字让人自己翻")
    void incompleteBriefListsUpToFive() {
        List<JudgeTarget> list = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            list.add(t(i, "外径", false, DIA, ""));
        }
        InspectionOutcome o = InspectionJudge.judge(list, 10);

        assertThat(o.incompleteCount()).isEqualTo(8);
        assertThat(o.reason()).contains("第 1 件").contains("第 5 件").contains("共 8 处");
        assertThat(o.reason()).as("第 6 件以后不铺开").doesNotContain("第 6 件");
    }
}
