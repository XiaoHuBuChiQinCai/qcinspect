package com.qc.inspect.report;

import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准改版差异。
 *
 * <p>这个类最容易错的不是「漏报一条变更」，而是<strong>把方向判反</strong>：
 * 上限调高明明是放宽，判成收紧会让人以为问题出在别处。
 * 所以每个字段都单独立一个用例锁方向。
 */
class StandardDiffTest {

    private static final long ITEM_INNER = 1L;
    private static final long ITEM_BURR = 2L;
    private static final long ITEM_SCRATCH = 3L;

    // ==================== 数值限值 ====================

    @Test
    @DisplayName("上限调高是放宽：能测的值更多了")
    void higherMaxIsLooser() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.10")), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.RELAXED);
        assertThat(r.verdictText()).isEqualTo("整体放宽");
        assertThat(r.changes()).hasSize(1);
        assertThat(r.changes().get(0).direction()).isEqualTo(StandardDiff.LOOSEN);
        assertThat(r.changes().get(0).label()).isEqualTo("上限");
        assertThat(r.changes().get(0).before()).isEqualTo("12.05");
        assertThat(r.changes().get(0).after())
                .as("尾零统一去掉，避免出现 12.1000 这种从 numeric(18,4) 带出来的样子")
                .isEqualTo("12.1");
        assertThat(r.summary().get(0)).contains("内径").contains("上限").contains("放宽");
    }

    @Test
    @DisplayName("上限调低是收紧")
    void lowerMaxIsTighter() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.00")), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.TIGHTENED);
        assertThat(r.changes().get(0).direction()).isEqualTo(StandardDiff.TIGHTEN);
    }

    @Test
    @DisplayName("下限调低是放宽、下限调高是收紧——跟上限的方向相反，别抄错")
    void minDirectionIsInverted() {
        assertThat(compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.90", "12.05")), items()).verdict())
                .as("下限从 11.95 松到 11.90").isEqualTo(StandardDiff.RELAXED);

        assertThat(compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "12.00", "12.05")), items()).verdict())
                .as("下限从 11.95 提到 12.00").isEqualTo(StandardDiff.TIGHTENED);
    }

    @Test
    @DisplayName("小数位不同不算变更：12.0 和 12.00 是同一个限值")
    void scaleIsNotAChange() {
        // 用 equals 比 BigDecimal 会在这里造出一条假差异，真差异就被淹掉了。
        // 从 Excel 粘进来的限值小数位从来不统一，这条一定会踩
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.0")),
                List.of(row(ITEM_INNER, "11.9500", "12.00")), items());

        assertThat(r.changes()).isEmpty();
        assertThat(r.verdict()).isEqualTo(StandardDiff.NEUTRAL);
    }

    @Test
    @DisplayName("单边规格多出一个限值算收紧，去掉限值算放宽")
    void singleSidedSpec() {
        StandardDiff.Result added = compare(
                List.of(row(ITEM_INNER, null, "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")), items());
        assertThat(added.verdict()).as("只卡上限 → 上下都卡").isEqualTo(StandardDiff.TIGHTENED);

        StandardDiff.Result removed = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", null)), items());
        assertThat(removed.verdict()).as("去掉上限").isEqualTo(StandardDiff.RELAXED);
    }

    // ==================== 关键项 / 必填 / 测点 ====================

    @Test
    @DisplayName("关键项从「是」改成「否」是放宽：超差不再判死整批，Ac 能吞掉")
    void criticalToNonCriticalIsLooser() {
        QcStandardItem oldRow = row(ITEM_INNER, "11.95", "12.05");
        oldRow.setIsCritical(true);
        QcStandardItem newRow = row(ITEM_INNER, "11.95", "12.05");
        newRow.setIsCritical(false);

        StandardDiff.Result r = compare(List.of(oldRow), List.of(newRow), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.RELAXED);
        assertThat(r.changes().get(0).label()).isEqualTo("关键项");
        assertThat(r.changes().get(0).before()).isEqualTo("是");
        assertThat(r.changes().get(0).after()).isEqualTo("否");
    }

    @Test
    @DisplayName("必填项取消必填是放宽：原来必须填，现在可以空着")
    void requiredToOptionalIsLooser() {
        QcStandardItem oldRow = row(ITEM_INNER, "11.95", "12.05");
        oldRow.setIsRequired(true);
        QcStandardItem newRow = row(ITEM_INNER, "11.95", "12.05");
        newRow.setIsRequired(false);

        assertThat(compare(List.of(oldRow), List.of(newRow), items()).verdict())
                .isEqualTo(StandardDiff.RELAXED);
    }

    @Test
    @DisplayName("测点数变少是放宽：原来要量三个点，现在量一个")
    void fewerPointsIsLooser() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_BURR, null, null, 3)),
                List.of(row(ITEM_BURR, null, null, 1)), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.RELAXED);
        assertThat(r.changes().get(0).label()).isEqualTo("测点数");
        assertThat(r.changes().get(0).before()).isEqualTo("3 点");
    }

    // ==================== 增删检验项 ====================

    @Test
    @DisplayName("新增检验项是收紧：多一道关；删掉一项是放宽：少检一样")
    void addRemoveItems() {
        StandardDiff.Result added = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05"), row(ITEM_BURR, null, null)), items());
        assertThat(added.verdict()).isEqualTo(StandardDiff.TIGHTENED);
        assertThat(added.summary()).anyMatch(s -> s.contains("毛刺") && s.contains("新增"));

        StandardDiff.Result removed = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05"), row(ITEM_BURR, null, null)),
                List.of(row(ITEM_INNER, "11.95", "12.05")), items());
        assertThat(removed.verdict()).isEqualTo(StandardDiff.RELAXED);
        assertThat(removed.summary()).anyMatch(s -> s.contains("毛刺") && s.contains("删除"));
    }

    // ==================== 方向不明的那一类 ====================

    @Test
    @DisplayName("布尔合格值反转不算宽严：同一份数据结论会反过来，这是最危险的一种")
    void boolFlipIsNotLoosening() {
        QcStandardItem oldRow = row(ITEM_BURR, null, null);
        oldRow.setBoolPassValue(true);
        QcStandardItem newRow = row(ITEM_BURR, null, null);
        newRow.setBoolPassValue(false);

        StandardDiff.Result r = compare(List.of(oldRow), List.of(newRow), items());

        assertThat(r.verdict())
                .as("不能归进放宽或收紧——它不是宽严问题，是判定方向反了")
                .isEqualTo(StandardDiff.CHANGED);
        assertThat(r.advice()).contains("结论可能完全相反");
    }

    @Test
    @DisplayName("布尔项没显式配置时沿用主数据，两侧实际值相同就不算变更")
    void boolFallbackToItemMaster() {
        QcStandardItem oldRow = row(ITEM_BURR, null, null);
        oldRow.setBoolPassValue(null);
        QcStandardItem newRow = row(ITEM_BURR, null, null);
        newRow.setBoolPassValue(true);

        // 主数据上该布尔项本来就是 true，新版只是把它显式写出来，实际判定没变
        assertThat(compare(List.of(oldRow), List.of(newRow), items()).changes()).isEmpty();
    }

    @Test
    @DisplayName("枚举合格选项减少是收紧、增加是放宽")
    void enumPassOptions() {
        QcStandardItem oldRow = row(ITEM_SCRATCH, null, null);
        oldRow.setOptionsJson("""
                [{"code":"NONE","name":"无","pass":true},
                 {"code":"MINOR","name":"轻微","pass":true},
                 {"code":"BAD","name":"明显","pass":false}]""");
        QcStandardItem newRow = row(ITEM_SCRATCH, null, null);
        newRow.setOptionsJson("""
                [{"code":"NONE","name":"无","pass":true},
                 {"code":"MINOR","name":"轻微","pass":false},
                 {"code":"BAD","name":"明显","pass":false}]""");

        StandardDiff.Result r = compare(List.of(oldRow), List.of(newRow), items());

        assertThat(r.verdict()).as("「轻微」从合格改成不合格").isEqualTo(StandardDiff.TIGHTENED);
        assertThat(r.changes().get(0).label()).isEqualTo("枚举合格选项");
        assertThat(r.changes().get(0).before()).isEqualTo("无、轻微");
        assertThat(r.changes().get(0).after()).isEqualTo("无");
    }

    // ==================== 表头：抽样与允收 ====================

    @Test
    @DisplayName("允收数 Ac 变大是放宽；全检改抽检也是放宽；抽样件数变少还是放宽")
    void headerLoosening() {
        StandardDiff.Result ac = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                std("FULL", "FIXED", 1, 1), std("FULL", "FIXED", 1, 3), items());
        assertThat(ac.verdict()).as("Ac 从 1 件涨到 3 件").isEqualTo(StandardDiff.RELAXED);
        assertThat(ac.changes().get(0).label()).isEqualTo("允收数 Ac");

        StandardDiff.Result sample = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                std("FULL", "FIXED", 1, 0), std("SAMPLE", "FIXED", 8, 0), items());
        assertThat(sample.verdict()).as("全检改成抽检").isEqualTo(StandardDiff.RELAXED);
        assertThat(sample.changes())
                .as("全检的 sampleSize=1 是占位值，不能拿去和抽检件数比——"
                        + "比了就会得出「改成抽检反而更严」的荒谬结论")
                .hasSize(1);
        assertThat(sample.summary()).anyMatch(s -> s.contains("全检") && s.contains("抽检"));

        StandardDiff.Result fewer = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                std("SAMPLE", "FIXED", 32, 0), std("SAMPLE", "FIXED", 8, 0), items());
        assertThat(fewer.verdict()).as("抽 32 件改成抽 8 件").isEqualTo(StandardDiff.RELAXED);
        assertThat(fewer.changes().get(0).label()).isEqualTo("抽样件数");
    }

    @Test
    @DisplayName("AQL 值变大是放宽：同批量下 Ac 更大")
    void aqlValue() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                std("SAMPLE", "AQL", 1, 0, "1.5"), std("SAMPLE", "AQL", 1, 0, "4.0"), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.RELAXED);
        assertThat(r.changes().get(0).label()).isEqualTo("AQL 值");
    }

    @Test
    @DisplayName("固定抽样换成 AQL 查表：样本量不再写在标准上，无法按宽严归类")
    void planSwitch() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                std("SAMPLE", "FIXED", 8, 0), std("SAMPLE", "AQL", 1, 0, "2.5"), items());

        assertThat(r.changes()).anyMatch(c -> "抽样方案".equals(c.label()));
        assertThat(r.verdict()).as("换了算法就不能再拿数字比宽严").isEqualTo(StandardDiff.CHANGED);
    }

    // ==================== 混合与空 ====================

    @Test
    @DisplayName("一边放宽一边收紧：不能只给一个「放宽」标签糊过去")
    void mixedDirections() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.10"), row(ITEM_BURR, null, null)), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.MIXED);
        assertThat(r.advice()).contains("净效果要逐项看");
        assertThat(r.changes()).hasSize(2);
    }

    @Test
    @DisplayName("两版完全一致时不给任何变更")
    void identical() {
        StandardDiff.Result r = compare(
                List.of(row(ITEM_INNER, "11.95", "12.05")),
                List.of(row(ITEM_INNER, "11.95", "12.05")), items());

        assertThat(r.verdict()).isEqualTo(StandardDiff.NEUTRAL);
        assertThat(r.changes()).isEmpty();
        assertThat(r.summary()).isEmpty();
    }

    @Test
    @DisplayName("空版本（刚建的草稿还没配项）不炸")
    void emptyItems() {
        StandardDiff.Result r = compare(List.of(), List.of(), items());
        assertThat(r.verdict()).isEqualTo(StandardDiff.NEUTRAL);
        assertThat(r.changes()).isEmpty();
    }

    @Test
    @DisplayName("检验项主数据缺失时仍然能比，显示成「项#ID」")
    void missingItemMaster() {
        StandardDiff.Result r = compare(
                List.of(row(99L, "1", "2")),
                List.of(row(99L, "1", "3")), Map.of());

        assertThat(r.changes().get(0).itemName()).isEqualTo("项#99");
    }

    // ==================== 构造 ====================

    private static StandardDiff.Result compare(List<QcStandardItem> beforeItems,
                                               List<QcStandardItem> afterItems,
                                               Map<Long, QcItem> index) {
        return StandardDiff.compare(std("FULL", "FIXED", 1, 0), std("FULL", "FIXED", 1, 0),
                beforeItems, afterItems, index);
    }

    private static StandardDiff.Result compare(List<QcStandardItem> beforeItems,
                                               List<QcStandardItem> afterItems,
                                               QcStandard before, QcStandard after,
                                               Map<Long, QcItem> index) {
        return StandardDiff.compare(before, after, beforeItems, afterItems, index);
    }

    private static QcStandardItem row(long itemId, String min, String max) {
        return row(itemId, min, max, 1);
    }

    private static QcStandardItem row(long itemId, String min, String max, int points) {
        QcStandardItem si = new QcStandardItem();
        si.setItemId(itemId);
        si.setMinValue(min == null ? null : new BigDecimal(min));
        si.setMaxValue(max == null ? null : new BigDecimal(max));
        si.setPointCount(points);
        si.setIsCritical(false);
        si.setIsRequired(true);
        si.setIsDestructive(false);
        si.setValueMode(points > 1 ? "MULTIPLE" : "SINGLE");
        return si;
    }

    private static QcStandard std(String samplingType, String plan, int sampleSize, int ac) {
        return std(samplingType, plan, sampleSize, ac, null);
    }

    private static QcStandard std(String samplingType, String plan, int sampleSize, int ac, String aql) {
        QcStandard s = new QcStandard();
        s.setVersion(1);
        s.setSamplingType(samplingType);
        s.setSamplingPlan(plan);
        s.setSampleSize(sampleSize);
        s.setAllowedNgCount(ac);
        s.setAql(aql == null ? null : new BigDecimal(aql));
        s.setInspectionLevel("AQL".equals(plan) ? "II" : null);
        return s;
    }

    private static Map<Long, QcItem> items() {
        Map<Long, QcItem> m = new LinkedHashMap<>();
        m.put(ITEM_INNER, item(ITEM_INNER, "I-ID", "内径", "NUMBER"));
        m.put(ITEM_BURR, item(ITEM_BURR, "I-BURR", "毛刺", "BOOL"));
        m.put(ITEM_SCRATCH, item(ITEM_SCRATCH, "I-SCR", "划伤", "ENUM"));
        return m;
    }

    private static QcItem item(long id, String code, String name, String valueType) {
        QcItem it = new QcItem();
        it.setId(id);
        it.setItemCode(code);
        it.setItemName(name);
        it.setValueType(valueType);
        // 布尔项主数据的合格值定为 true：用来验「标准项没配时沿用主数据」
        it.setBoolPassValue("BOOL".equals(valueType) ? Boolean.TRUE : null);
        return it;
    }
}
