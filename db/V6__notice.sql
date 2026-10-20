-- ============================================================
-- 工件质检系统 V6：站内通知
-- 适用：已在 V1~V5 之上运行的库；全新安装按 V1 → V6 顺序执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. 通知表
--
-- 为什么要有这张表：
--   审核退回之前只改状态、写日志。审核员点了退回，检验员在产线上，
--   他不会知道——直到下一次主动打开「我的-被退回」。体系上这叫「异常未通报」，
--   现场常见的后果是单子一挂两三天，批次一直卡在 INSPECTING。
--   退回是一次需要对方动作的事件，必须主动落到接收人的待办里。
--
-- 接收人为什么存展示名而不是账号：
--   与 qc_inspection.created_by 同口径（都是登录态 displayName），
--   两个口径不一致的话，待办列表和通知列表会各算各的人。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qc_notice (
    id           bigserial    PRIMARY KEY,
    receiver     varchar(64)  NOT NULL,
    notice_type  varchar(32)  NOT NULL,
    title        varchar(200) NOT NULL,
    content      varchar(1000),
    biz_type     varchar(32),
    biz_id       bigint,
    biz_no       varchar(64),
    read_flag    smallint     NOT NULL DEFAULT 0,
    read_at      timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notice_read CHECK (read_flag IN (0, 1))
);

COMMENT ON TABLE  qc_notice IS '站内通知。审核退回、让步审批结果等需要对方动作的事件落在这里';
COMMENT ON COLUMN qc_notice.receiver IS '接收人展示名，与 qc_inspection.created_by 同口径';
COMMENT ON COLUMN qc_notice.notice_type IS 'REVIEW_REJECT 审核退回 / REVIEW_PASS 审核通过 / CONCESSION 让步接收';
COMMENT ON COLUMN qc_notice.biz_type IS 'INSPECTION 检验单 / DEFECT 不良记录 / GAUGE 量具';
COMMENT ON COLUMN qc_notice.biz_no IS '单据号快照，列表直接显示，不必回查业务表';
COMMENT ON COLUMN qc_notice.read_flag IS '0 未读 / 1 已读';

-- 未读角标走这条索引：按接收人 + 未读过滤，倒序取最近几条
CREATE INDEX IF NOT EXISTS idx_notice_receiver_unread
    ON qc_notice (receiver, read_flag, id DESC);

COMMENT ON INDEX idx_notice_receiver_unread IS '未读列表与角标计数专用';
