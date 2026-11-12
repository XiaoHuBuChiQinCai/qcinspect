package com.qc.inspect.capa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAPA 规则。
 *
 * <p>每个用例都成对写「刚好允许」和「刚好拒绝」——只测一侧的话，把条件写成
 * {@code <=} 还是 {@code <} 永远发现不了，而这类差一位的错在期限判断上最要命。
 */
@DisplayName("CAPA 状态机与期限")
class CapaRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    // ------------------------------------------------------------------ 建单

    @Test
    @DisplayName("标题空 → 拒：以后靠它认人")
    void 标题为空拒绝() {
        assertThat(CapaRules.checkCreate("  ", "外径超差 3 件", "张三").ok()).isFalse();
        assertThat(CapaRules.checkCreate(null, "外径超差 3 件", "张三").ok()).isFalse();
    }

    @Test
    @DisplayName("标题超过 200 字 → 拒，并指向问题描述")
    void 标题过长拒绝() {
        CapaRules.Check c = CapaRules.checkCreate("长".repeat(201), "x", "张三");
        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("问题描述");
    }

    @Test
    @DisplayName("问题描述空 → 拒")
    void 问题描述为空拒绝() {
        assertThat(CapaRules.checkCreate("外径反复超差", " ", "张三").ok()).isFalse();
    }

    @Test
    @DisplayName("没有责任人 → 拒：没人负责的措施不会有人做")
    void 责任人为空拒绝() {
        CapaRules.Check c = CapaRules.checkCreate("外径反复超差", "连续 3 批各 1 件", null);
        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("责任人");
    }

    @Test
    @DisplayName("建单不要求根因和措施——这时候确实还没有")
    void 建单不要求根因() {
        assertThat(CapaRules.checkCreate("外径反复超差", "连续 3 批各 1 件", "张三").ok()).isTrue();
    }

    // ------------------------------------------------------------------ 开始

    @Test
    @DisplayName("待分析可以开始")
    void 待分析可以开始() {
        assertThat(CapaRules.checkStart(CapaRules.OPEN).ok()).isTrue();
    }

    @Test
    @DisplayName("已关闭 / 已撤销不能重新开始")
    void 终态不能开始() {
        assertThat(CapaRules.checkStart(CapaRules.CLOSED).ok()).isFalse();
        assertThat(CapaRules.checkStart(CapaRules.CANCELLED).ok()).isFalse();
    }

    @Test
    @DisplayName("已关闭的提示指向「另开一条并注明再犯」")
    void 已关闭不能开始且提示再开() {
        assertThat(CapaRules.checkStart(CapaRules.CLOSED).message()).contains("另开一条");
    }

    @Test
    @DisplayName("执行中 / 待验证 都不能重复开始")
    void 进行中不能重复开始() {
        assertThat(CapaRules.checkStart(CapaRules.DOING).ok()).isFalse();
        assertThat(CapaRules.checkStart(CapaRules.VERIFYING).ok()).isFalse();
    }

    // ------------------------------------------------------------------ 提交验证

    @Test
    @DisplayName("待分析不能直接提交验证")
    void 未开始不能提交验证() {
        assertThat(CapaRules.checkSubmitVerify(CapaRules.OPEN, "原因", "措施").ok()).isFalse();
    }

    @Test
    @DisplayName("执行中缺根因 → 拒，且说明「只是把这批处理掉了」")
    void 缺根因不能提交验证() {
        CapaRules.Check c = CapaRules.checkSubmitVerify(CapaRules.DOING, "   ", "换了夹具");
        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("说不清为什么发生");
    }

    @Test
    @DisplayName("执行中缺纠正措施 → 拒")
    void 缺措施不能提交验证() {
        assertThat(CapaRules.checkSubmitVerify(CapaRules.DOING, "夹具磨损", null).ok()).isFalse();
    }

    @Test
    @DisplayName("根因 + 措施齐全 → 可以提交验证")
    void 齐全可以提交验证() {
        assertThat(CapaRules.checkSubmitVerify(CapaRules.DOING, "夹具磨损", "更换夹具").ok()).isTrue();
    }

    @Test
    @DisplayName("待验证不能重复提交")
    void 待验证不能重复提交() {
        assertThat(CapaRules.checkSubmitVerify(CapaRules.VERIFYING, "原因", "措施").ok()).isFalse();
    }

    @Test
    @DisplayName("已关闭 / 已撤销不能提交验证")
    void 终态不能提交验证() {
        assertThat(CapaRules.checkSubmitVerify(CapaRules.CLOSED, "原因", "措施").ok()).isFalse();
        assertThat(CapaRules.checkSubmitVerify(CapaRules.CANCELLED, "原因", "措施").ok()).isFalse();
    }

    // ------------------------------------------------------------------ 验证

    @Test
    @DisplayName("验证必须明确给有效性，不能只写一段话")
    void 验证必须给有效性() {
        CapaRules.Check c = CapaRules.checkVerify(CapaRules.VERIFYING, null, "已改善");
        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("有效");
    }

    @Test
    @DisplayName("验证结论不能空——三个月后没人知道当时看了什么")
    void 验证结论不能为空() {
        assertThat(CapaRules.checkVerify(CapaRules.VERIFYING, true, " ").ok()).isFalse();
    }

    @Test
    @DisplayName("非待验证状态不能验证")
    void 非待验证不能验证() {
        assertThat(CapaRules.checkVerify(CapaRules.DOING, true, "连续 5 批正常").ok()).isFalse();
        assertThat(CapaRules.checkVerify(CapaRules.OPEN, true, "连续 5 批正常").ok()).isFalse();
    }

    @Test
    @DisplayName("判无效也是合法操作——退回继续改")
    void 判无效合法() {
        assertThat(CapaRules.checkVerify(CapaRules.VERIFYING, false, "第 3 批又出现 1 件").ok()).isTrue();
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    @DisplayName("已关闭不能撤销：验证结论是历史事实")
    void 已关闭不能撤销() {
        assertThat(CapaRules.checkCancel(CapaRules.CLOSED, "不想做了").ok()).isFalse();
    }

    @Test
    @DisplayName("撤销要写理由")
    void 撤销要理由() {
        assertThat(CapaRules.checkCancel(CapaRules.DOING, "  ").ok()).isFalse();
        assertThat(CapaRules.checkCancel(CapaRules.DOING, "并入 CAPA-001，同一次模具问题").ok()).isTrue();
    }

    @Test
    @DisplayName("已撤销的不能再撤销")
    void 重复撤销拒绝() {
        assertThat(CapaRules.checkCancel(CapaRules.CANCELLED, "理由").ok()).isFalse();
    }

    // ------------------------------------------------------------------ 期限

    @Test
    @DisplayName("还开着 + 过了期限 → 超期")
    void 开着且过期算超期() {
        assertThat(CapaRules.overdue(TODAY.minusDays(1), CapaRules.DOING, TODAY)).isTrue();
        assertThat(CapaRules.overdue(TODAY.minusDays(1), CapaRules.OPEN, TODAY)).isTrue();
        assertThat(CapaRules.overdue(TODAY.minusDays(1), CapaRules.VERIFYING, TODAY)).isTrue();
    }

    @Test
    @DisplayName("已关闭的不算超期——那是历史，不是拖延")
    void 已关闭不算超期() {
        assertThat(CapaRules.overdue(TODAY.minusDays(30), CapaRules.CLOSED, TODAY)).isFalse();
        assertThat(CapaRules.overdue(TODAY.minusDays(30), CapaRules.CANCELLED, TODAY)).isFalse();
    }

    @Test
    @DisplayName("当天到期不算超期（闭区间）")
    void 当天到期不算超期() {
        assertThat(CapaRules.overdue(TODAY, CapaRules.DOING, TODAY)).isFalse();
    }

    @Test
    @DisplayName("没有期限不算超期")
    void 无期限不算超期() {
        assertThat(CapaRules.overdue(null, CapaRules.DOING, TODAY)).isFalse();
    }

    @Test
    @DisplayName("超期天数：未超期给 0，不给负数")
    void 超期天数不给负数() {
        assertThat(CapaRules.overdueDays(TODAY.minusDays(5), TODAY)).isEqualTo(5);
        assertThat(CapaRules.overdueDays(TODAY, TODAY)).isZero();
        assertThat(CapaRules.overdueDays(TODAY.plusDays(3), TODAY)).isZero();
        assertThat(CapaRules.overdueDays(null, TODAY)).isZero();
    }

    @Test
    @DisplayName("默认期限 14 天")
    void 默认期限两周() {
        assertThat(CapaRules.defaultDueDate(TODAY)).isEqualTo(TODAY.plusDays(14));
    }

    // ------------------------------------------------------------------ 其他

    @Test
    @DisplayName("只做纠正没写预防 → 提醒但不拦")
    void 缺预防措施给提示() {
        assertThat(CapaRules.closingNote(null)).contains("预防措施");
        assertThat(CapaRules.closingNote("  ")).contains("预防措施");
        assertThat(CapaRules.closingNote("另外三台同型号一并换夹具")).isNull();
    }

    @Test
    @DisplayName("关闭态不占人的精力")
    void 开着的判定() {
        assertThat(CapaRules.isOpen(CapaRules.OPEN)).isTrue();
        assertThat(CapaRules.isOpen(CapaRules.DOING)).isTrue();
        assertThat(CapaRules.isOpen(CapaRules.VERIFYING)).isTrue();
        assertThat(CapaRules.isOpen(CapaRules.CLOSED)).isFalse();
        assertThat(CapaRules.isOpen(CapaRules.CANCELLED)).isFalse();
        assertThat(CapaRules.isOpen(null)).isFalse();
    }

    @Test
    @DisplayName("状态文案：认不出的状态说「未知状态」，不抛异常")
    void 状态文案() {
        assertThat(CapaRules.statusText(CapaRules.VERIFYING)).isEqualTo("待验证");
        assertThat(CapaRules.statusText("WHATEVER")).isEqualTo("未知状态");
        assertThat(CapaRules.statusText(null)).isEqualTo("未知状态");
    }

    // ------------------------------------------------------ 什么时候该立措施

    @Test
    @DisplayName("跨三个批次才推：挤在一批里的多少次都不推")
    void 建议门槛看批次数不看次数() {
        // 三批各一次：换料换班都还在，是过程的病
        assertThat(CapaRules.advice(3, 3, false, false).suggest()).isTrue();
        // 一批十次：那是那批货的事，先把这批处理掉
        assertThat(CapaRules.advice(1, 10, false, false).suggest()).isFalse();
        // 两批：够看，但还不够推——两天各撞一次可能只是同一台设备的两个班
        assertThat(CapaRules.advice(2, 8, false, false).suggest()).isFalse();
    }

    @Test
    @DisplayName("已经在改的不推：再列一条会让人以为系统不知道他正在做")
    void 有开着的措施不再推() {
        assertThat(CapaRules.advice(5, 9, false, false).suggest()).isTrue();
        assertThat(CapaRules.advice(5, 9, true, false).suggest()).isFalse();
        assertThat(CapaRules.advice(5, 9, true, true).suggest()).isFalse();
    }

    @Test
    @DisplayName("改过又犯：哪怕只冒一次也要推，且标成改过又犯")
    void 改过又犯最该被看见() {
        CapaRules.Advice a = CapaRules.advice(1, 1, false, true);
        assertThat(a.suggest()).isTrue();
        assertThat(a.regressed()).isTrue();
        assertThat(a.note()).contains("验证有效");

        // 首次出现（没关过措施）且只有一批，不推
        assertThat(CapaRules.advice(1, 1, false, false).suggest()).isFalse();
    }

    @Test
    @DisplayName("跨批次但没关过措施：说的是「查一次根因」，不说「上次打偏了」")
    void 首次反复的措辞不能说成再犯() {
        CapaRules.Advice a = CapaRules.advice(4, 6, false, false);
        assertThat(a.regressed()).isFalse();
        assertThat(a.note()).doesNotContain("上次");
        assertThat(a.note()).contains("批次");
    }

    @Test
    @DisplayName("不推的时候不带文案：前端不会拿到一句用不上的话")
    void 不推就没有note() {
        CapaRules.Advice a = CapaRules.advice(1, 1, false, false);
        assertThat(a.suggest()).isFalse();
        assertThat(a.note()).isNull();
    }

    // ------------------------------------------------------------ 什么时候该催

    @Test
    @DisplayName("距到期天数：今天到期是 0，已超期是负数")
    void 距到期天数() {
        assertThat(CapaRules.dueInDays(TODAY, CapaRules.DOING, TODAY)).isZero();
        assertThat(CapaRules.dueInDays(TODAY.plusDays(1), CapaRules.DOING, TODAY)).isEqualTo(1);
        // 超期给负数：「已经晚了」和「快到了」不是同一句话
        assertThat(CapaRules.dueInDays(TODAY.minusDays(5), CapaRules.DOING, TODAY)).isEqualTo(-5);
    }

    @Test
    @DisplayName("结束了的不算天数：关闭、撤销、没定期限都给 null")
    void 结束态没有天数() {
        assertThat(CapaRules.dueInDays(TODAY.plusDays(3), CapaRules.CLOSED, TODAY)).isNull();
        assertThat(CapaRules.dueInDays(TODAY.plusDays(3), CapaRules.CANCELLED, TODAY)).isNull();
        assertThat(CapaRules.dueInDays(null, CapaRules.DOING, TODAY)).isNull();
    }

    @Test
    @DisplayName("快到期的窗口是闭区间 0~3 天，第 4 天就不催了")
    void 催的窗口边界() {
        assertThat(CapaRules.dueSoon(TODAY, CapaRules.DOING, TODAY)).isTrue();
        assertThat(CapaRules.dueSoon(TODAY.plusDays(3), CapaRules.DOING, TODAY)).isTrue();
        assertThat(CapaRules.dueSoon(TODAY.plusDays(4), CapaRules.DOING, TODAY)).isFalse();
        // 昨天到期的归「超期」管，不重复出现在「快到期」里
        assertThat(CapaRules.dueSoon(TODAY.minusDays(1), CapaRules.DOING, TODAY)).isFalse();
    }

    @Test
    @DisplayName("已提交验证的不催：期限对交上来的人没意义，压着的是验证人")
    void 待验证不催责任人() {
        assertThat(CapaRules.dueSoon(TODAY.plusDays(1), CapaRules.VERIFYING, TODAY)).isFalse();
        assertThat(CapaRules.dueSoon(TODAY.plusDays(1), CapaRules.DOING, TODAY)).isTrue();
        assertThat(CapaRules.dueSoon(TODAY.plusDays(1), CapaRules.OPEN, TODAY)).isTrue();
    }
}
