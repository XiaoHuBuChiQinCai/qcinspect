#!/usr/bin/env bash
#
# 数据库备份：把 qc-db 容器里的库导成 gzip 的 SQL 文件，旧文件按天数自动清理。
#
# 为什么用 pg_dump 的纯 SQL 而不是自定义格式：
#   出事那天往往不是你在操作，接手的人只需要会 psql。自定义格式要 pg_restore，
#   版本对不上还得折腾。体积大一点换恢复简单，值。
#
# 每次跑完（成功或失败）往 qc_backup_log 写一行。这不只是日志——页面上
# 「上次成功备份在几小时前」就是读这张表算出来的。没有这行记录，备份等于在暗处跑。
#
# 用法：
#   ./scripts/backup.sh                     # 默认备份到 /root/qc/backups，留 30 天
#   QC_BACKUP_DIR=/data/bak ./scripts/backup.sh
#
# 定时由 systemd timer 驱动（qc-backup.timer，每天 02:00），见 docs/DEPLOY.md。
# 不要挂 cron：登录用户不在 docker 组、也不一定能读 /root，到点必然 Permission denied。
#
set -euo pipefail

CONTAINER="${QC_DB_CONTAINER:-qc-db}"
DB_USER="${QC_DB_USER:-qc}"
DB_NAME="${QC_DB_NAME:-qcinspect}"
BACKUP_DIR="${QC_BACKUP_DIR:-/root/qc/backups}"
KEEP_DAYS="${QC_BACKUP_KEEP_DAYS:-30}"
HOST_TAG="${QC_BACKUP_HOST:-$(hostname 2>/dev/null || echo unknown)}"

stamp=$(date '+%Y%m%d-%H%M%S')
target="$BACKUP_DIR/qcinspect-$stamp.sql.gz"
tmp="$target.part"

# ------------------------------------------------------------
# 把本次执行结果落库
# ------------------------------------------------------------
# 写库失败绝不能让备份本身失败：库挂掉的时候最需要一份备份，
# 这时候如果因为「记不上账」就退出，等于在最坏的时刻放弃干活。
# 所以这里一律吞掉错误，只往 stderr 喊一句。
#
# 用 psql 的 :'var' 插值而不是字符串拼接：message 里出现单引号
# （比如 pg_dump 的报错里带 'xxx'）会直接把 INSERT 拼断。
log_to_db() {
    local status="$1" file="$2" size="$3" pruned="$4" msg="$5"
    docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -q -v ON_ERROR_STOP=1 \
        -v v_status="$status" \
        -v v_file="$file" \
        -v v_size="$size" \
        -v v_pruned="$pruned" \
        -v v_host="$HOST_TAG" \
        -v v_msg="$msg" \
        -c "INSERT INTO qc_backup_log (status, file_name, file_size, pruned_files, host, message)
            VALUES (:'v_status',
                    nullif(:'v_file', ''),
                    nullif(:'v_size', '')::bigint,
                    coalesce(nullif(:'v_pruned', '')::int, 0),
                    nullif(:'v_host', ''),
                    nullif(:'v_msg', ''))" >/dev/null 2>&1 \
    || echo "[$(date '+%F %T')] 备份日志写库失败（库可能不可用，或 V13 尚未执行），本次结果只留在本文件与 systemd 日志里" >&2
}

fail() {
    rm -f "${tmp:-}"
    log_to_db FAILED "" "" "" "$1"
    echo "[$(date '+%F %T')] $1，备份中止" >&2
    exit 1
}

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    fail "容器 $CONTAINER 不存在"
fi

mkdir -p "$BACKUP_DIR"

# 先写 .part，校验通过再改名：中途失败不会留下一个看起来正常的半截文件
# （半截备份比没有备份更危险，恢复的时候才发现就晚了）
if ! docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
        --no-owner --no-privileges | gzip -9 > "$tmp"; then
    fail "pg_dump 失败"
fi

size=$(stat -c%s "$tmp")
if [ "$size" -lt 1024 ]; then
    fail "备份文件只有 ${size} 字节，视为失败"
fi

# 完整性粗检：gzip 能完整解压才算数
if ! gzip -t "$tmp" 2>/dev/null; then
    fail "备份文件 gzip 校验失败"
fi

mv "$tmp" "$target"

# 只删本脚本自己产出的那类文件，且要同时满足「超过保留天数」
deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'qcinspect-*.sql.gz' \
        -mtime +"$KEEP_DAYS" -print -delete | wc -l)

summary="备份完成 $target（$((size / 1024)) KB），清理过期 $deleted 个"
log_to_db SUCCESS "$(basename "$target")" "$size" "$deleted" "$summary"
echo "[$(date '+%F %T')] $summary"
