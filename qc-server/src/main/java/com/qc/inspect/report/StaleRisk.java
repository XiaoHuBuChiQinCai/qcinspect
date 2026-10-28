package com.qc.inspect.report;

/**
 * 「按已作废的标准版本下的结论」到底值不值得复查。
 *
 * <p>只说「有 42 张单是按旧版标准判的」是没有用的：42 张里可能一张都不用管。
 * 真正决定要不要动手的，是<strong>标准后来往哪个方向改了</strong>——
 * <ul>
 *   <li>旧版宽松、现行收紧，而这批单判了合格：放行的东西按现在的标准可能不合格，
 *       这是合规风险，也是唯一真正要抢着看的一种</li>
 *   <li>旧版严格、现行放宽，而这批单判了不合格：按现行标准可能本来是合格的，
 *       返工和报废白做了。不影响出厂合规，但那是钱</li>
 *   <li>旧版判的全是 NG：已经拦住了，标准怎么改都不改变结论</li>
 *   <li>两版没实质差异：连看都不用看</li>
 * </ul>
 *
 * <p>所以「有多少张单」是数量，「风险是什么」才是能不能排优先级。两者分开算：
 * 数量由 SQL 聚合，风险在这里判——纯函数，能被单测逐条钉死。
 *
 * <p>这里只做提示，绝不参与判定：判定永远按单据当时的标准快照来，
 * 结论一旦作出就是质量记录，不会因为标准改版而变化。
 */
public final class StaleRisk {

    /** 放行结论可能站不住：旧版宽松、现行收紧，且有判合格的单 */
    public static final String HIGH = "HIGH";
    /** 值得看一眼：方向不明，或者方向明确但影响的是成本不是合规 */
    public static final String WATCH = "WATCH";
    /** 不用管：标准怎么改都不改变这批结论 */
    public static final String LOW = "LOW";
    /** 判不了：该产品已无生效版本，或标准内容读不出来 */
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * 一个判定结果。
     *
     * @param level   {@link #HIGH}/{@link #WATCH}/{@link #LOW}/{@link #UNKNOWN}
     * @param text    两三个字的档位名，列表里直接当标签用
     * @param advice  一句人话，说清「要做什么」而不是「发生了什么」
     * @param order   排序权重，越小越靠前——工作台第一行就该是最该看的
     */
    public record Verdict(String level, String text, String advice, int order) {}

    private StaleRisk() {}

    /**
     * @param changeVerdict 旧版到现行版的方向，取值同 {@link StandardDiff}；
     *                      {@code null} 表示拿不到对比（该产品已无生效版本等）
     * @param passCount     这一版下判合格的单数
     * @param failCount     这一版下判不合格的单数
     */
    public static Verdict judge(String changeVerdict, long passCount, long failCount) {
        if (changeVerdict == null || changeVerdict.isBlank()) {
            return new Verdict(UNKNOWN, "无法判断",
                    "这个产品已经没有生效版本，或读不到现行标准的内容，只能人工核对", 2);
        }
        if (passCount <= 0 && failCount <= 0) {
            // HAVING count > 0 已经挡掉了，这里兜底：没有单据就没有风险，
            // 但文案不能写成「全部合格」——那是另一回事
            return new Verdict(LOW, "无影响", "这一版下没有已判定单据", 3);
        }
        return switch (changeVerdict) {
            case StandardDiff.TIGHTENED -> passCount > 0
                    ? new Verdict(HIGH, "合规风险",
                            "标准在这批单判定之后收紧了。判合格的那部分是按更宽松的旧口径放的，"
                                    + "按现行标准可能不合格——优先抽查关键尺寸和参数", 0)
                    : new Verdict(LOW, "已全部拦下",
                            "这批判定全是不合格，标准收紧不改变结论，不用复查", 3);
            case StandardDiff.RELAXED -> failCount > 0
                    ? new Verdict(WATCH, "可能误判",
                            "标准后来放宽了。判不合格的那部分按现行标准可能本来就合格，"
                                    + "涉及返工或报废的可以复核，能挽回成本", 1)
                    : new Verdict(LOW, "无影响",
                            "标准后来放宽，而这批判定全部合格，放宽不影响这批结论", 3);
            case StandardDiff.MIXED, StandardDiff.CHANGED -> new Verdict(WATCH, "方向不一",
                    "这一版到现行版既有放宽也有收紧（或改的不是宽严），一句话说不清，"
                            + "要按项核对差异再决定查哪几张", 1);
            case StandardDiff.NEUTRAL -> new Verdict(LOW, "不受影响",
                    "现行标准和这一版没有实质差异，这批结论不用复查", 3);
            default -> new Verdict(UNKNOWN, "无法判断", "改版方向识别不出来，只能人工核对", 2);
        };
    }
}
