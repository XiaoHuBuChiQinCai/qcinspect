-- ---------------------------------------------------------------------------
-- V19：系统健康预警的推送去重
--
-- 「磁盘快满了」这种话喊一次有用，喊十次就没人看了。但反过来，
-- 满了一个星期不吭声也不行——问题没解决，人以为系统自己在管。
--
-- 所以按 (alert_key, alert_date) 唯一：同一个问题**一天最多推一次**，
-- 第二天还没解决就再提醒一次。这比「只在状态变化时推」准：
-- 状态变化那天正好没人上班，就永远错过了。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qc_system_alert (
    id          bigserial     PRIMARY KEY,
    alert_key   varchar(64)   NOT NULL,
    alert_date  date          NOT NULL,
    level       varchar(16)   NOT NULL,               -- WARN / CRITICAL
    message     varchar(1000),
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_system_alert ON qc_system_alert (alert_key, alert_date);

COMMENT ON TABLE qc_system_alert IS '系统健康预警推送去重：同一 alert_key 一天只推一次，问题没解决第二天会再推';
