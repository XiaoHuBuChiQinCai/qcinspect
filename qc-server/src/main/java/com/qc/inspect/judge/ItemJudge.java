package com.qc.inspect.judge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单项判定器，对应四种数据类型。
 *
 * <p>设计原则：判定逻辑与持久化、框架完全解耦，纯静态方法，便于单测与复用。
 * 前端录入的原始字符串直接传入，这里负责解析与校验，避免脏数据被静默放过。
 */
public final class ItemJudge {

    private ItemJudge() {
    }

    /**
     * 判定单个检验项。
     *
     * @param spec       判定配置（来自检验明细快照）
     * @param values     实测值列表，单值项传 1 个，多点项传 pointCount 个
     * @param textValue  文本型录入内容，其余类型忽略
     */
    public static JudgeOutcome judge(ItemSpec spec, List<String> values, String textValue) {
        if (spec == null || spec.valueType() == null) {
            return JudgeOutcome.ng("检验项配置缺失");
        }
        if (spec.valueType() == ValueType.TEXT) {
            return JudgeOutcome.ok();
        }

        List<String> safeValues = values == null ? List.of() : values;

        // 漏填是最常见的场景，先单独给一句能看懂的说明，别让人对着一句「数量不符」猜
        boolean blank = safeValues.isEmpty()
                || safeValues.stream().allMatch(v -> v == null || v.isBlank());
        if (blank) {
            return JudgeOutcome.ng("未录入实测值");
        }

        int expected = spec.expectedPointCount();
        if (safeValues.size() != expected) {
            return JudgeOutcome.ng("测点数量不符，应为 " + expected + " 个，实际 " + safeValues.size() + " 个");
        }

        return switch (spec.valueType()) {
            case NUMBER -> judgeNumber(spec, safeValues);
            case ENUM -> judgeEnum(spec, safeValues);
            case BOOL -> judgeBool(spec, safeValues);
            case TEXT -> JudgeOutcome.ok();
        };
    }

    // ---------------- 数值型 ----------------

    private static JudgeOutcome judgeNumber(ItemSpec spec, List<String> values) {
        BigDecimal min = spec.minValue();
        BigDecimal max = spec.maxValue();
        if (min == null && max == null) {
            // 数值项未配置限值，属于标准维护疏漏，不阻断判定但留痕
            return JudgeOutcome.ok("未配置限值，仅记录");
        }
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String raw = values.get(i);
            BigDecimal v;
            try {
                v = new BigDecimal(raw == null ? "" : raw.trim());
            } catch (NumberFormatException e) {
                return JudgeOutcome.ng("第 " + (i + 1) + " 点不是有效数字：" + raw);
            }
            if (min != null && v.compareTo(min) < 0) {
                failed.add("第 " + (i + 1) + " 点 " + fmt(v) + " < " + fmt(min));
            } else if (max != null && v.compareTo(max) > 0) {
                failed.add("第 " + (i + 1) + " 点 " + fmt(v) + " > " + fmt(max));
            }
        }
        if (!failed.isEmpty()) {
            return JudgeOutcome.ng(String.join("；", failed));
        }
        return JudgeOutcome.ok();
    }

    // ---------------- 枚举型 ----------------

    private static JudgeOutcome judgeEnum(ItemSpec spec, List<String> values) {
        List<EnumOption> options = spec.options() == null ? List.of() : spec.options();
        if (options.isEmpty()) {
            return JudgeOutcome.ng("枚举项未配置选项");
        }
        for (int i = 0; i < values.size(); i++) {
            String code = values.get(i);
            EnumOption hit = options.stream()
                    .filter(o -> o.code().equalsIgnoreCase(code == null ? "" : code.trim()))
                    .findFirst()
                    .orElse(null);
            if (hit == null) {
                return JudgeOutcome.ng("第 " + (i + 1) + " 点选项不存在：" + code);
            }
            if (!hit.pass()) {
                return JudgeOutcome.ng("第 " + (i + 1) + " 点判定为不合格：" + hit.name());
            }
        }
        return JudgeOutcome.ok();
    }

    // ---------------- 布尔型 ----------------

    private static JudgeOutcome judgeBool(ItemSpec spec, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            Boolean b = parseBool(values.get(i));
            if (b == null) {
                return JudgeOutcome.ng("第 " + (i + 1) + " 点无法识别的值：" + values.get(i));
            }
            if (b != spec.boolPassValue()) {
                return JudgeOutcome.ng("第 " + (i + 1) + " 点判定为不合格");
            }
        }
        return JudgeOutcome.ok();
    }

    private static Boolean parseBool(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "y", "yes", "是", "有" -> Boolean.TRUE;
            case "false", "0", "n", "no", "否", "无" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String fmt(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
