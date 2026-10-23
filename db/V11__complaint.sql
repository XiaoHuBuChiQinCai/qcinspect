-- V11：客户投诉（客诉）登记与闭环
--
-- 原有追溯链是「批次 → 检验单 → 出货给了谁」，走到底就断了：
-- 货出去之后客户说这批有问题，系统里没有任何地方能记下这件事。
-- 结果是客诉只活在电话、微信和某个人的脑子里，年底体系审核问
-- 「今年客户投诉几起、都关闭了吗、根因是什么」，一条也拿不出来。
--
-- PDCA 的 A（处置）一直缺这一块：不良品内部有 qc_defect 闭环，
-- 客户侧却只有出口没有回路。本表补上这个回路。
--
-- 状态机刻意做得很短：
--   OPEN 待处理 → PROCESSING 处理中 → CLOSED 已关闭
-- 只有 CLOSED 要求写根因/措施/责任方，因为要求一登记就写根因
-- 只会让人填「已处理」三个字，那比不填更糟。

CREATE TABLE IF NOT EXISTS qc_complaint (
    id             bigserial     PRIMARY KEY,
    complaint_no   varchar(32)   NOT NULL,
    batch_id       bigint        NOT NULL REFERENCES qc_batch (id),
    shipment_id    bigint        REFERENCES qc_shipment (id),
    customer       varchar(128)  NOT NULL,
    complaint_date date          NOT NULL,
    category       varchar(32)   NOT NULL DEFAULT 'OTHER',
    severity       varchar(16)   NOT NULL DEFAULT 'MEDIUM',
    qty            int,
    description    text          NOT NULL,
    status         varchar(16)   NOT NULL DEFAULT 'OPEN',
    root_cause     text,
    action         text,
    responsibility varchar(64),
    closed_by      varchar(64),
    closed_at      timestamptz,
    remark         text,
    del_flag       smallint      NOT NULL DEFAULT 0,
    created_by     varchar(64),
    created_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_complaint_status CHECK (status IN ('OPEN', 'PROCESSING', 'CLOSED')),
    CONSTRAINT ck_complaint_severity CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW'))
);

COMMENT ON TABLE qc_complaint IS '客户投诉：追溯链的回向，客诉进来要能反查到批次/出货单/检验单';
COMMENT ON COLUMN qc_complaint.complaint_no IS '客诉单号，格式 TS + yyyyMMdd + 3 位流水';
COMMENT ON COLUMN qc_complaint.batch_id IS '被投诉批次，必填——没有批次的客诉无法定责，只能当抱怨';
COMMENT ON COLUMN qc_complaint.shipment_id IS '对应的出货单，可不填（客户可能说不清哪一单）';
COMMENT ON COLUMN qc_complaint.category IS 'APP 外观 / SIZE 尺寸 / PERF 性能 / PACK 包装 / MARK 标识 / OTHER 其他';
COMMENT ON COLUMN qc_complaint.severity IS 'HIGH 严重（停线/退货/索赔）/ MEDIUM 一般 / LOW 轻微';
COMMENT ON COLUMN qc_complaint.qty IS '客户反馈的涉及数量，客户报不准时留空，不要瞎填';
COMMENT ON COLUMN qc_complaint.status IS 'OPEN 待处理 / PROCESSING 处理中 / CLOSED 已关闭';
COMMENT ON COLUMN qc_complaint.root_cause IS '根本原因，关闭时必填';
COMMENT ON COLUMN qc_complaint.action IS '纠正预防措施，关闭时必填';
COMMENT ON COLUMN qc_complaint.responsibility IS '责任归属：供应商 / 本厂制程 / 设计 / 运输 / 客户方，关闭时必填';

CREATE INDEX IF NOT EXISTS idx_complaint_batch
    ON qc_complaint (batch_id, complaint_date DESC);
CREATE INDEX IF NOT EXISTS idx_complaint_status
    ON qc_complaint (status, complaint_date DESC);
CREATE INDEX IF NOT EXISTS idx_complaint_customer
    ON qc_complaint (customer);

-- 单号唯一。用部分唯一索引而不是纯程序判断：两个人同时登记时先查后插挡不住。
CREATE UNIQUE INDEX IF NOT EXISTS uk_complaint_no
    ON qc_complaint (complaint_no) WHERE del_flag = 0;
