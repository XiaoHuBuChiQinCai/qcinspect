package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcNoSeqMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 取号器的纯逻辑部分。
 *
 * <p>并发正确性由 IT 真库覆盖（{@code InspectionFlowIT#docNoAtomic}）——
 * 这里换成内存假表，只锁住「号码长什么样」「seed 什么时候生效」这类边界，
 * 它们在真库上要造很多数据才能碰到。
 */
class DocNoServiceTest {

    private static final String DAY = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    /** 内存版计数器：语义对齐 qc_no_seq —— seed 只在没有这一行时生效，bump 自增后返回 */
    private static class FakeSeq implements QcNoSeqMapper {
        final Map<String, Long> rows = new HashMap<>();
        long seedCalls;

        @Override
        public int seed(String prefix, long seed) {
            seedCalls++;
            if (rows.containsKey(prefix)) {
                return 0;
            }
            rows.put(prefix, seed);
            return 1;
        }

        @Override
        public Long bump(String prefix) {
            Long v = rows.get(prefix);
            if (v == null) {
                return null;
            }
            rows.put(prefix, v + 1);
            return v + 1;
        }
    }

    @Test
    @DisplayName("流水号补齐位数：QC 是 4 位、SH/TS 是 3 位，日期的 8 位不能被挤掉")
    void padding() {
        FakeSeq seq = new FakeSeq();
        DocNoService svc = new DocNoService(seq);

        assertThat(svc.next("QC", 4, () -> 0L)).isEqualTo("QC" + DAY + "0001");
        assertThat(svc.next("SH", 3, () -> 0L)).isEqualTo("SH" + DAY + "001");
        assertThat(svc.next("TS", 3, () -> 0L)).isEqualTo("TS" + DAY + "001");
    }

    @Test
    @DisplayName("超过位数不截断：一天开一万张单也不该出现重号")
    void noTruncationWhenOverflow() {
        FakeSeq seq = new FakeSeq();
        DocNoService svc = new DocNoService(seq);
        seq.rows.put("QC" + DAY, 9999L);

        assertThat(svc.next("QC", 4, () -> 0L)).isEqualTo("QC" + DAY + "10000");
    }

    @Test
    @DisplayName("seed 只在第一次生效：已有历史单时从历史最大号往后接，不和旧单撞")
    void seedOnlyOnce() {
        FakeSeq seq = new FakeSeq();
        DocNoService svc = new DocNoService(seq);

        assertThat(svc.next("QC", 4, () -> 41L)).isEqualTo("QC" + DAY + "0042");
        // 第二次 seed 传了个离谱的值，必须无效——否则计数器会被历史数覆盖、倒退重号
        assertThat(svc.next("QC", 4, () -> 0L)).isEqualTo("QC" + DAY + "0043");
        assertThat(seq.seedCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("三种单据共用一个计数器表，但前缀互不干扰")
    void prefixesAreIndependent() {
        FakeSeq seq = new FakeSeq();
        DocNoService svc = new DocNoService(seq);

        svc.next("QC", 4, () -> 7L);
        assertThat(svc.next("SH", 3, () -> 0L)).isEqualTo("SH" + DAY + "001");
        // SH 自增不该带动 QC：QC 上一步是 8，这次应该是 9
        assertThat(svc.next("QC", 4, () -> 0L)).isEqualTo("QC" + DAY + "0009");
    }

    @Test
    @DisplayName("负数 seed 归一为 0：宁可从头开始，也不能把计数器推到负号段")
    void negativeSeedClamped() {
        FakeSeq seq = new FakeSeq();
        DocNoService svc = new DocNoService(seq);

        assertThat(svc.next("QC", 4, () -> -5L)).isEqualTo("QC" + DAY + "0001");
    }

    @Test
    @DisplayName("计数器行没了要报中文提示，不能让调用方看到 NPE")
    void missingRowGivesFriendlyError() {
        QcNoSeqMapper broken = new QcNoSeqMapper() {
            @Override
            public int seed(String prefix, long seed) {
                return 0;
            }

            @Override
            public Long bump(String prefix) {
                return null;
            }
        };
        DocNoService svc = new DocNoService(broken);

        assertThatThrownBy(() -> svc.next("QC", 4, () -> 0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单号分配失败");
    }
}
