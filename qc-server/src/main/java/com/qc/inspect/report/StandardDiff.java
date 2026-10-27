package com.qc.inspect.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.judge.EnumOption;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检验标准两个版本之间的差异。
 *
 * <p>存在理由是质量问题发生后的第一句话：「是不是标准被放宽了？」
 * 标准改版走的是「新增版本 + 旧版置 OBSOLETE」，两版都还在库里，差量随时可算，
 * 但此前没人算——只能两个页面来回翻，靠眼睛比。
 *
 * <p>所以这里不只是列出「变了什么」，还要判<strong>方向</strong>：
 * 上限调高是放宽，下限调高是收紧，关键项从「是」改成「否」也是放宽
 * （原本任意一件超差就判死整批，改完 Ac 能吞掉了）。
 * 方向判错的后果比不判还糟：它会让人以为问题出在别处。
 */
public final class StandardDiff {

    /** 有放宽、没有收紧 */
    public static final String RELAXED = "RELAXED";
    /** 有收紧、没有放宽 */
    public static final String TIGHTENED = "TIGHTENED";
    /** 放宽与收紧同时存在，净效果要看具体项 */
    public static final String MIXED = "MIXED";
    /** 改了，但改的不是宽严——比如布尔项合格方向反转、枚举合格选项换了一批 */
    public static final String CHANGED = "CHANGED";
    /** 没实质变更 */
    public static final String NEUTRAL = "NEUTRAL";

    public static final String LOOSEN = "LOOSEN";
    public static final String TIGHTEN = "TIGHTEN";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一条差异。
     *
     * @param field    英文键，前端用来上图标
     * @param label    中文标签，直接给人看
     * @param direction {@link #LOOSEN} 放宽 / {@link #TIGHTEN} 收紧
     */
    public record Change(String itemCode, String itemName, String field, String label,
                         String before, String after, String direction) {

        /** 一句话，用在摘要里 */
        public String text() {
            String who = itemName == null || itemName.isBlank() ? itemCode : itemName;
            String head = (who == null || who.isBlank()) ? label : who + " " + label;
            return head + " " + (before == null ? "—" : before)
                    + " → " + (after == null ? "—" : after)
                    + "（" + (LOOSEN.equals(direction) ? "放宽" : "收紧") + "）";
        }
    }

    public record Result(String verdict, String verdictText, String advice,
                         List<Change> changes, List<String> summary) {
    }

    private StandardDiff() {
    }

    /**
     * 比对两个版本。
     *
     * @param itemIndex itemId → 检验项主数据，用来取编码名称、以及在标准项
     *                  {@code optionsJson} / {@code boolPassValue} 为空时解析出实际生效值。
     *                  传空 Map 也能跑，只是显示成「项#12」。
     */
    public static Result compare(QcStandard before, QcStandard after,
                                 List<QcStandardItem> beforeItems, List<QcStandardItem> afterItems,
                                 Map<Long, QcItem> itemIndex) {
        List<Change> changes = new ArrayList<>();
        compareHeader(changes, before, after);

        Map<Long, QcStandardItem> oldByItem = index(beforeItems);
        Map<Long, QcStandardItem> newByItem = index(afterItems);

        // 顺序按新版配置走，再补上被删掉的——同一份标准两次对比，行序必须一样，
        // 否则人眼每次都要重新定位
        Set<Long> order = new LinkedHashSet<>();
        afterItems.stream().map(QcStandardItem::getItemId).filter(Objects::nonNull).forEach(order::add);
        beforeItems.stream().map(QcStandardItem::getItemId).filter(Objects::nonNull).forEach(order::add);

        for (Long itemId : order) {
            QcItem item = itemIndex.get(itemId);
            QcStandardItem oldRow = oldByItem.get(itemId);
            QcStandardItem newRow = newByItem.get(itemId);
            String code = item == null ? null : item.getItemCode();
            String name = item == null ? ("项#" + itemId) : item.getItemName();

            if (oldRow == null) {
                // 新增检验项 = 多一道关 = 收紧。删掉一项就是少检一样东西，这是放宽
                changes.add(new Change(code, name, "added", "新增检验项",
                        null, null, TIGHTEN));
                continue;
            }
            if (newRow == null) {
                changes.add(new Change(code, name, "removed", "删除检验项",
                        null, null, LOOSEN));
                continue;
            }
            compareItem(changes, code, name, item, oldRow, newRow);
        }

        List<String> summary = changes.stream().map(Change::text).toList();
        return new Result(verdict(changes), verdictText(changes), advice(changes), changes, summary);
    }

    // ==================== 表头：抽样与允收 ====================

    private static void compareHeader(List<Change> out, QcStandard before, QcStandard after) {
        if (before == null || after == null) {
            return;
        }

        // 抽样方式：全检 → 抽检是放宽，反过去是收紧，改成免检是最大的放宽
        String oldType = nz(before.getSamplingType(), "FULL");
        String newType = nz(after.getSamplingType(), "FULL");
        int oldRank = samplingRank(oldType);
        int newRank = samplingRank(newType);
        if (oldRank != newRank) {
            out.add(new Change(null, "整份标准", "samplingType", "抽样方式",
                    samplingText(oldType), samplingText(newType),
                    newRank < oldRank ? LOOSEN : TIGHTEN));
        }

        boolean oldAql = "AQL".equals(nz(before.getSamplingPlan(), ""));
        boolean newAql = "AQL".equals(nz(after.getSamplingPlan(), ""));

        if (oldAql != newAql) {
            // 固定抽样 ↔ AQL 查表，样本量和 Ac 从此不再是标准上的数字，没法放到同一条线上比
            out.add(new Change(null, "整份标准", "samplingPlan", "抽样方案",
                    oldAql ? "AQL 按批量查表" : "固定抽样",
                    newAql ? "AQL 按批量查表" : "固定抽样", CHANGED));
            return;
        }

        if (oldAql) {
            // AQL 值越大越宽松（同批量下 Ac 更大），水平 I < II < III 越靠后越严
            BigDecimal oldAqlVal = before.getAql();
            BigDecimal newAqlVal = after.getAql();
            if (numChanged(oldAqlVal, newAqlVal)) {
                out.add(new Change(null, "整份标准", "aql", "AQL 值",
                        plain(oldAqlVal), plain(newAqlVal),
                        newAqlVal.compareTo(oldAqlVal) > 0 ? LOOSEN : TIGHTEN));
            }
            String oldLevel = nz(before.getInspectionLevel(), "II");
            String newLevel = nz(after.getInspectionLevel(), "II");
            if (!oldLevel.equals(newLevel)) {
                int oldLv = levelRank(oldLevel);
                int newLv = levelRank(newLevel);
                out.add(new Change(null, "整份标准", "inspectionLevel", "检验水平",
                        oldLevel, newLevel, newLv < oldLv ? LOOSEN : TIGHTEN));
            }
        } else {
            // 只有两边都是抽检时，抽样件数才可比：全检的 sampleSize 恒为 1 是占位值，
            // 拿「全检(1)」和「抽检(8 件)」比会得出「改成抽检反而更严」的荒谬结论
            Integer oldSize = before.getSampleSize();
            Integer newSize = after.getSampleSize();
            if ("SAMPLE".equals(oldType) && "SAMPLE".equals(newType)
                    && !Objects.equals(oldSize, newSize) && oldSize != null && newSize != null) {
                out.add(new Change(null, "整份标准", "sampleSize", "抽样件数",
                        oldSize + " 件", newSize + " 件",
                        newSize < oldSize ? LOOSEN : TIGHTEN));
            }
        }

        Integer oldAc = before.getAllowedNgCount();
        Integer newAc = after.getAllowedNgCount();
        if (!Objects.equals(oldAc, newAc) && oldAc != null && newAc != null) {
            out.add(new Change(null, "整份标准", "allowedNgCount", "允收数 Ac",
                    oldAc + " 件", newAc + " 件", newAc > oldAc ? LOOSEN : TIGHTEN));
        }
    }

    /** 免检 &lt; 抽检 &lt; 全检，等级越高越严 */
    private static int samplingRank(String type) {
        return switch (type) {
            case "NONE" -> 0;
            case "SAMPLE" -> 1;
            default -> 2;
        };
    }

    /** 一般检验水平越靠后越严：S-1 &lt; S-2 &lt; … &lt; II &lt; III */
    private static int levelRank(String level) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("S-1", 0);
        ranks.put("S-2", 1);
        ranks.put("S-3", 2);
        ranks.put("S-4", 3);
        ranks.put("I", 4);
        ranks.put("II", 5);
        ranks.put("III", 6);
        return ranks.getOrDefault(level, 5);
    }

    private static String samplingText(String type) {
        return switch (type) {
            case "NONE" -> "免检";
            case "SAMPLE" -> "抽检";
            default -> "全检";
        };
    }

    // ==================== 单个检验项 ====================

    private static void compareItem(List<Change> out, String code, String name, QcItem item,
                                    QcStandardItem oldRow, QcStandardItem newRow) {
        // 上限调高 = 能测的值更多 = 放宽；下限调低同理。
        // 一边有值一边为空时：多出一个限值就是收紧，少一个就是放宽
        addNum(out, code, name, "maxValue", "上限", oldRow.getMaxValue(), newRow.getMaxValue(),
                newRow.getMaxValue() != null && oldRow.getMaxValue() != null
                        && newRow.getMaxValue().compareTo(oldRow.getMaxValue()) > 0);
        addNum(out, code, name, "minValue", "下限", oldRow.getMinValue(), newRow.getMinValue(),
                newRow.getMinValue() != null && oldRow.getMinValue() != null
                        && newRow.getMinValue().compareTo(oldRow.getMinValue()) < 0);

        // 关键项：改成「否」之后，超差不再判死整批，Ac 能吞掉——这是实打实的放宽
        addBool(out, code, name, "isCritical", "关键项", oldRow.getIsCritical(), newRow.getIsCritical());
        addBool(out, code, name, "isRequired", "必填项", oldRow.getIsRequired(), newRow.getIsRequired());

        Integer oldPoint = oldRow.getPointCount();
        Integer newPoint = newRow.getPointCount();
        if (!Objects.equals(oldPoint, newPoint) && oldPoint != null && newPoint != null) {
            out.add(new Change(code, name, "pointCount", "测点数",
                    oldPoint + " 点", newPoint + " 点", newPoint < oldPoint ? LOOSEN : TIGHTEN));
        }

        // 破坏性检验改成非破坏性：原来要报废的现在不报废了，属于放宽
        addBool(out, code, name, "isDestructive", "破坏性检验",
                oldRow.getIsDestructive(), newRow.getIsDestructive());

        // 布尔项合格方向反转：不是宽也不是严，而是「同一份实测数据结论反过来」。
        // 这种改动最危险，必须单独标出来，不能混进放宽/收紧里
        String oldBool = nz(oldRow.getBoolPassValue(), item == null ? null : item.getBoolPassValue());
        String newBool = nz(newRow.getBoolPassValue(), item == null ? null : item.getBoolPassValue());
        if (!Objects.equals(oldBool, newBool) && (oldBool != null || newBool != null)) {
            out.add(new Change(code, name, "boolPassValue", "布尔项合格值",
                    boolText(oldBool), boolText(newBool), CHANGED));
        }

        // 枚举项：只比合格集合。合格选项少了是收紧，多了是放宽；
        // 集合一样只是改了措辞（选项改名）就不算实质变更
        if (item != null && "ENUM".equals(item.getValueType())) {
            Set<String> oldPass = passCodes(oldRow.getOptionsJson(), item);
            Set<String> newPass = passCodes(newRow.getOptionsJson(), item);
            if (!oldPass.equals(newPass)) {
                boolean onlyAdded = newPass.containsAll(oldPass);
                boolean onlyRemoved = oldPass.containsAll(newPass);
                String direction = onlyAdded ? LOOSEN : onlyRemoved ? TIGHTEN : CHANGED;
                out.add(new Change(code, name, "enumPass", "枚举合格选项",
                        String.join("、", oldPass.isEmpty() ? Set.of("无") : oldPass),
                        String.join("、", newPass.isEmpty() ? Set.of("无") : newPass),
                        direction));
            }
        }
    }

    private static void addNum(List<Change> out, String code, String name, String field, String label,
                               BigDecimal before, BigDecimal after, boolean looser) {
        if (!numChanged(before, after)) {
            return;
        }
        // 上限/下限本身的方向由调用方算好传进来；这里只处理「一边为空」的两种情形
        String direction;
        if (before == null || after == null) {
            direction = after == null ? LOOSEN : TIGHTEN;
        } else {
            direction = looser ? LOOSEN : TIGHTEN;
        }
        out.add(new Change(code, name, field, label, plain(before), plain(after), direction));
    }

    private static void addBool(List<Change> out, String code, String name, String field, String label,
                                Boolean before, Boolean after) {
        boolean b = Boolean.TRUE.equals(before);
        boolean a = Boolean.TRUE.equals(after);
        if (b == a) {
            return;
        }
        // 「是」代表更严格的要求：关键、必填、破坏性。所以变 false 就是放宽
        out.add(new Change(code, name, field, label, yesNo(b), yesNo(a), a ? TIGHTEN : LOOSEN));
    }

    // ==================== 判定 ====================

    private static String verdict(List<Change> changes) {
        boolean loosen = changes.stream().anyMatch(c -> LOOSEN.equals(c.direction()));
        boolean tighten = changes.stream().anyMatch(c -> TIGHTEN.equals(c.direction()));
        if (!loosen && !tighten) {
            return changes.isEmpty() ? NEUTRAL : CHANGED;
        }
        if (loosen && tighten) {
            return MIXED;
        }
        return loosen ? RELAXED : TIGHTENED;
    }

    private static String verdictText(List<Change> changes) {
        return switch (verdict(changes)) {
            case RELAXED -> "整体放宽";
            case TIGHTENED -> "整体收紧";
            case MIXED -> "有放宽也有收紧";
            case CHANGED -> "改了判定方向，无法按宽严归类";
            default -> "无实质变更";
        };
    }

    private static String advice(List<Change> changes) {
        return switch (verdict(changes)) {
            case RELAXED -> "这一版比上一版更宽松。若近期不良流出是在这版之后开始的，优先查这一版";
            case TIGHTENED -> "这一版更严，按新版判定会出现原本合格的产品被判不合格——先确认测量能力和供应商能不能做到";
            case MIXED -> "同一版里既放宽又收紧了，净效果要逐项看，不能只看结论标签";
            case CHANGED -> "改的不是宽严，而是判定方向（布尔合格值或枚举合格选项）。同一份实测数据在新版下结论可能完全相反，历史数据不能直接拿来比";
            default -> "两版在判定口径上没有实质差别";
        };
    }

    // ==================== 小工具 ====================

    private static Map<Long, QcStandardItem> index(List<QcStandardItem> items) {
        Map<Long, QcStandardItem> m = new LinkedHashMap<>();
        if (items != null) {
            for (QcStandardItem si : items) {
                if (si != null && si.getItemId() != null) {
                    m.put(si.getItemId(), si);
                }
            }
        }
        return m;
    }

    /**
     * BigDecimal 必须用 compareTo 比。
     *
     * <p>{@code equals} 会把 12.0 和 12.00 判成不同——从 Excel 粘进来的限值
     * 小数位经常不统一，用 equals 会造出一堆「12.00 → 12.00 变了」的假差异，
     * 真差异就被淹掉了。
     */
    private static boolean numChanged(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) {
            return before != after;
        }
        return before.compareTo(after) != 0;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String yesNo(Boolean b) {
        return Boolean.TRUE.equals(b) ? "是" : "否";
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String nz(Boolean b, Boolean fallback) {
        Boolean v = b != null ? b : fallback;
        return v == null ? null : v.toString();
    }

    private static String boolText(String raw) {
        if (raw == null) {
            return "沿用主数据";
        }
        return "true".equals(raw) ? "录「有」算合格" : "录「无」算合格";
    }

    /** 合格选项编码集合。标准项没配就用主数据的 —— 判定层就是这么取的 */
    private static Set<String> passCodes(String optionsJson, QcItem item) {
        String json = optionsJson != null && !optionsJson.isBlank() ? optionsJson : item.getOptionsJson();
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            EnumOption[] arr = JSON.readValue(json, EnumOption[].class);
            if (arr == null) {
                return Set.of();
            }
            return java.util.Arrays.stream(arr)
                    .filter(EnumOption::pass)
                    .map(EnumOption::name)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            // 选项 JSON 坏了不该让整个对比 500——对比页还是得能打开，坏的那项显示成空集合
            return Set.of();
        }
    }
}
