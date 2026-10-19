package com.qc.inspect.judge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 整批判定：两层结构。
 *
 * <p><b>第一层（件）</b>：同一 pieceNo 的所有行一起判。任一行不合格 → 该件不合格。
 * 关键项不合格单独统计，用于说明为什么这件过不了。
 *
 * <p><b>第二层（批）</b>：不合格件数 failedQty 与允收数 acceptQty 比较
 * （即抽样检验里的 Ac）。failedQty &lt;= acceptQty → 整批合格。
 *
 * <p>注意 acceptQty 的语义是<b>允许的不合格件数</b>，不是允许的不合格项数。
 * 一个批次抽 20 件，Ac = 2，意味着最多容忍 2 件不合格。
 *
 * <p>没有检验数据时判不合格——没有数据就不能判合格，这是质量记录的基本要求。
 */
public final class InspectionJudge {

    private InspectionJudge() {
    }

    /**
     * @param targets   待判定的检验行，同一件的多行 pieceNo 相同
     * @param acceptQty 允收数 Ac，允许的不合格件数，负数按 0 处理
     */
    public static InspectionOutcome judge(List<JudgeTarget> targets, int acceptQty) {
        int ac = Math.max(acceptQty, 0);

        if (targets == null || targets.isEmpty()) {
            return new InspectionOutcome(List.of(), List.of(), 0, 0, 0, 0, ac, false,
                    "没有任何检验数据，无法判定（不允许空单判合格）");
        }

        // ---------- 第一层 A：逐行判定 ----------
        List<JudgedItem> judged = new ArrayList<>(targets.size());
        Map<Integer, List<Integer>> pieceToIndex = new TreeMap<>();
        for (int i = 0; i < targets.size(); i++) {
            JudgeTarget t = targets.get(i);
            JudgeOutcome o = ItemJudge.judge(t.spec(), t.values(), t.textValue());
            judged.add(new JudgedItem(t.lineId(), t.pieceNo(), t.itemName(), o.pass(), o.reason()));
            pieceToIndex.computeIfAbsent(t.pieceNo(), k -> new ArrayList<>()).add(i);
        }

        // ---------- 第一层 B：逐件判定 ----------
        List<PieceOutcome> pieces = new ArrayList<>(pieceToIndex.size());
        for (Map.Entry<Integer, List<Integer>> e : pieceToIndex.entrySet()) {
            int pieceNo = e.getKey();
            int pieceNg = 0;
            int pieceCritical = 0;
            List<String> ngDetails = new ArrayList<>();
            List<String> criticalDetails = new ArrayList<>();

            for (int idx : e.getValue()) {
                JudgedItem j = judged.get(idx);
                if (j.pass()) {
                    continue;
                }
                pieceNg++;
                String detail = j.itemName() + "（" + j.reason() + "）";
                ngDetails.add(detail);
                if (targets.get(idx).isCritical()) {
                    pieceCritical++;
                    criticalDetails.add(detail);
                }
            }

            String reason;
            if (pieceNg == 0) {
                reason = "全部检验项合格";
            } else if (pieceCritical > 0) {
                reason = "关键项不合格：" + String.join("；", criticalDetails);
            } else {
                reason = "不合格项 " + pieceNg + " 项：" + String.join("；", ngDetails);
            }
            pieces.add(new PieceOutcome(pieceNo, pieceNg == 0, pieceNg, pieceCritical, reason));
        }

        // ---------- 第二层：整批判定 ----------
        int totalNg = 0;
        int totalCritical = 0;
        for (JudgedItem j : judged) {
            if (!j.pass()) {
                totalNg++;
            }
        }
        int failedQty = 0;
        List<Integer> failedPieces = new ArrayList<>();
        Map<Integer, PieceOutcome> pieceMap = new LinkedHashMap<>();
        for (PieceOutcome p : pieces) {
            pieceMap.put(p.pieceNo(), p);
            totalCritical += p.criticalNgCount();
            if (!p.pass()) {
                failedQty++;
                failedPieces.add(p.pieceNo());
            }
        }
        int passedQty = pieces.size() - failedQty;

        String reason;
        boolean pass;
        if (failedQty == 0) {
            pass = true;
            reason = pieces.size() + " 件全部合格";
        } else if (failedQty <= ac) {
            pass = true;
            reason = "不合格 " + failedQty + " 件（第 " + joinNo(failedPieces) + " 件），未超过允收数 " + ac + " 件"
                    + firstCriticalHint(pieceMap, failedPieces);
        } else {
            pass = false;
            reason = "不合格 " + failedQty + " 件（第 " + joinNo(failedPieces) + " 件），超过允收数 " + ac + " 件"
                    + firstCriticalHint(pieceMap, failedPieces);
        }

        return new InspectionOutcome(judged, pieces, totalNg, totalCritical,
                passedQty, failedQty, ac, pass, reason);
    }

    /** 有不合格件因关键项 NG 时，在整批理由里点出来——这是审核时最先被问的一句 */
    private static String firstCriticalHint(Map<Integer, PieceOutcome> pieceMap, List<Integer> failedPieces) {
        String critical = failedPieces.stream()
                .map(pieceMap::get)
                .filter(p -> p != null && p.criticalNgCount() > 0)
                .map(p -> "第 " + p.pieceNo() + " 件 " + p.reason())
                .collect(Collectors.joining("；"));
        return critical.isEmpty() ? "" : "。关键项问题：" + critical;
    }

    private static String joinNo(List<Integer> nos) {
        return nos.stream().map(String::valueOf).collect(Collectors.joining("、"));
    }
}
