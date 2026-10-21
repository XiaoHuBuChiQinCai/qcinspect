#!/usr/bin/env bash
#
# 数据库备份：把 qc-db 容器里的库导成 gzip 的 SQL 文件，旧文件按天数自动清理。
#
# 为什么用 pg_dump 的纯 SQL 而不是自定义格式：
#   出事那天往往不是你在操作，接手的人只需要会 psql。自定义格式要 pg_restore，
#   版本对不上还得折腾。体积大一点换恢复简单，值。
#
# 用法：
#   ./scripts/backup.sh                     # 默认备份到 /root/qc/backups，留 30 天
#   QC_BACKUP_DIR=/data/bak ./scripts/backup.sh
#
# 建议挂 cron（见 docs/DEPLOY.md）：
#   0 2 * * * /root/qc/qcinspect/scripts/backup.sh >> /var/log/qc-backup.log 2>&1
#
set -euo pipefail

CONTAINER="${QC_DB_CONTAINER:-qc-db}"
DB_USER="${QC_DB_USER:-qc}"
DB_NAME="${QC_DB_NAME:-qcinspect}"
BACKUP_DIR="${QC_BACKUP_DIR:-/root/qc/backups}"
KEEP_DAYS="${QC_BACKUP_KEEP_DAYS:-30}"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    echo "[$(date '+%F %T')] 容器 $CONTAINER 不存在，备份中止" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"
stamp=$(date '+%Y%m%d-%H%M%S')
target="$BACKUP_DIR/qcinspect-$stamp.sql.gz"
tmp="$target.part"

# 先写 .part，校验通过再改名：中途失败不会留下一个看起来正常的半截文件
# （半截备份比没有备份更危险，恢复的时候才发现就晚了）
if ! docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
        --no-owner --no-privileges | gzip -9 > "$tmp"; then
    rm -f "$tmp"
    echo "[$(date '+%F %T')] pg_dump 失败，备份中止" >&2
    exit 1
fi

size=$(stat -c%s "$tmp")
if [ "$size" -lt 1024 ]; then
    rm -f "$tmp"
    echo "[$(date '+%F %T')] 备份文件只有 ${size} 字节，视为失败" >&2
    exit 1
fi

# 完整性粗检：gzip 能完整解压才算数
if ! gzip -t "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    echo "[$(date '+%F %T')] 备份文件 gzip 校验失败" >&2
    exit 1
fi

mv "$tmp" "$target"

# 只删本脚本自己产出的那类文件，且要同时满足「超过保留天数」
deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'qcinspect-*.sql.gz' \
        -mtime +"$KEEP_DAYS" -print -delete | wc -l)

echo "[$(date '+%F %T')] 备份完成 $target（$((size / 1024)) KB），清理过期 $deleted 个"
