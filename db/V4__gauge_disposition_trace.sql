-- ============================================================
-- V4：量具校准 + 不良处置闭环 + 不良代码字典 + 出货追溯
--
-- 适用场景：已建库原地升级。全新安装请直接用 V1（V1 已含以下全部内容）。
--
-- 这一版的三个主题：
--   1. 量具 —— 卡尺过校准期，它测出的数在质量体系上全部无效，所以必须在录入/提交时拦住
--   2. 处置 —— NG 不是终点。返工/报废/让步接收要有人签字、有状态流转、返工后能回链复检单
--   3. 追溯 —— 从原料批查到客户，从客诉批号反查到检验员、量具、当时的实测值
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- 1. 量具台账
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qc_gauge (
    id                  bigserial     PRIMARY KEY,
    gauge_code          varchar(64)   NOT NULL,
    gauge_name          varchar(128)  NOT NULL,
    gauge_type          varchar(32),                       -- 卡尺 / 千分尺 / 硬度计 / CMM / 塞规
    accuracy            varchar(32),                       -- 精度，如 0.01mm
    calibrate_date      date,                              -- 上次校准日
    calibrate_cycle_days int          NOT NULL DEFAULT 365,
    next_calibrate_date date,                              -- 下次到期日，冗余存储便于检索，由程序维护
    status              varchar(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SCRAPPED
    owner_dept          varchar(64),
    remark              text,
    del_flag            smallint      NOT NULL DEFAULT 0,
    created_by          varchar(64),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          varchar(64),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_gauge_status CHECK (status IN ('ACTIVE','SCRAPPED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gauge_code ON qc_gauge (gauge_code) WHERE del_flag = 0;
CREATE INDEX IF NOT EXISTS idx_gauge_next_calibrate ON qc_gauge (next_calibrate_date)
    WHERE del_flag = 0 AND status = 'ACTIVE';

COMMENT ON TABLE  qc_gauge IS '量具台账。过校准有效期的量具测出的数据在质量体系上不被承认';
COMMENT ON COLUMN qc_gauge.next_calibrate_date IS '下次校准到期日 = calibrate_date + calibrate_cycle_days，程序写入';
COMMENT ON COLUMN qc_gauge.status IS 'ACTIVE 可用 / SCRAPPED 已报废';

-- ------------------------------------------------------------
-- 2. 检验明细记录用了哪把量具
-- ------------------------------------------------------------
ALTER TABLE qc_inspection_line
    ADD COLUMN IF NOT EXISTS gauge_id   bigint,
    ADD COLUMN IF NOT EXISTS gauge_code varchar(64);

COMMENT ON COLUMN qc_inspection_line.gauge_id IS
    '实际使用的量具。快照 gauge_code，量具台账日后改名不影响历史记录';

-- ------------------------------------------------------------
-- 3. 不良处置闭环
-- ------------------------------------------------------------
ALTER TABLE qc_defect
    ADD COLUMN IF NOT EXISTS disposition_status      varchar(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS disposition_by          varchar(64),
    ADD COLUMN IF NOT EXISTS disposition_at          timestamptz,
    ADD COLUMN IF NOT EXISTS concession_reason       text,
    ADD COLUMN IF NOT EXISTS concession_approved_by  varchar(64),
    ADD COLUMN IF NOT EXISTS concession_approved_at  timestamptz,
    ADD COLUMN IF NOT EXISTS recheck_inspection_id   bigint;

COMMENT ON COLUMN qc_defect.disposition_status IS
    'PENDING 待处理 / PROCESSING 处理中 / DONE 已完成 / WAIVED 让步接收';
COMMENT ON COLUMN qc_defect.concession_reason IS
    '让步接收理由。ISO 体系下让步接收是唯一需要授权人签字放行的处置方式';
COMMENT ON COLUMN qc_defect.recheck_inspection_id IS
    '返工后的复检单 ID，形成「原单 NG → 返工 → 复检」的追溯链';

ALTER TABLE qc_defect DROP CONSTRAINT IF EXISTS ck_defect_disposition_status;
ALTER TABLE qc_defect ADD CONSTRAINT ck_defect_disposition_status
    CHECK (disposition_status IN ('PENDING','PROCESSING','DONE','WAIVED'));

-- 让步接收必须留理由和授权人，否则等于没人负责
ALTER TABLE qc_defect DROP CONSTRAINT IF EXISTS ck_defect_concession;
ALTER TABLE qc_defect ADD CONSTRAINT ck_defect_concession
    CHECK (disposition_status <> 'WAIVED'
           OR (concession_reason IS NOT NULL AND concession_approved_by IS NOT NULL));

-- ------------------------------------------------------------
-- 4. 不良代码字典
--
-- 之前 defect_code 是自由字符串，帕累托图按字符串分组，
-- 「毛刺」「有毛刺」「burr」会被算成三类，统计直接失真。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qc_defect_code (
    id          bigserial     PRIMARY KEY,
    code        varchar(32)   NOT NULL,
    name        varchar(128)  NOT NULL,
    category    varchar(32),                            -- 尺寸 / 外观 / 性能 / 材质 / 装配
    severity    varchar(16)   NOT NULL DEFAULT 'MINOR',  -- CRITICAL / MAJOR / MINOR
    remark      text,
    del_flag    smallint      NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_defect_code_severity CHECK (severity IN ('CRITICAL','MAJOR','MINOR'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_defect_code ON qc_defect_code (code) WHERE del_flag = 0;
CREATE INDEX IF NOT EXISTS idx_defect_code_category ON qc_defect_code (category);

COMMENT ON TABLE qc_defect_code IS '不良代码字典，帕累托图按此维度统计，禁止自由输入';

-- ------------------------------------------------------------
-- 5. 出货记录（追溯的出向）
--
-- 只有「这批货发给谁了」，客诉来了才能反查到批次，再反查到检验单。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qc_shipment (
    id            bigserial     PRIMARY KEY,
    shipment_no   varchar(64)   NOT NULL,
    batch_id      bigint        NOT NULL REFERENCES qc_batch (id),
    customer      varchar(128)  NOT NULL,
    qty           int           NOT NULL,
    shipped_at    timestamptz   NOT NULL DEFAULT now(),
    remark        text,
    del_flag      smallint      NOT NULL DEFAULT 0,
    created_by    varchar(64),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipment_qty CHECK (qty > 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipment_no ON qc_shipment (shipment_no) WHERE del_flag = 0;
CREATE INDEX IF NOT EXISTS idx_shipment_batch ON qc_shipment (batch_id);
CREATE INDEX IF NOT EXISTS idx_shipment_customer ON qc_shipment (customer);

COMMENT ON TABLE qc_shipment IS '出货记录。正向追溯（原料批 → 客户）和反向追溯（客诉 → 批次）都靠它';

-- ------------------------------------------------------------
-- 6. 初始化不良代码
-- ------------------------------------------------------------
INSERT INTO qc_defect_code (code, name, category, severity) VALUES
('D-SCRATCH', '划伤',     '外观', 'MINOR'),
('D-BURR',    '毛刺',     '外观', 'MINOR'),
('D-DENT',    '磕碰凹陷', '外观', 'MAJOR'),
('D-SIZE',    '尺寸超差', '尺寸', 'MAJOR'),
('D-THREAD',  '螺纹不良', '尺寸', 'CRITICAL'),
('D-HARD',    '硬度不足', '性能', 'CRITICAL'),
('D-MATERIAL','材质不符', '材质', 'CRITICAL'),
('D-MISSING', '漏装',     '装配', 'CRITICAL')
ON CONFLICT DO NOTHING;

COMMIT;
