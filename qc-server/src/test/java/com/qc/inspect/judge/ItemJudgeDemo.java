package com.qc.inspect.judge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 判定引擎验证程序，直接 javac + java 运行，不依赖任何框架。
 * 覆盖四种数据类型、单值/多点、件级判定与整批判定（两层）。
 */
public class ItemJudgeDemo {

    private static int passed = 0;
    private static int total = 0;

    public static void main(String[] args) {
        System.out.println("========== 单项判定 ==========");

        // 数值型：单值
        check("数值单值 达标", ItemJudge.judge(
                ItemSpec.number(bd("2.5"), bd("3.0")), List.of("2.62"), null).pass());
        check("数值单值 超上限", !ItemJudge.judge(
                ItemSpec.number(bd("2.5"), bd("3.0")), List.of("3.12"), null).pass());
        check("数值单值 低于下限", !ItemJudge.judge(
                ItemSpec.number(bd("2.5"), bd("3.0")), List.of("2.41"), null).pass());
        check("数值单值 恰好等于上限算合格", ItemJudge.judge(
                ItemSpec.number(bd("2.5"), bd("3.0")), List.of("3.0"), null).pass());

        // 数值型：多点
        check("数值多点 全部达标", ItemJudge.judge(
                ItemSpec.numberMulti(3, bd("11.95"), bd("12.05")),
                List.of("12.03", "12.01", "12.02"), null).pass());
        check("数值多点 第2点超差", !ItemJudge.judge(
                ItemSpec.numberMulti(3, bd("11.95"), bd("12.05")),
                List.of("12.03", "12.11", "12.02"), null).pass());
        check("数值多点 测点数量不符", !ItemJudge.judge(
                ItemSpec.numberMulti(3, bd("11.95"), bd("12.05")),
                List.of("12.03", "12.01"), null).pass());
        check("数值 非法输入", !ItemJudge.judge(
                ItemSpec.number(bd("1"), bd("2")), List.of("12.O3"), null).pass());

        // 枚举型
        List<EnumOption> scratch = List.of(
                new EnumOption("NONE", "无划伤", true),
                new EnumOption("LIGHT", "轻微划伤", true),
                new EnumOption("HEAVY", "严重划伤", false));
        check("枚举 合格选项", ItemJudge.judge(ItemSpec.enums(scratch), List.of("LIGHT"), null).pass());
        check("枚举 不合格选项", !ItemJudge.judge(ItemSpec.enums(scratch), List.of("HEAVY"), null).pass());
        check("枚举 未知选项", !ItemJudge.judge(ItemSpec.enums(scratch), List.of("XXX"), null).pass());

        // 布尔型：项目名称是「是否有毛刺」，合格值是 false（无毛刺）
        check("布尔 无毛刺合格", ItemJudge.judge(ItemSpec.bool(false), List.of("false"), null).pass());
        check("布尔 有毛刺不合格", !ItemJudge.judge(ItemSpec.bool(false), List.of("true"), null).pass());
        check("布尔 中文值兼容", !ItemJudge.judge(ItemSpec.bool(false), List.of("有"), null).pass());
        check("布尔 无法识别", !ItemJudge.judge(ItemSpec.bool(false), List.of("maybe"), null).pass());

        // 文本型
        check("文本 不参与判定", ItemJudge.judge(ItemSpec.text(), List.of(), "随便写点什么").pass());

        System.out.println();
        System.out.println("========== 件级判定 ==========");

        // 单件全合格
        InspectionOutcome singleOk = InspectionJudge.judge(List.of(
                single("外径", true, ItemSpec.numberMulti(3, bd("11.95"), bd("12.05")), "12.03", "12.01", "12.02"),
                single("壁厚", false, ItemSpec.number(bd("2.5"), bd("3.0")), "2.62"),
                single("毛刺", false, ItemSpec.bool(false), "false")
        ), 0);
        check("单件 全合格 → PASS", singleOk.pass());
        check("单件 合格件数 1", singleOk.passedQty() == 1 && singleOk.failedQty() == 0);
        print(singleOk);

        // 单件关键项 NG
        InspectionOutcome criticalNg = InspectionJudge.judge(List.of(
                single("外径", true, ItemSpec.numberMulti(3, bd("11.95"), bd("12.05")), "12.03", "12.18", "12.02"),
                single("壁厚", false, ItemSpec.number(bd("2.5"), bd("3.0")), "2.62")
        ), 0);
        check("单件 关键项NG → FAIL", !criticalNg.pass());
        check("单件 关键项NG 计数为1", criticalNg.criticalNgCount() == 1);
        check("单件 失败件数 1", criticalNg.failedQty() == 1);
        print(criticalNg);

        // 单件非关键项 NG，任一项 NG 即该件 NG
        InspectionOutcome minorNg = InspectionJudge.judge(List.of(
                single("壁厚", false, ItemSpec.number(bd("2.5"), bd("3.0")), "3.12"),
                single("毛刺", false, ItemSpec.bool(false), "false")
        ), 0);
        check("单件 非关键项NG → 该件仍NG（项级不放宽）", !minorNg.pass() && minorNg.failedQty() == 1);
        check("单件 非关键项NG → 关键项计数为0", minorNg.criticalNgCount() == 0);

        System.out.println();
        System.out.println("========== 整批判定（抽样 5 件） ==========");

        // 5 件：第 3 件外径超差，其余合格。Ac=1 → 允收
        List<JudgeTarget> batch5 = fivePieces(3);
        InspectionOutcome allowed = InspectionJudge.judge(batch5, 1);
        check("整批 1 件NG / 允收1件 → PASS", allowed.pass());
        check("整批 合格4件 不合格1件", allowed.passedQty() == 4 && allowed.failedQty() == 1);
        check("整批 件数合计=5", allowed.pieces().size() == 5);
        check("整批 理由含件号", allowed.reason().contains("第 3 件"));
        print(allowed);

        // 同一批，Ac=0 → 拒收
        InspectionOutcome rejected = InspectionJudge.judge(batch5, 0);
        check("整批 1 件NG / 允收0件 → FAIL", !rejected.pass());
        print(rejected);

        // 5 件里 2 件不合格，Ac=1 → 拒收
        List<JudgeTarget> twoNg = fivePieces(3);
        twoNg.add(piece(5, "壁厚", false, ItemSpec.number(bd("2.5"), bd("3.0")), "3.44"));
        InspectionOutcome twoFailed = InspectionJudge.judge(twoNg, 1);
        check("整批 2 件NG / 允收1件 → FAIL", !twoFailed.pass());
        check("整批 失败件数 2", twoFailed.failedQty() == 2);

        // 关键项 NG 出现在第 2 件，允许 Ac=3：仍判允收，但理由必须点出关键项
        List<JudgeTarget> criticalInPiece = fivePieces(-1);
        criticalInPiece.add(piece(2, "硬度", true, ItemSpec.number(bd("45"), bd("50")), "41"));
        InspectionOutcome criticalBatch = InspectionJudge.judge(criticalInPiece, 3);
        check("整批 关键项NG在允收内 → 仍 PASS", criticalBatch.pass());
        check("整批 关键项NG在允收内 → 理由含关键项提示", criticalBatch.reason().contains("关键项问题"));
        check("整批 关键项NG 行计数为1", criticalBatch.criticalNgCount() == 1);
        print(criticalBatch);

        System.out.println();
        System.out.println("========== 边界 ==========");

        InspectionOutcome empty = InspectionJudge.judge(List.of(), 0);
        check("空单 → 判不合格（不允许空单合格）", !empty.pass());

        check("未录入 → 理由说明是「未录入」",
                ItemJudge.judge(ItemSpec.number(bd("1"), bd("2")), List.of(), null).reason().contains("未录入"));
        check("只填部分测点 → 仍判数量不符",
                ItemJudge.judge(ItemSpec.numberMulti(3, bd("1"), bd("2")), List.of("1.5"), null)
                        .reason().contains("测点数量不符"));

        InspectionOutcome negativeAc = InspectionJudge.judge(fivePieces(2), -3);
        check("允收数负数按 0 处理", negativeAc.acceptQty() == 0 && !negativeAc.pass());

        // 同一件重复项：两行同件同项，任一行不合格就该件不合格
        List<JudgeTarget> dup = new ArrayList<>();
        dup.add(piece1("外径", true, ItemSpec.number(bd("11.95"), bd("12.05")), "12.00"));
        dup.add(piece1("外径", true, ItemSpec.number(bd("11.95"), bd("12.05")), "12.30"));
        InspectionOutcome dupOutcome = InspectionJudge.judge(dup, 0);
        check("同件同项重复行 任一行NG → 该件NG", !dupOutcome.pass());

        System.out.println();
        System.out.println("========================================");
        System.out.println("通过 " + passed + " / " + total + " 项断言");
        if (passed != total) {
            System.exit(1);
        }
    }

    /** 5 件 × 3 项的批次，badPiece 指定的那件外径超差（传 -1 表示全部合格） */
    private static List<JudgeTarget> fivePieces(int badPiece) {
        List<JudgeTarget> list = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            boolean bad = p == badPiece;
            list.add(piece(p, "外径", true, ItemSpec.number(bd("11.95"), bd("12.05")),
                    bad ? "12.30" : "12.00"));
            list.add(piece(p, "壁厚", false, ItemSpec.number(bd("2.5"), bd("3.0")), "2.70"));
            list.add(piece(p, "毛刺", false, ItemSpec.bool(false), "false"));
        }
        return list;
    }

    private static JudgeTarget piece(int pieceNo, String name, boolean critical, ItemSpec spec, String... values) {
        return new JudgeTarget(null, null, pieceNo, name, critical, spec, List.of(values), null);
    }

    private static JudgeTarget piece1(String name, boolean critical, ItemSpec spec, String... values) {
        return piece(1, name, critical, spec, values);
    }

    /** 件号恒为 1，用于单件场景 */
    private static JudgeTarget single(String name, boolean critical, ItemSpec spec, String... values) {
        return piece(1, name, critical, spec, values);
    }

    private static void check(String title, boolean condition) {
        total++;
        if (condition) {
            passed++;
            System.out.println("  [OK]   " + title);
        } else {
            System.out.println("  [FAIL] " + title);
        }
    }

    private static void print(InspectionOutcome o) {
        System.out.println("         → " + (o.pass() ? "PASS" : "FAIL") + "｜"
                + "合格 " + o.passedQty() + " / 不合格 " + o.failedQty()
                + "（允收 " + o.acceptQty() + "）｜" + o.reason());
        o.pieces().forEach(p ->
                System.out.println("           · 第 " + p.pieceNo() + " 件 "
                        + (p.pass() ? "合格" : "不合格")
                        + (p.pass() ? "" : "（" + p.reason() + "）")));
        System.out.println();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
