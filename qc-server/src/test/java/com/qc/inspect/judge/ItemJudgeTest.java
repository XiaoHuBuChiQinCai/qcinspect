package com.qc.inspect.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单项判定器测试。
 *
 * <p>这里刻意覆盖的全是「真实质检现场会踩的坑」：边界值算不算合格、
 * 布尔项合格值配反、多点漏填、选项拼错、非数字输入。
 * 判定错了不会报错，只会静默放行一批坏件，所以这一层必须钉死。
 */
@DisplayName("单项判定 ItemJudge")
class ItemJudgeTest {

    private static ItemSpec number(String min, String max) {
        return ItemSpec.number(min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max));
    }

    private static JudgeOutcome judge(ItemSpec spec, String... values) {
        return ItemJudge.judge(spec, List.of(values), null);
    }

    @Nested
    @DisplayName("数值型")
    class Number {

        @Test
        @DisplayName("落在公差带内 → 合格")
        void withinTolerance() {
            JudgeOutcome o = judge(number("49.90", "50.10"), "50.02");
            assertThat(o.pass()).isTrue();
        }

        @Test
        @DisplayName("恰好等于上下限 → 合格（闭区间，不能把边界判成 NG）")
        void onBoundaryIsPass() {
            assertThat(judge(number("49.90", "50.10"), "49.90").pass()).isTrue();
            assertThat(judge(number("49.90", "50.10"), "50.10").pass()).isTrue();
        }

        @Test
        @DisplayName("超上限 → 不合格，理由带具体数值便于追溯")
        void aboveMax() {
            JudgeOutcome o = judge(number("49.90", "50.10"), "50.11");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("50.11").contains("50.1");
        }

        @Test
        @DisplayName("低于下限 → 不合格")
        void belowMin() {
            JudgeOutcome o = judge(number("49.90", "50.10"), "49.89");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("49.89");
        }

        @Test
        @DisplayName("单边约束只配下限 → 上限不设防")
        void onlyMinConfigured() {
            assertThat(judge(number("10", null), "99999").pass()).isTrue();
            assertThat(judge(number(null, "10"), "-99999").pass()).isTrue();
        }

        @Test
        @DisplayName("未配置限值 → 放行但给出说明（标准维护疏漏要能看出来）")
        void noLimitConfigured() {
            JudgeOutcome o = judge(number(null, null), "123.456");
            assertThat(o.pass()).isTrue();
            assertThat(o.reason()).contains("未配置限值");
        }

        @Test
        @DisplayName("小数比较不走 double → 0.1 + 0.2 这类误差不会误判")
        void decimalPrecision() {
            JudgeOutcome o = judge(number("0.3", "0.3"), "0.30");
            assertThat(o.pass()).isTrue();
        }

        @Test
        @DisplayName("非数字输入 → 不合格，而不是当成 0 放过")
        void notANumber() {
            JudgeOutcome o = judge(number("0", "10"), "abc");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("不是有效数字");
        }

        @Test
        @DisplayName("首尾空格自动裁掉")
        void trimSpaces() {
            assertThat(judge(number("0", "10"), "  5.5  ").pass()).isTrue();
        }

        @Test
        @DisplayName("空值 → 提示「未录入」，而不是「数量不符」这种看不懂的话")
        void blankValue() {
            JudgeOutcome o = judge(number("0", "10"), "");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("未录入");
        }

        @Test
        @DisplayName("null 值列表 → 判不合格，不允许当空单混过去")
        void nullValues() {
            JudgeOutcome o = ItemJudge.judge(number("0", "10"), null, null);
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("未录入");
        }

        @Test
        @DisplayName("多点全部合格 → 合格")
        void multiPointAllPass() {
            ItemSpec spec = ItemSpec.numberMulti(3, new BigDecimal("9.9"), new BigDecimal("10.1"));
            JudgeOutcome o = ItemJudge.judge(spec, List.of("10.0", "9.95", "10.08"), null);
            assertThat(o.pass()).isTrue();
        }

        @Test
        @DisplayName("多点里第 2 点超差 → 不合格，且指出是第 2 点")
        void multiPointSecondFails() {
            ItemSpec spec = ItemSpec.numberMulti(3, new BigDecimal("9.9"), new BigDecimal("10.1"));
            JudgeOutcome o = ItemJudge.judge(spec, List.of("10.0", "10.5", "10.0"), null);
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("第 2 点");
        }

        @Test
        @DisplayName("多点只填了 2 个 → 数量不符，不能按已填的判合格")
        void multiPointCountMismatch() {
            ItemSpec spec = ItemSpec.numberMulti(3, new BigDecimal("9.9"), new BigDecimal("10.1"));
            JudgeOutcome o = ItemJudge.judge(spec, List.of("10.0", "10.0"), null);
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("测点数量不符");
        }
    }

    @Nested
    @DisplayName("枚举型")
    class Enum {

        private final ItemSpec light = ItemSpec.enums(List.of(
                new EnumOption("LIGHT", "轻微", true),
                new EnumOption("HEAVY", "严重", false)));

        @Test
        @DisplayName("合格选项 → 合格")
        void passOption() {
            assertThat(judge(light, "LIGHT").pass()).isTrue();
        }

        @Test
        @DisplayName("不合格选项 → 不合格")
        void failOption() {
            JudgeOutcome o = judge(light, "HEAVY");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("严重");
        }

        @Test
        @DisplayName("选项大小写不敏感（现场扫码/手输都可能小写）")
        void caseInsensitive() {
            assertThat(judge(light, "light").pass()).isTrue();
            assertThat(judge(light, "Heavy").pass()).isFalse();
        }

        @Test
        @DisplayName("选项不存在 → 不合格，不能当成「没选所以没问题」")
        void unknownOption() {
            JudgeOutcome o = judge(light, "XXX");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("选项不存在");
        }

        @Test
        @DisplayName("枚举项未配选项 → 不合格（配置缺失不能放行）")
        void noOptions() {
            JudgeOutcome o = judge(ItemSpec.enums(List.of()), "LIGHT");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("未配置选项");
        }
    }

    @Nested
    @DisplayName("布尔型")
    class Bool {

        /** 典型场景：「是否有毛刺」，合格值是 false（无毛刺） */
        private final ItemSpec burr = ItemSpec.bool(false);

        @Test
        @DisplayName("合格值为「无」，录入无 → 合格")
        void noBurrPass() {
            assertThat(judge(burr, "false").pass()).isTrue();
            assertThat(judge(burr, "无").pass()).isTrue();
            assertThat(judge(burr, "否").pass()).isTrue();
        }

        @Test
        @DisplayName("合格值为「无」，录入有 → 不合格")
        void hasBurrFail() {
            assertThat(judge(burr, "true").pass()).isFalse();
            assertThat(judge(burr, "有").pass()).isFalse();
            assertThat(judge(burr, "是").pass()).isFalse();
        }

        @Test
        @DisplayName("布尔项合格值配反是最隐蔽的 bug：这里钉死两种配置的不同结果")
        void passValueOrientation() {
            // 同一个输入 "true"，配置为「true 判合格」时通过，配置为「false 判合格」时不通过
            assertThat(judge(ItemSpec.bool(true), "true").pass()).isTrue();
            assertThat(judge(ItemSpec.bool(false), "true").pass()).isFalse();
        }

        @ParameterizedTest(name = "\"{0}\" 识别为 true")
        @ValueSource(strings = {"true", "TRUE", "1", "y", "yes", "是", "有"})
        void truthy(String raw) {
            // 合格要求「有」，这些写法都该被认成 true
            assertThat(judge(ItemSpec.bool(true), raw).pass()).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" 识别为 false")
        @ValueSource(strings = {"false", "0", "n", "no", "否", "无"})
        void falsy(String raw) {
            assertThat(judge(ItemSpec.bool(false), raw).pass()).isTrue();
        }

        @Test
        @DisplayName("无法识别的输入 → 不合格，不静默当成 false")
        void unrecognized() {
            JudgeOutcome o = judge(burr, "maybe");
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("无法识别");
        }
    }

    @Nested
    @DisplayName("文本型与异常配置")
    class TextAndBroken {

        @Test
        @DisplayName("文本项只作记录，不参与判定（连空值都不判 NG）")
        void textNeverJudged() {
            assertThat(ItemJudge.judge(ItemSpec.text(), List.of(), "表面有轻微油污").pass()).isTrue();
            assertThat(ItemJudge.judge(ItemSpec.text(), null, null).pass()).isTrue();
        }

        @Test
        @DisplayName("判定配置为 null → 不合格（宁可不放行）")
        void nullSpec() {
            JudgeOutcome o = ItemJudge.judge(null, List.of("1"), null);
            assertThat(o.pass()).isFalse();
            assertThat(o.reason()).contains("配置缺失");
        }
    }

    /**
     * 单项层面的 pass 都是 false，但「做坏了」和「没数」对整批的意义完全不同：
     * 前者是正常不良品，能被允收数消化；后者是记录缺失，Ac 再大也不能判合格。
     * 所以这里把每条 NG 走哪个门钉死。
     */
    @Nested
    @DisplayName("数据不完整 vs 指标不合格")
    class IncompleteFlag {

        private final ItemSpec light = ItemSpec.enums(List.of(
                new EnumOption("LIGHT", "轻微", true),
                new EnumOption("HEAVY", "严重", false)));

        @Test
        @DisplayName("没录 → 数据不完整")
        void blankIsIncomplete() {
            JudgeOutcome o = judge(number("0", "10"), "");
            assertThat(o.pass()).isFalse();
            assertThat(o.incomplete()).isTrue();
        }

        @Test
        @DisplayName("超规 → 指标不合格，不是数据问题")
        void overSpecIsNotIncomplete() {
            JudgeOutcome o = judge(number("0", "10"), "10.5");
            assertThat(o.pass()).isFalse();
            assertThat(o.incomplete()).isFalse();
        }

        @Test
        @DisplayName("不是数字 → 数据不完整（多半是打错字，不是真做坏）")
        void notNumericIsIncomplete() {
            assertThat(judge(number("0", "10"), "1O").incomplete()).isTrue();
        }

        @Test
        @DisplayName("测点数量不够 → 数据不完整")
        void pointsNotEnoughIsIncomplete() {
            ItemSpec three = ItemSpec.numberMulti(3, new BigDecimal("0"), new BigDecimal("10"));
            JudgeOutcome o = ItemJudge.judge(three, List.of("1", "2"), null);
            assertThat(o.pass()).isFalse();
            assertThat(o.incomplete()).isTrue();
        }

        @Test
        @DisplayName("枚举选项拼错 → 数据不完整；判为不合格选项 → 指标不合格")
        void enumSplit() {
            assertThat(judge(light, "LIGTH").incomplete()).as("拼错").isTrue();
            assertThat(judge(light, "HEAVY").incomplete()).as("真不合格").isFalse();
        }

        @Test
        @DisplayName("布尔值无法识别 → 数据不完整；方向不对 → 指标不合格")
        void boolSplit() {
            ItemSpec burr = ItemSpec.bool(true);
            assertThat(judge(burr, "maybe").incomplete()).as("乱填").isTrue();
            assertThat(judge(burr, "false").incomplete()).as("真有毛刺").isFalse();
        }

        @Test
        @DisplayName("文本项从来不产生不合格，也就不会算数据不完整")
        void textNeverIncomplete() {
            assertThat(ItemJudge.judge(ItemSpec.text(), List.of(), null).incomplete()).isFalse();
        }
    }
}
