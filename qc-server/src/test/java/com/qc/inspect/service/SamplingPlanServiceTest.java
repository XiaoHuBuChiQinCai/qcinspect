package com.qc.inspect.service;

import com.qc.inspect.entity.QcSamplingPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽样表校验：锁住 GB/T 2828.1-2012 一般检验水平 II / AQL 2.5 的 11 行数据。
 *
 * <p>为什么要拿脚本文本反过来验：这张表的每一格都是别人算好的，
 * 抄错一格（比如 26~50 档把 Ac 记成 1）在系统里不会有任何报错，
 * 只会让来料判定悄悄变松。所以这里直接把 db/V7__sampling_plan.sql 读出来，
 * 逐行跟国标公布的数值比——改错一个数，测试立刻红。
 *
 * <p>对照来源：GB/T 38029-2019 附录C、GB/T 15552-2015 附录B、
 * FZ/T 43015-2021 附录A 三份国标附录，数值一致。
 */
@DisplayName("AQL 抽样表与批量匹配")
class SamplingPlanServiceTest {

    /** 批量下限, 批量上限, 字码, n, Ac, Re */
    private static final String[][] GB2828_II_AQL25 = {
            {"2", "8", "A", "2", "0", "1"},
            {"9", "15", "B", "3", "0", "1"},
            {"16", "25", "C", "5", "0", "1"},
            {"26", "50", "D", "8", "0", "1"},
            {"51", "90", "E", "13", "1", "2"},
            {"91", "150", "F", "20", "1", "2"},
            {"151", "280", "G", "32", "2", "3"},
            {"281", "500", "H", "50", "3", "4"},
            {"501", "1200", "J", "80", "5", "6"},
            {"1201", "3200", "K", "125", "7", "8"},
            {"3201", "10000", "L", "200", "10", "11"},
    };

    @Test
    @DisplayName("内置方案表与国标数值逐行一致")
    void seedMatchesNationalStandard() throws IOException {
        List<QcSamplingPlan> rows = loadSeed();

        assertThat(rows).as("11 个批量区间要齐，少一档会出现批量无方案可用").hasSize(GB2828_II_AQL25.length);

        for (int i = 0; i < GB2828_II_AQL25.length; i++) {
            String[] e = GB2828_II_AQL25[i];
            QcSamplingPlan p = rows.get(i);
            assertThat(p.getLotSizeMin()).as("第 %d 行 批量下限", i + 1).isEqualTo(Integer.parseInt(e[0]));
            assertThat(p.getLotSizeMax()).as("第 %d 行 批量上限", i + 1).isEqualTo(Integer.parseInt(e[1]));
            assertThat(p.getCodeLetter()).as("第 %d 行 字码", i + 1).isEqualTo(e[2]);
            assertThat(p.getSampleSize()).as("第 %d 行 样本量 n", i + 1).isEqualTo(Integer.parseInt(e[3]));
            assertThat(p.getAcceptQty()).as("第 %d 行 接收数 Ac", i + 1).isEqualTo(Integer.parseInt(e[4]));
            assertThat(p.getRejectQty()).as("第 %d 行 拒收数 Re", i + 1).isEqualTo(Integer.parseInt(e[5]));
            // 一次抽样恒有 Re = Ac + 1，抄错会在这里露出来
            assertThat(p.getRejectQty()).isEqualTo(p.getAcceptQty() + 1);
        }
    }

    @Test
    @DisplayName("批量区间首尾端点都命中本档，不越界不落空")
    void boundariesHitOwnRange() throws IOException {
        List<QcSamplingPlan> rows = loadSeed();

        for (String[] e : GB2828_II_AQL25) {
            int lo = Integer.parseInt(e[0]);
            int hi = Integer.parseInt(e[1]);
            QcSamplingPlan atLo = SamplingPlanService.pick(rows, lo);
            QcSamplingPlan atHi = SamplingPlanService.pick(rows, hi);
            assertThat(atLo).as("批量 %d 应命中 %s 档", lo, e[2]).isNotNull();
            assertThat(atHi).as("批量 %d 应命中 %s 档", hi, e[2]).isNotNull();
            assertThat(atLo.getCodeLetter()).as("批量 %d 落在本档下限", lo).isEqualTo(e[2]);
            assertThat(atHi.getCodeLetter()).as("批量 %d 落在本档上限", hi).isEqualTo(e[2]);
            // 闭区间：25 属于 C 档，26 必须落到 D 档，这是最容易写错的地方
            assertThat(atHi.getSampleSize()).isEqualTo(atLo.getSampleSize());
        }
    }

    @Test
    @DisplayName("常见来料批量取到该取的样本量与允收数")
    void typicalLots() throws IOException {
        List<QcSamplingPlan> rows = loadSeed();

        assertThat(SamplingPlanService.pick(rows, 50).getAcceptQty()).isZero();
        // 来料 300 件：字码 H、抽 50、允收 3
        QcSamplingPlan p300 = SamplingPlanService.pick(rows, 300);
        assertThat(p300.getCodeLetter()).isEqualTo("H");
        assertThat(p300.getSampleSize()).isEqualTo(50);
        assertThat(p300.getAcceptQty()).isEqualTo(3);
        // 来料 5000 件：字码 L、抽 200、允收 10
        QcSamplingPlan p5000 = SamplingPlanService.pick(rows, 5000);
        assertThat(p5000.getCodeLetter()).isEqualTo("L");
        assertThat(p5000.getSampleSize()).isEqualTo(200);
        assertThat(p5000.getAcceptQty()).isEqualTo(10);
        // 批量翻十倍，样本量必须跟着涨——「固定抽 5 件」的老问题就在这
        assertThat(p5000.getSampleSize()).isGreaterThan(p300.getSampleSize());
    }

    @Test
    @DisplayName("批量小于最小档、大于最大档都不返回方案，由调用方决定兜底")
    void outOfTableRange() throws IOException {
        List<QcSamplingPlan> rows = loadSeed();

        assertThat(SamplingPlanService.pick(rows, 1)).as("批量 1 件不在 2~8 档内").isNull();
        assertThat(SamplingPlanService.pick(rows, 10001)).as("超过 10000 件不在表内").isNull();
        assertThat(SamplingPlanService.pick(List.of(), 300)).as("空表不能炸").isNull();
    }

    @Test
    @DisplayName("AQL 数字按人读形式输出，2.500 显示成 2.5")
    void aqlText() {
        assertThat(SamplingPlanService.trim(new BigDecimal("2.500"))).isEqualTo("2.5");
        assertThat(SamplingPlanService.trim(new BigDecimal("1.000"))).isEqualTo("1");
        assertThat(SamplingPlanService.trim(null)).isEmpty();
    }

    // ==================== 辅助 ====================

    /** 从建表脚本里把内置的 11 行数据读出来，避免测试另抄一份再慢慢跑偏 */
    private List<QcSamplingPlan> loadSeed() throws IOException {
        String sql;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/db/V7__sampling_plan.sql"),
                "建表脚本没进测试 classpath，检查 pom 的 testResources")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher m = Pattern.compile(
                        "\\('(GB2828-1)',\\s*'(II)',\\s*([\\d.]+),\\s*(\\d+),\\s*(\\d+),\\s*'([A-R])',"
                                + "\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)")
                .matcher(sql);

        List<QcSamplingPlan> rows = new ArrayList<>();
        while (m.find()) {
            QcSamplingPlan p = new QcSamplingPlan();
            p.setPlanCode(m.group(1));
            p.setInspectionLevel(m.group(2));
            p.setAql(new BigDecimal(m.group(3)));
            p.setLotSizeMin(Integer.parseInt(m.group(4)));
            p.setLotSizeMax(Integer.parseInt(m.group(5)));
            p.setCodeLetter(m.group(6));
            p.setSampleSize(Integer.parseInt(m.group(7)));
            p.setAcceptQty(Integer.parseInt(m.group(8)));
            p.setRejectQty(Integer.parseInt(m.group(9)));
            rows.add(p);
        }
        // 脚本里按批量升序 INSERT，读出来也应是升序
        rows.sort((a, b) -> Integer.compare(a.getLotSizeMin(), b.getLotSizeMin()));
        return rows;
    }
}
