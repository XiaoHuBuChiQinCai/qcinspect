package com.qc.inspect.spc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判异准则的窗口长度和边界，错一位没人看得出来——图上标红的点挪了一个位置，
 * 看的人只会以为「就是那个点有问题」。所以每条准则都要有「刚好触发」和「刚好不触发」两侧的用例。
 */
class ControlRulesTest {

    private static List<Double> of(double... v) {
        List<Double> list = new ArrayList<>();
        for (double d : v) {
            list.add(d);
        }
        return list;
    }

    /** 平稳数据：没有一条准则该触发 */
    @Test
    void 随机波动不报异常() {
        ControlRules.Report r = ControlRules.check(of(1, -1, 0.5, -0.5, 1.2), 0, 1);
        assertEquals(ControlRules.STABLE, r.verdict());
        assertTrue(r.alarms().isEmpty());
    }

    /** 准则 1：超出 3σ */
    @Test
    void 单点超出3σ判失控() {
        ControlRules.Report r = ControlRules.check(of(0.1, 0.2, 3.5, 0.1), 0, 1);
        assertEquals(ControlRules.OUT_OF_CONTROL, r.verdict());
        assertEquals(1, r.alarms().size());
        ControlRules.Alarm a = r.alarms().get(0);
        assertEquals("R1", a.code());
        assertEquals("CRITICAL", a.level());
        assertEquals(3, a.fromSeq());
        assertEquals(3, a.toSeq());
    }

    /** 正好压在 3σ 上不算出界：控制限是闭区间，越限要「越过去」 */
    @Test
    void 正好落在3σ上不算出界() {
        ControlRules.Report r = ControlRules.check(of(0.1, 3.0, -0.1, 0.2), 0, 1);
        assertTrue(r.alarms().stream().noneMatch(a -> "R1".equals(a.code())));
    }

    /** 准则 2：连续 9 点同侧 */
    @Test
    void 连续9点同侧判偏移() {
        ControlRules.Report r = ControlRules.check(of(0.2, 0.3, 0.1, 0.4, 0.2, 0.3, 0.1, 0.2, 0.3), 0, 1);
        assertEquals(ControlRules.WARNING, r.verdict());
        ControlRules.Alarm a = r.alarms().get(0);
        assertEquals("R2", a.code());
        assertEquals(1, a.fromSeq());
        assertEquals(9, a.toSeq());
        assertEquals(1, a.hits());
        assertTrue(a.text().contains("上方"), a.text());
    }

    /** 8 点同侧还不够，第 9 点才成立 */
    @Test
    void 只有8点同侧不报() {
        ControlRules.Report r = ControlRules.check(of(0.2, 0.3, 0.1, 0.4, 0.2, 0.3, 0.1, 0.2), 0, 1);
        assertTrue(r.alarms().stream().noneMatch(a -> "R2".equals(a.code())));
    }

    /** 10 点同侧 = 触发 2 次，位置报最近那一次（第 2~10 点） */
    @Test
    void 同侧偏移报最近一次位置并计次() {
        ControlRules.Report r = ControlRules.check(
                of(0.2, 0.3, 0.1, 0.4, 0.2, 0.3, 0.1, 0.2, 0.3, 0.2), 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R2".equals(x.code())).findFirst().orElseThrow();
        assertEquals(2, a.fromSeq());
        assertEquals(10, a.toSeq());
        assertEquals(2, a.hits());
    }

    /** 同侧偏移要分清偏上还是偏下——「查什么时候开始偏的」方向不同，查的东西也不同 */
    @Test
    void 连续9点在下侧文案说下方() {
        ControlRules.Report r = ControlRules.check(
                of(-0.2, -0.3, -0.1, -0.4, -0.2, -0.3, -0.1, -0.2, -0.3), 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R2".equals(x.code())).findFirst().orElseThrow();
        assertTrue(a.text().contains("下方"), a.text());
    }

    /** 准则 3：连续 6 点递增 */
    @Test
    void 连续6点递增判趋势() {
        ControlRules.Report r = ControlRules.check(of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6), 0.35, 1);
        assertEquals(ControlRules.WARNING, r.verdict());
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R3".equals(x.code())).findFirst().orElseThrow();
        assertEquals(1, a.fromSeq());
        assertEquals(6, a.toSeq());
        assertTrue(a.title().contains("上升"));
    }

    /** 5 点递增还不算趋势 */
    @Test
    void 连续5点递增不报趋势() {
        ControlRules.Report r = ControlRules.check(of(0.1, 0.2, 0.3, 0.4, 0.5), 0.3, 1);
        assertTrue(r.alarms().stream().noneMatch(a -> "R3".equals(a.code())));
    }

    /** 准则 4：连续 14 点上下交替 */
    @Test
    void 连续14点交替判系统性() {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            v.add(i % 2 == 0 ? 0.5 : -0.5);
        }
        ControlRules.Report r = ControlRules.check(v, 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R4".equals(x.code())).findFirst().orElseThrow();
        assertEquals(1, a.fromSeq());
        assertEquals(14, a.toSeq());
    }

    /** 准则 5：3 点中 2 点在 2σ 外。滑窗会滚动命中两次（第 1~3 点、第 2~4 点），报最近一次 */
    @Test
    void 三点中两点在2σ外() {
        ControlRules.Report r = ControlRules.check(of(0, 2.5, 2.6, 0, 0), 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R5".equals(x.code())).findFirst().orElseThrow();
        assertEquals(2, a.fromSeq());
        assertEquals(4, a.toSeq());
        assertEquals(2, a.hits());
        assertEquals(0, r.alarms().stream().filter(x -> "R5".equals(x.code())).count() - 1,
                "同一侧的同一条准则只报一条，位置取最近的那次");
    }

    /** 一上一下各一个 2σ：不同侧不算准则 5 */
    @Test
    void 三点中两个在2σ外但分属两侧不算() {
        ControlRules.Report r = ControlRules.check(of(2.5, -2.5, 0, 0, 0), 0, 1);
        assertTrue(r.alarms().stream().noneMatch(a -> "R5".equals(a.code())));
    }

    /** 准则 6：5 点中 4 点在 1σ 外 */
    @Test
    void 五点中四点在1σ外() {
        ControlRules.Report r = ControlRules.check(of(1.5, 1.6, 1.7, 1.8, 0), 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R6".equals(x.code())).findFirst().orElseThrow();
        assertTrue(a.text().contains("1σ"), a.text());
    }

    /** 准则 7：15 点全在 1σ 内，波动小得不像真的 */
    @Test
    void 连续15点挤在1σ内判波动过小() {
        ControlRules.Report r = ControlRules.check(
                of(0.0, 0.05, 0.1, 0.05, 0.02, -0.02, -0.05, -0.1, -0.05, 0.0, 0.05, 0.1, 0.08, 0.02, -0.03), 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R7".equals(x.code())).findFirst().orElseThrow();
        assertEquals(1, a.fromSeq());
        assertEquals(15, a.toSeq());
        assertTrue(a.text().contains("量具"), a.text());
    }

    /** 准则 8：连续 8 点都在 1σ 外，两侧都有 */
    @Test
    void 连续8点在1σ外两侧都有() {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            v.add(i % 2 == 0 ? 1.5 : -1.5);
        }
        ControlRules.Report r = ControlRules.check(v, 0, 1);
        ControlRules.Alarm a = r.alarms().stream().filter(x -> "R8".equals(x.code())).findFirst().orElseThrow();
        assertEquals(1, a.fromSeq());
        assertEquals(8, a.toSeq());
    }

    /** σ̂ 为 0 是无法判读，不是「一切正常」——这个区别在页面上就是「绿」和「灰」 */
    @Test
    void 标准差为零判无法判读而不是稳定() {
        ControlRules.Report r = ControlRules.check(of(5, 5, 5, 5), 5, 0);
        assertEquals(ControlRules.UNKNOWN, r.verdict());
        assertTrue(r.alarms().isEmpty());
    }

    /** 点太少：不报异，但也别拿「未见异常」当结论说满 */
    @Test
    void 点数不足不报异() {
        assertEquals(ControlRules.STABLE, ControlRules.check(of(1), 0, 1).verdict());
        assertEquals(ControlRules.STABLE, ControlRules.check(List.of(), 0, 1).verdict());
        assertEquals(ControlRules.STABLE, ControlRules.check(null, 0, 1).verdict());
    }

    /** 出界 + 同侧偏移同时成立：结论取最严重的那一档，两条都要报出来 */
    @Test
    void 多条准则同时触发时取最严重结论() {
        ControlRules.Report r = ControlRules.check(
                of(0.2, 0.3, 0.1, 4.2, 0.2, 0.3, 0.1, 0.2, 0.3), 0, 1);
        assertEquals(ControlRules.OUT_OF_CONTROL, r.verdict());
        assertTrue(r.alarms().stream().anyMatch(a -> "R1".equals(a.code())));
        assertTrue(r.alarms().stream().anyMatch(a -> "R2".equals(a.code())));
    }

    /** 每条准则的文案都要能被车间看懂：至少不能是空的，也不能只有代码 */
    @Test
    void 告警都有说明文案() {
        ControlRules.Report r = ControlRules.check(of(0.1, 3.9, 2.8, 2.7, 0.0, 0.1), 0, 1);
        assertNotNull(r.verdictText());
        for (ControlRules.Alarm a : r.alarms()) {
            assertNotNull(a.title());
            assertTrue(a.text().length() > 20, a.code() + " 的说明太短：" + a.text());
        }
    }
}
