package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.QcBackupLog;
import com.qc.inspect.mapper.QcBackupLogMapper;
import com.qc.inspect.report.BackupHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份健康。
 *
 * <p>回答两个问题：现在手上这份库备份有多旧、最近几次跑成什么样。
 * 判定交给 {@link BackupHealth}（纯逻辑、可单测），这里只负责取数和组装。
 */
@Service
@RequiredArgsConstructor
public class BackupHealthService {

    /** 页面上展示的最近执行条数。一天一条，10 条能看到十天，够判断「是不是偶尔才断」 */
    public static final int RECENT_ROWS = 10;

    /** 单次查询上限，防止有人把 limit 传成 100000 拉全表 */
    public static final int MAX_RECENT = 50;

    private final QcBackupLogMapper backupLogMapper;

    public Map<String, Object> health(int recentLimit) {
        int n = Math.max(1, Math.min(recentLimit, MAX_RECENT));

        Map<String, Object> facts = backupLogMapper.facts();
        String lastStatus = str(facts.get("last_status"));
        Integer hoursSinceSuccess = intOrNull(facts.get("hours_since_success"));
        Integer hoursSinceAttempt = intOrNull(facts.get("hours_since_attempt"));

        BackupHealth.Verdict verdict = BackupHealth.judge(lastStatus, hoursSinceSuccess);

        // 判定只说「失败了」，具体原因在 message 里。失败原因不摆在最上面，
        // 看到红灯的人还得再展开一次才知道要修什么
        List<String> flags = new ArrayList<>(verdict.flags());
        String lastMessage = str(facts.get("last_message"));
        if (BackupHealth.LEVEL_FAILED.equals(verdict.level())
                && lastMessage != null && !lastMessage.isBlank()) {
            String reason = lastMessage.length() > 300 ? lastMessage.substring(0, 300) + "…" : lastMessage;
            flags.add(Math.min(1, flags.size()), "失败原因：" + reason);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", verdict.level());
        m.put("alert", verdict.alert());
        m.put("flags", flags);

        m.put("lastStatus", lastStatus);
        m.put("lastSuccessAt", facts.get("last_success_at"));
        m.put("lastAttemptAt", facts.get("last_attempt_at"));
        m.put("lastMessage", facts.get("last_message"));
        m.put("lastHost", facts.get("last_host"));
        m.put("hoursSinceSuccess", hoursSinceSuccess == null ? null : Math.max(0, hoursSinceSuccess));
        m.put("hoursSinceAttempt", hoursSinceAttempt == null ? null : Math.max(0, hoursSinceAttempt));
        m.put("lastSuccessMb", megaBytes(facts.get("last_success_size")));
        m.put("lastSuccessSizeText", sizeText(facts.get("last_success_size")));

        m.put("runs30d", longOf(facts.get("runs_30d")));
        m.put("failures30d", longOf(facts.get("failures_30d")));
        m.put("totalRuns", longOf(facts.get("total_runs")));

        List<Map<String, Object>> recent = new ArrayList<>(n);
        for (QcBackupLog log : recent(n)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", log.getId());
            row.put("status", log.getStatus());
            row.put("fileName", log.getFileName());
            row.put("sizeMb", megaBytes(log.getFileSize()));
            row.put("sizeText", sizeText(log.getFileSize()));
            row.put("prunedFiles", log.getPrunedFiles());
            row.put("host", log.getHost());
            row.put("message", log.getMessage());
            row.put("createdAt", log.getCreatedAt());
            recent.add(row);
        }
        m.put("recent", recent);
        return m;
    }

    public List<QcBackupLog> recent(int limit) {
        return backupLogMapper.selectList(new LambdaQueryWrapper<QcBackupLog>()
                .orderByDesc(QcBackupLog::getCreatedAt)
                .orderByDesc(QcBackupLog::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 人类可读的体积。空库备份只有十几 KB，一律按 MB 显示会变成「0.0 MB」——
     * 页面上看着像「没备份成」，实际是有的。所以小于 1 MB 报 KB。
     */
    static String sizeText(Object bytes) {
        if (!(bytes instanceof Number num)) {
            return null;
        }
        long b = num.longValue();
        if (b < 1024) {
            return b + " B";
        }
        // 先按 KB 取整再判量级：不然 1048575 字节会报成「1024 KB」，
        // 差一个字节却换了个更长的说法
        long kb = Math.round(b / 1024.0);
        if (kb < 1024) {
            return kb + " KB";
        }
        return (Math.round(b / 104857.6) / 10.0) + " MB";
    }

    /** 字节转 MB，保留一位小数。备份文件用 KB 表示时看不出量级变化（1200 KB 和 340 KB 差着三倍） */
    private static Double megaBytes(Object bytes) {
        if (!(bytes instanceof Number num)) {
            return null;
        }
        // 先放大 10 倍再取整，等于四舍五入到 1 位小数：bytes / (1048576 / 10)
        return Math.round(num.longValue() / 104857.6) / 10.0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number num ? num.intValue() : null;
    }

    private static long longOf(Object o) {
        return o instanceof Number num ? num.longValue() : 0L;
    }
}
