-- V13：备份日志
--
-- 从 2026-10-20 上线起，数据库每天 02:00 由 systemd timer 跑 scripts/backup.sh，
-- 跑完只往宿主机 /root/qc/backups 落一个 .sql.gz，成败全留在 systemd journal 里。
-- 结果是：系统里没有任何地方能回答「上次成功的备份是什么时候」。
--
-- 而备份失效这件事不会自己喊出来。它坏得很安静——timer 还在每天触发，日志目录
-- 还在多出文件（失败时脚本本来就不落文件，但如果磁盘满了、pg_dump 版本对不上、
-- 甚至整台机器换了，都没有人会在第一时间发现）。等到真需要恢复那天才查，
-- 前面三十天的「每日备份」全是空的。
--
-- 这一版让备份结果落到库里：
--   1. 建 qc_backup_log，脚本每次跑完（成功或失败）写一行
--   2. 应用算「距上次成功多久」，超过 36 小时就报警
--
-- 为什么不做成「应用去读备份目录的文件时间」：应用跑在容器里，宿主机目录得额外
-- 挂卷；一旦换了机器、改了路径、或者运维把 compose 改回简易版，页面就静默失明，
-- 而且失明的样子和「备份正常」长得一模一样——这比不展示更糟。
-- 写库的另一个好处：备份历史跟数据库自己在一起，恢复之后立刻能看到之前的状态。

CREATE TABLE IF NOT EXISTS qc_backup_log (
    id           bigserial    PRIMARY KEY,
    status       varchar(16)  NOT NULL,
    file_name    varchar(255),
    file_size    bigint,
    pruned_files integer      NOT NULL DEFAULT 0,
    host         varchar(128),
    message      varchar(1000),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_backup_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

COMMENT ON TABLE  qc_backup_log IS '每日数据库备份的执行记录。由 scripts/backup.sh 写入，应用只读';
COMMENT ON COLUMN qc_backup_log.status IS 'SUCCESS 落盘且 gzip 校验通过 / FAILED 任意环节失败。没有其他状态——进行中的备份不值得看，结果才值得';
COMMENT ON COLUMN qc_backup_log.file_size IS '压缩后字节数。用它判断「备份文件是不是突然小了一个量级」，比看时间更早发现问题';
COMMENT ON COLUMN qc_backup_log.pruned_files IS '本次清理掉的过期备份数，用于确认保留策略真的在生效';
COMMENT ON COLUMN qc_backup_log.host IS '执行备份的机器名。换机器或迁移后一眼能看出记录断在哪';
COMMENT ON COLUMN qc_backup_log.message IS '失败原因或成功摘要，直接展示给运维，不要塞堆栈';

-- 查询永远是「按时间倒序取最近几条」，倒序索引正好贴合
CREATE INDEX IF NOT EXISTS idx_backup_created ON qc_backup_log (created_at DESC, id DESC);

-- 找「最近一次成功」是高频查询，单独给个部分索引：
-- 一天一行，全表扫描也不慢，但失败排查时这张表会被反复查
CREATE INDEX IF NOT EXISTS idx_backup_success
    ON qc_backup_log (created_at DESC) WHERE status = 'SUCCESS';
