package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.QcImageMapper;
import com.qc.inspect.mapper.SysUserMapper;
import com.qc.inspect.mapper.SystemAlertMapper;
import com.qc.inspect.mapper.SystemStatMapper;
import com.qc.inspect.report.HumanSize;
import com.qc.inspect.report.StorageHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘与容量。
 *
 * <p>备份健康回答「数据还在不在」，这里回答「明天还写得进去吗」。两件事都属于
 * 「坏了不会自己喊」，所以放在同一处、同一个页面角上，让管服务器的人一次看完。
 *
 * <p>取数之外只做两件事：把纯逻辑判定（{@link StorageHealth}）组装成页面要的形状，
 * 以及每天早上检查一次、真出问题时推给管理员。判定本身不在这里写，
 * 因为「剩多少算危险」这种规则必须能被单测钉住。
 */
@Slf4j
@Service
public class StorageHealthService {

    /**
     * 估「还能撑几天」的窗口。
     *
     * <p>30 天能盖住一次完整的月生产周期，同时不会长到把「上个月突击拍了两千张」
     * 摊薄成看不见。窗口太短则遇到停产周会得出「永远撑得住」。
     */
    public static final int GROWTH_DAYS = 30;

    /** 告警去重用的键。分级不同算不同告警：从 WARN 恶化到 CRITICAL 要再喊一次 */
    private static final String ALERT_KEY = "STORAGE";

    private final String localDir;
    private final String storageType;
    private final SystemStatMapper statMapper;
    private final QcImageMapper imageMapper;
    private final SystemAlertMapper alertMapper;
    private final SysUserMapper userMapper;
    private final NoticeService noticeService;

    public StorageHealthService(@Value("${qc.storage.local-dir:./data/files}") String localDir,
                                @Value("${qc.storage.type:local}") String storageType,
                                SystemStatMapper statMapper,
                                QcImageMapper imageMapper,
                                SystemAlertMapper alertMapper,
                                SysUserMapper userMapper,
                                NoticeService noticeService) {
        this.localDir = localDir;
        this.storageType = storageType;
        this.statMapper = statMapper;
        this.imageMapper = imageMapper;
        this.alertMapper = alertMapper;
        this.userMapper = userMapper;
        this.noticeService = noticeService;
    }

    public Map<String, Object> health() {
        Disk disk = disk();
        long dbBytes = 0;
        try {
            dbBytes = statMapper.databaseSize();
        } catch (Exception e) {
            log.warn("读不到数据库大小: {}", e.getMessage());
        }

        Map<String, Object> img = new LinkedHashMap<>();
        try {
            img = imageMapper.storageStats(GROWTH_DAYS);
        } catch (Exception e) {
            log.warn("读不到照片占用: {}", e.getMessage());
        }
        long count = num(img.get("image_count"));
        long imageBytes = num(img.get("image_bytes"));
        long recentBytes = num(img.get("recent_bytes"));

        StorageHealth.Verdict v = StorageHealth.judge(new StorageHealth.Facts(
                disk.totalBytes(), disk.usableBytes(), dbBytes,
                count, imageBytes, recentBytes, GROWTH_DAYS));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", v.level());
        m.put("alert", v.alert());
        m.put("flags", v.flags());

        // 监控的是哪个路径必须写出来。容器里挂错卷时，页面显示「还有 40 GB」
        // 而真实照片盘已经满了——这种「看着一切正常」的错最难查
        m.put("path", disk.path());
        m.put("storageType", storageType);

        m.put("totalBytes", disk.totalBytes());
        m.put("usableBytes", Math.max(0, disk.usableBytes()));
        m.put("usedBytes", v.usedBytes());
        m.put("totalText", HumanSize.of(disk.totalBytes()));
        m.put("usableText", HumanSize.of(Math.max(0, disk.usableBytes())));
        m.put("usedText", HumanSize.of(v.usedBytes()));
        m.put("usedPercent", v.usedPercent());

        m.put("dbBytes", dbBytes);
        m.put("dbText", HumanSize.of(dbBytes));

        m.put("imageCount", count);
        m.put("imageBytes", imageBytes);
        m.put("imageText", HumanSize.of(imageBytes));
        m.put("recentImageBytes", recentBytes);
        m.put("recentImageText", HumanSize.of(recentBytes));
        m.put("recentDays", GROWTH_DAYS);

        m.put("dailyGrowthBytes", v.dailyGrowthBytes());
        m.put("dailyGrowthText", v.dailyGrowthBytes() == null ? null : HumanSize.of(v.dailyGrowthBytes()));
        m.put("daysLeft", v.daysLeft());

        m.put("thresholds", Map.of(
                "warnUsableText", HumanSize.of(StorageHealth.WARN_USABLE),
                "criticalUsableText", HumanSize.of(StorageHealth.CRITICAL_USABLE),
                "warnDays", StorageHealth.WARN_DAYS,
                "criticalDays", StorageHealth.CRITICAL_DAYS));

        List<Map<String, Object>> tables = new ArrayList<>();
        try {
            for (Map<String, Object> row : statMapper.largestTables()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("tableName", row.get("table_name"));
                one.put("sizeText", HumanSize.of(num(row.get("total_bytes"))));
                one.put("rows", num(row.get("live_rows")));
                tables.add(one);
            }
        } catch (Exception e) {
            log.warn("读不到表体积排行: {}", e.getMessage());
        }
        m.put("largestTables", tables);

        if (!"local".equals(storageType)) {
            // MinIO 时照片不在这台机器上，页面上的盘只反映数据库与应用日志。
            // 不说这一句，人会把「盘很空」误当成「照片也安全」
            m.put("note", "存储方式为 " + storageType + "，照片不在本机；这里的容量只反映数据库与日志所在的盘");
        }
        return m;
    }

    /**
     * 每天早上看一眼。
     *
     * <p>为什么不只在状态变化时推：状态变成 CRITICAL 那天正好没人上班，就永远错过了。
     * 每天推一次、问题没解决第二天再推，才叫「一直顶在眼前」。
     * 代价是必须去重，否则一天之内重启三次就推三条——那比不推更烦人。
     */
    @Scheduled(cron = "0 30 8 * * ?")
    public void morningCheck() {
        try {
            Map<String, Object> h = health();
            if (!Boolean.TRUE.equals(h.get("alert"))) {
                return;
            }
            String level = String.valueOf(h.get("level"));
            String message = String.join("；", flagsOf(h));
            if (alertMapper.claim(ALERT_KEY + "_" + level, level, truncate(message, 900)) != 1) {
                log.debug("磁盘告警今天已经推过（{}），跳过", level);
                return;
            }
            notifyAdmins("服务器磁盘快满了", message);
            log.warn("已推送磁盘告警（{}）：{}", level, message);
        } catch (Exception e) {
            // 定时任务里抛异常会被框架吞成一行日志，而且这个任务本来就是为了
            // 「在没人看的时候报警」，所以失败必须自己记清楚
            log.error("磁盘健康检查失败", e);
        }
    }

    private void notifyAdmins(String title, String content) {
        List<SysUser> admins = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, "ADMIN")
                .eq(SysUser::getEnabled, true));
        for (SysUser u : admins) {
            String who = u.getRealName() == null || u.getRealName().isBlank()
                    ? u.getUsername() : u.getRealName();
            // 走通用通知链路：站内是权威记录，企业微信是第二投递通道，
            // 能不能推出去都不影响这条记录落在库里
            noticeService.push(who, "SYSTEM_ALERT", title, content, "SYSTEM", null, null);
        }
        if (admins.isEmpty()) {
            log.warn("磁盘告警没有推送对象：系统里没有启用的管理员账号");
        }
    }

    private static List<String> flagsOf(Map<String, Object> h) {
        Object flags = h.get("flags");
        if (flags instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            list.forEach(o -> out.add(String.valueOf(o)));
            return out;
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** @param usableBytes 读不到时返回负数，交给判定层去判 UNKNOWN */
    record Disk(String path, long totalBytes, long usableBytes) {
    }

    Disk disk() {
        Path p = Paths.get(localDir).toAbsolutePath().normalize();
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            existing = Paths.get(".").toAbsolutePath().normalize();
        }
        try {
            FileStore fs = Files.getFileStore(existing);
            return new Disk(existing.toString(), fs.getTotalSpace(), fs.getUsableSpace());
        } catch (IOException e) {
            log.warn("读不到磁盘容量 {}: {}", existing, e.getMessage());
            return new Disk(existing.toString(), 0, -1);
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
