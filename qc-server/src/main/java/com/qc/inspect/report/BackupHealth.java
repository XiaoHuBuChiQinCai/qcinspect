package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份健康分级。
 *
 * <p>备份是那种「做对了一百次没人记得，漏一次就全完」的东西。它有两种坏法：
 * 一是明确报错（脚本退出码非 0），二是<strong>安静地不再产生新备份</strong>
 * （timer 被停、机器换了、磁盘满了、pg_dump 版本对不上）——后者更常见，
 * 而且从外观看和正常一模一样，直到真的需要恢复那天。
 *
 * <p>所以这里判的不是「上一次跑成功了吗」，而是「现在手上这份备份有多旧」。
 * 明确了这一点，判定就不用猜了：每天 02:00 一次的计划，超过 36 小时没有成功记录
 * 意味着至少漏了一次，超过 72 小时意味着连漏两次。
 *
 * <p>为什么 FAILED 优先于「上次成功很新鲜」：单纯的失败本身也要人去看。
 * 今天的备份成功了、下午手动跑一次失败，数据是安全的，但脚本已经坏了——
 * 不管的话明天凌晨就是真断了。所以照样报 FAILED，只是文案里点明现有备份仍然可用，
 * 免得有人半夜被叫起来以为数据没了。
 */
public final class BackupHealth {

    /** 正常：距上次成功不足 36 小时 */
    public static final String LEVEL_OK = "OK";
    /** 已错过一次：36~72 小时没有成功记录 */
    public static final String LEVEL_STALE = "STALE";
    /** 连续错过多次：72 小时以上没有成功记录，按故障处理 */
    public static final String LEVEL_OVERDUE = "OVERDUE";
    /** 最近一次执行失败 */
    public static final String LEVEL_FAILED = "FAILED";
    /** 没有任何记录。多半是刚接上脚本，也可能是记录写不进库 */
    public static final String LEVEL_NO_RECORD = "NO_RECORD";

    /**
     * 36 小时：计划每天 02:00 一次，超过一天半说明已经漏掉了一整轮。
     * 卡在 24 小时会让「今早的备份还没跑」（凌晨 1 点打开页面）就报警，
     * 那种天天误报的灯没人会看。
     */
    public static final int STALE_HOURS = 36;

    /** 72 小时：连漏两轮，这时候不能再叫「提醒」了 */
    public static final int OVERDUE_HOURS = 72;

    private BackupHealth() {
    }

    /**
     * @param lastStatus         最近一次执行的状态，没有记录传 {@code null}
     * @param hoursSinceSuccess  距最近一次成功的小时数，从未成功过传 {@code null}
     */
    public static Verdict judge(String lastStatus, Integer hoursSinceSuccess) {
        if (lastStatus == null) {
            return new Verdict(LEVEL_NO_RECORD,
                    List.of("还没有任何备份执行记录。若刚接上备份脚本，等今晚 02:00 跑完再看；"
                            + "若已经配了很久，说明脚本根本没在跑，或者结果写不进库"),
                    false);
        }

        // 时钟漂移时 hours 可能是负数（应用机器比库快，或者写的是未来时间），
        // 负数一律当 0：宁可说「刚刚备份过」，也不要显示「-3 小时前」
        Integer hours = hoursSinceSuccess == null ? null : Math.max(0, hoursSinceSuccess);

        if (LEVEL_FAILED.equals(lastStatus)) {
            List<String> flags = new ArrayList<>(2);
            flags.add("最近一次备份失败");
            if (hours == null) {
                flags.add("而且从来没成功过一次——现在库里没有任何可用备份");
            } else if (hours < STALE_HOURS) {
                flags.add("距上次成功只有 " + human(hours) + "，现有备份还能用；"
                        + "但失败原因必须查清，否则明天凌晨就真断了");
            } else {
                flags.add("距上次成功已 " + human(hours) + "，已超出安全窗口，需要尽快人工补一次");
            }
            return new Verdict(LEVEL_FAILED, List.copyOf(flags), true);
        }

        if (hours == null) {
            // 有执行记录却拿不到成功时间。正常流程里不会出现（SUCCESS 行必然带 created_at），
            // 出现就说明数据不对劲，保守按超期处理而不是放过
            return new Verdict(LEVEL_OVERDUE,
                    List.of("有备份记录，却读不到任何一次成功的时间——记录本身可疑，按超期处理"), true);
        }

        if (hours < STALE_HOURS) {
            return new Verdict(LEVEL_OK,
                    List.of("最近一次成功备份在 " + human(hours) + "前"), false);
        }
        if (hours < OVERDUE_HOURS) {
            return new Verdict(LEVEL_STALE,
                    List.of("距上次成功备份已 " + human(hours) + "，按每天 02:00 的计划，"
                            + "应该已经跑过一轮却没留下成功记录"), true);
        }
        return new Verdict(LEVEL_OVERDUE,
                List.of("距上次成功备份已 " + human(hours) + "，连续错过多次定时任务。"
                        + "这不是提醒，是故障：请先确认 timer 是否还在，再手工跑一次备份"), true);
    }

    /** 排序权重：越小越该先看。页面要按这个顺序排队时才不会把「正常」摆在「故障」前面 */
    public static int rank(String level) {
        return switch (level) {
            case LEVEL_FAILED -> 0;
            case LEVEL_OVERDUE -> 1;
            case LEVEL_STALE -> 2;
            case LEVEL_NO_RECORD -> 3;
            default -> 4;
        };
    }

    /**
     * 人话版时长。48 小时以内报小时，再长换算成天——
     * 「已 168 小时」没人会立刻反应出那是一周。
     */
    static String human(int hours) {
        if (hours < 1) {
            return "不到 1 小时";
        }
        if (hours < 48) {
            return hours + " 小时";
        }
        return (hours / 24) + " 天";
    }

    /**
     * @param level  五档取一，见常量
     * @param flags  给人看的判断，按严重度排列，可直接展示
     * @param alert  是否需要占据页面上的醒目位置
     */
    public record Verdict(String level, List<String> flags, boolean alert) {
    }
}
