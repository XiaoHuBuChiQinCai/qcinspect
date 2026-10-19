-- ============================================================
-- 工件质检系统 V1 初始化脚本
-- DB: PostgreSQL 16+
-- 约定：所有业务表带 del_flag 逻辑删除；时间统一 timestamptz
-- ============================================================

-- ------------------------------------------------------------
-- 1. 产品主数据
-- ------------------------------------------------------------
CREATE TABLE qc_product (
    id              bigserial     PRIMARY KEY,
    product_code    varchar(64)   NOT NULL,
    product_name    varchar(128)  NOT NULL,
    spec            varchar(128),
    unit            varchar(16)   NOT NULL DEFAULT '件',
    remark          text,
    del_flag        smallint      NOT NULL DEFAULT 0,
    created_by      varchar(64),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(64),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_product_code ON qc_product (product_code) WHERE del_flag = 0;
COMMENT ON TABLE  qc_product IS '产品（被检工件）';
COMMENT ON COLUMN qc_product.spec IS '规格型号，如 DN50 PN16';

-- ------------------------------------------------------------
-- 2. 检验项主数据（跨产品复用）
-- ------------------------------------------------------------
CREATE TABLE qc_item (
    id                bigserial     PRIMARY KEY,
    item_code         varchar(64)   NOT NULL,
    item_name         varchar(64)   NOT NULL,
    value_type        varchar(16)   NOT NULL,           -- NUMBER / ENUM / BOOL / TEXT
    unit              varchar(16),
    default_min       numeric(18,4),
    default_max       numeric(18,4),
    options_json      jsonb,                            -- [{"code":"OK","name":"无","pass":true}]
    bool_pass_value   boolean       NOT NULL DEFAULT true,
    measure_tool      varchar(64),                      -- 卡尺 / 千分尺 / 硬度计 / CMM
    group_name        varchar(32),                      -- 尺寸 / 外观 / 性能 / 材质
    remark            text,
    del_flag          smallint      NOT NULL DEFAULT 0,
    created_by        varchar(64),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(64),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_item_value_type CHECK (value_type IN ('NUMBER','ENUM','BOOL','TEXT'))
);
CREATE UNIQUE INDEX uk_item_code ON qc_item (item_code) WHERE del_flag = 0;
COMMENT ON TABLE  qc_item IS '检验项主数据，可被多个检验标准复用';
COMMENT ON COLUMN qc_item.options_json IS '枚举型选项，pass=true 表示该选项判为合格';
COMMENT ON COLUMN qc_item.bool_pass_value IS '布尔型：取该值判为合格';

-- ------------------------------------------------------------
-- 3. 检验标准（产品 + 版本）
-- ------------------------------------------------------------
CREATE TABLE qc_standard (
    id                bigserial     PRIMARY KEY,
    standard_code     varchar(64)   NOT NULL,
    product_id        bigint        NOT NULL REFERENCES qc_product (id),
    version           int           NOT NULL DEFAULT 1,
    status            varchar(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACTIVE / OBSOLETE
    sampling_type     varchar(16)   NOT NULL DEFAULT 'FULL',   -- FULL 全检 / SAMPLE 抽检 / NONE 免检
    sample_size       int           NOT NULL DEFAULT 1,        -- 抽检件数
    allowed_ng_count  int           NOT NULL DEFAULT 0,        -- 允收数 Ac：允许的不合格件数
    effective_from    date,
    remark            text,
    del_flag          smallint      NOT NULL DEFAULT 0,
    created_by        varchar(64),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(64),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_standard_status CHECK (status IN ('DRAFT','ACTIVE','OBSOLETE')),
    CONSTRAINT ck_standard_sampling CHECK (sampling_type IN ('FULL','SAMPLE','NONE'))
);
CREATE UNIQUE INDEX uk_standard_code_ver ON qc_standard (standard_code, version) WHERE del_flag = 0;
-- 同一产品同一时刻只能有一个生效版本
CREATE UNIQUE INDEX uk_standard_active ON qc_standard (product_id) WHERE status = 'ACTIVE' AND del_flag = 0;
COMMENT ON TABLE  qc_standard IS '检验标准，改版新增 version，老版本置 OBSOLETE 不删除';
COMMENT ON COLUMN qc_standard.allowed_ng_count IS '允收数 Ac：该标准允许的最大不合格件数。判批时用不合格件数与之比较，不是不合格项数';

-- ------------------------------------------------------------
-- 4. 标准 - 检验项关联（一个标准 N 项）
-- ------------------------------------------------------------
CREATE TABLE qc_standard_item (
    id              bigserial     PRIMARY KEY,
    standard_id     bigint        NOT NULL REFERENCES qc_standard (id) ON DELETE CASCADE,
    item_id         bigint        NOT NULL REFERENCES qc_item (id),
    seq             int           NOT NULL DEFAULT 0,
    group_name      varchar(32),                        -- 覆盖主数据的默认分组
    is_critical     boolean       NOT NULL DEFAULT false,
    is_required     boolean       NOT NULL DEFAULT true,
    value_mode      varchar(16)   NOT NULL DEFAULT 'SINGLE',   -- SINGLE 单值 / MULTIPLE 多点
    point_count     int           NOT NULL DEFAULT 1,
    min_value       numeric(18,4),
    max_value       numeric(18,4),
    options_json    jsonb,                              -- 覆盖主数据的默认选项，为空则用主数据
    bool_pass_value boolean,                            -- 覆盖主数据的合格值（布尔型），NULL 表示沿用主数据
    is_destructive  boolean       NOT NULL DEFAULT false,
    del_flag        smallint      NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_std_item_mode CHECK (value_mode IN ('SINGLE','MULTIPLE'))
);
CREATE UNIQUE INDEX uk_std_item ON qc_standard_item (standard_id, item_id) WHERE del_flag = 0;
CREATE INDEX idx_std_item_item ON qc_standard_item (item_id);
COMMENT ON TABLE  qc_standard_item IS '标准包含的检验项，带本标准的限值与判定配置';
COMMENT ON COLUMN qc_standard_item.point_count IS '多点测量时的测点数量';

-- ------------------------------------------------------------
-- 5. 批次
-- ------------------------------------------------------------
CREATE TABLE qc_batch (
    id                bigserial     PRIMARY KEY,
    batch_no          varchar(64)   NOT NULL,
    work_order_no     varchar(64),
    product_id        bigint        NOT NULL REFERENCES qc_product (id),
    inspect_type      varchar(16)   NOT NULL DEFAULT 'FQC',   -- IQC / IPQC / FQC / OQC
    qty               int           NOT NULL,
    supplier          varchar(128),
    material_batch_no varchar(64),
    status            varchar(16)   NOT NULL DEFAULT 'PENDING', -- PENDING / INSPECTING / DONE
    arrived_at        timestamptz,
    remark            text,
    del_flag          smallint      NOT NULL DEFAULT 0,
    created_by        varchar(64),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(64),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_batch_type CHECK (inspect_type IN ('IQC','IPQC','FQC','OQC')),
    CONSTRAINT ck_batch_status CHECK (status IN ('PENDING','INSPECTING','DONE'))
);
CREATE UNIQUE INDEX uk_batch_no ON qc_batch (batch_no) WHERE del_flag = 0;
CREATE INDEX idx_batch_product ON qc_batch (product_id);
CREATE INDEX idx_batch_status ON qc_batch (status);
COMMENT ON TABLE  qc_batch IS '待检批次，按工单或来料批建立';

-- ------------------------------------------------------------
-- 6. 检验单
-- ------------------------------------------------------------
CREATE TABLE qc_inspection (
    id                  bigserial     PRIMARY KEY,
    inspection_no       varchar(64)   NOT NULL,
    batch_id            bigint        NOT NULL REFERENCES qc_batch (id),
    standard_id         bigint        NOT NULL REFERENCES qc_standard (id),
    standard_version    int           NOT NULL,          -- 快照：建单时用的版本号
    product_id          bigint        NOT NULL,
    inspect_type        varchar(16)   NOT NULL,
    sampling_type       varchar(16)   NOT NULL,
    sample_qty          int           NOT NULL DEFAULT 1,          -- 抽检件数 N
    accept_qty          int           NOT NULL DEFAULT 0,          -- 快照：建单时的允收数 Ac，标准改版不影响本单
    inspector_id        bigint,
    inspector_name      varchar(64),
    status              varchar(16)   NOT NULL DEFAULT 'DRAFT',    -- DRAFT / SUBMITTED / CLOSED / VOID
    result              varchar(16)   NOT NULL DEFAULT 'NA',       -- PASS / FAIL / NA
    passed_qty          int           NOT NULL DEFAULT 0,          -- 合格件数，判定后回写
    failed_qty          int           NOT NULL DEFAULT 0,          -- 不合格件数，判定后回写
    ng_count            int           NOT NULL DEFAULT 0,          -- 不合格行数（件 × 项）
    critical_ng_count   int           NOT NULL DEFAULT 0,          -- 关键项不合格行数
    judge_reason        varchar(500),                              -- 整批判定说明
    inspected_at        timestamptz,
    remark              text,
    del_flag            smallint      NOT NULL DEFAULT 0,
    created_by          varchar(64),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          varchar(64),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_insp_status CHECK (status IN ('DRAFT','SUBMITTED','CLOSED','VOID')),
    CONSTRAINT ck_insp_result CHECK (result IN ('PASS','FAIL','NA')),
    CONSTRAINT ck_insp_qty CHECK (passed_qty >= 0 AND failed_qty >= 0
                                  AND (status IN ('DRAFT','VOID') OR passed_qty + failed_qty = sample_qty))
);
CREATE UNIQUE INDEX uk_inspection_no ON qc_inspection (inspection_no) WHERE del_flag = 0;
CREATE INDEX idx_insp_batch ON qc_inspection (batch_id);
CREATE INDEX idx_insp_status ON qc_inspection (status);
CREATE INDEX idx_insp_time ON qc_inspection (inspected_at DESC);
COMMENT ON TABLE  qc_inspection IS '检验单，一单对应一次检验批';
COMMENT ON COLUMN qc_inspection.standard_version IS '快照：建单时使用的标准版本，后续标准改版不影响本单';
COMMENT ON COLUMN qc_inspection.accept_qty IS '快照：建单时的允收数 Ac，标准改版不影响本单';
COMMENT ON COLUMN qc_inspection.judge_reason IS '整批判定说明；关键项 NG 落在允收范围内时也会在这里点出来';

-- ------------------------------------------------------------
-- 7. 检验明细（含标准快照，历史单据自包含）
-- ------------------------------------------------------------
CREATE TABLE qc_inspection_line (
    id              bigserial     PRIMARY KEY,
    inspection_id   bigint        NOT NULL REFERENCES qc_inspection (id) ON DELETE CASCADE,
    piece_no        int           NOT NULL DEFAULT 1,    -- 件号，从 1 开始。抽检 N 件则每项有 N 行
    item_id         bigint        NOT NULL,
    -- 以下均为快照字段，建单时从 qc_standard_item 复制，之后不再随标准变化
    item_code       varchar(64)   NOT NULL,
    item_name       varchar(64)   NOT NULL,
    group_name      varchar(32),
    value_type      varchar(16)   NOT NULL,
    value_mode      varchar(16)   NOT NULL,
    point_count     int           NOT NULL DEFAULT 1,
    unit            varchar(16),
    min_value       numeric(18,4),
    max_value       numeric(18,4),
    options_json    jsonb,
    bool_pass_value boolean       NOT NULL DEFAULT true,
    is_critical     boolean       NOT NULL DEFAULT false,
    seq             int           NOT NULL DEFAULT 0,
    -- 实测数据
    measured_values jsonb,                               -- ["12.03","12.01","12.02"]
    measured_text   text,                                -- 文本型录入
    gauge_id        bigint,                              -- 实际使用的量具
    gauge_code      varchar(64),                         -- 量具编号快照
    result          varchar(16)   NOT NULL DEFAULT 'PENDING',  -- PASS / FAIL / PENDING
    judge_reason    varchar(255),
    remark          varchar(255),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_line_result CHECK (result IN ('PASS','FAIL','PENDING'))
);
CREATE INDEX idx_line_inspection ON qc_inspection_line (inspection_id);
CREATE INDEX idx_line_item ON qc_inspection_line (item_id);
-- 一件一项只能有一行，数据库层挡住重复录入
CREATE UNIQUE INDEX uk_line_piece_item ON qc_inspection_line (inspection_id, piece_no, item_id);
COMMENT ON TABLE  qc_inspection_line IS '检验明细，含标准快照，保证历史单据判定依据不被后续改版影响';
COMMENT ON COLUMN qc_inspection_line.piece_no IS '件号，从 1 开始。一个批次抽 N 件，每个检验项就有 N 行';

-- ------------------------------------------------------------
-- 8. 不良记录
-- ------------------------------------------------------------
CREATE TABLE qc_defect (
    id                bigserial     PRIMARY KEY,
    inspection_id     bigint        NOT NULL REFERENCES qc_inspection (id) ON DELETE CASCADE,
    inspection_line_id bigint       REFERENCES qc_inspection_line (id),
    piece_no          int           NOT NULL DEFAULT 1,   -- 件号，不良要能定位到具体哪一件
    item_id           bigint,
    defect_code       varchar(32),
    defect_name       varchar(64)   NOT NULL,
    defect_qty        int           NOT NULL DEFAULT 1,
    disposition       varchar(16),                                -- REWORK / SCRAP / CONCESSION / RETURN
    disposition_note  text,
    -- 处置闭环
    disposition_status      varchar(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING/PROCESSING/DONE/WAIVED
    disposition_by          varchar(64),
    disposition_at          timestamptz,
    concession_reason       text,
    concession_approved_by  varchar(64),
    concession_approved_at  timestamptz,
    recheck_inspection_id   bigint,                              -- 返工后的复检单
    created_by        varchar(64),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_defect_disposition CHECK (disposition IS NULL
        OR disposition IN ('REWORK','SCRAP','CONCESSION','RETURN')),
    CONSTRAINT ck_defect_disposition_status CHECK (disposition_status IN ('PENDING','PROCESSING','DONE','WAIVED')),
    -- 让步接收必须留理由和授权人，否则等于没人负责
    CONSTRAINT ck_defect_concession CHECK (disposition_status <> 'WAIVED'
        OR (concession_reason IS NOT NULL AND concession_approved_by IS NOT NULL))
);
CREATE INDEX idx_defect_inspection ON qc_defect (inspection_id);
CREATE INDEX idx_defect_item ON qc_defect (item_id);
COMMENT ON TABLE  qc_defect IS '不良记录，NG 项产生的缺陷与处置方式';
COMMENT ON COLUMN qc_defect.piece_no IS '件号，不良记录必须能定位到具体哪一件';

-- ------------------------------------------------------------
-- 9. 照片
-- ------------------------------------------------------------
CREATE TABLE qc_image (
    id            bigserial     PRIMARY KEY,
    biz_type      varchar(16)   NOT NULL,               -- INSPECTION / DEFECT
    biz_id        bigint        NOT NULL,
    file_name     varchar(255)  NOT NULL,
    object_key    varchar(255)  NOT NULL,               -- MinIO 对象键
    file_size     bigint,
    content_type  varchar(64),
    created_by    varchar(64),
    created_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_image_biz ON qc_image (biz_type, biz_id);
COMMENT ON TABLE  qc_image IS '检验照片，object_key 指向 MinIO';

-- ------------------------------------------------------------
-- 10. 用户（简化版，实际可对接公司统一认证）
-- ------------------------------------------------------------
CREATE TABLE sys_user (
    id            bigserial     PRIMARY KEY,
    username      varchar(64)   NOT NULL,
    password      varchar(128)  NOT NULL,               -- BCrypt
    real_name     varchar(64),
    role          varchar(16)   NOT NULL DEFAULT 'INSPECTOR', -- ADMIN / INSPECTOR
    enabled       boolean       NOT NULL DEFAULT true,
    del_flag      smallint      NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_username ON sys_user (username) WHERE del_flag = 0;

-- ------------------------------------------------------------
-- 11. 检验单操作日志（提交即锁定，所有状态变化必须留痕）
-- ------------------------------------------------------------
CREATE TABLE qc_inspection_log (
    id              bigserial     PRIMARY KEY,
    inspection_id   bigint        NOT NULL REFERENCES qc_inspection (id) ON DELETE CASCADE,
    action          varchar(16)   NOT NULL,     -- CREATE / SUBMIT / REOPEN / CLOSE / VOID
    from_status     varchar(16),
    to_status       varchar(16),
    reason          text,                       -- 解锁/作废必须填理由
    operator        varchar(64),
    summary         text,                       -- 动作结果摘要，如判定结论
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_log_action CHECK (action IN ('CREATE','SUBMIT','REOPEN','CLOSE','VOID'))
);
CREATE INDEX idx_log_inspection ON qc_inspection_log (inspection_id, created_at DESC);
COMMENT ON TABLE qc_inspection_log IS '检验单操作日志。已提交的单据不允许原地修改，改动一律通过 reopen 并在此留痕';

-- ------------------------------------------------------------
-- 12. 量具台账
--
-- 过校准有效期的量具测出的数据在质量体系上不被承认，
-- 所以这张表不是「附属资料」，是判定数据是否有效的前提。
-- ------------------------------------------------------------
CREATE TABLE qc_gauge (
    id                  bigserial     PRIMARY KEY,
    gauge_code          varchar(64)   NOT NULL,
    gauge_name          varchar(128)  NOT NULL,
    gauge_type          varchar(32),                       -- 卡尺 / 千分尺 / 硬度计 / CMM / 塞规
    accuracy            varchar(32),                       -- 精度，如 0.01mm
    calibrate_date      date,                              -- 上次校准日
    calibrate_cycle_days int          NOT NULL DEFAULT 365,
    next_calibrate_date date,                              -- 下次到期日，程序维护
    status              varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    owner_dept          varchar(64),
    remark              text,
    del_flag            smallint      NOT NULL DEFAULT 0,
    created_by          varchar(64),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          varchar(64),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_gauge_status CHECK (status IN ('ACTIVE','SCRAPPED'))
);
CREATE UNIQUE INDEX uk_gauge_code ON qc_gauge (gauge_code) WHERE del_flag = 0;
CREATE INDEX idx_gauge_next_calibrate ON qc_gauge (next_calibrate_date)
    WHERE del_flag = 0 AND status = 'ACTIVE';
COMMENT ON TABLE qc_gauge IS '量具台账。过校准有效期的量具测出的数据在质量体系上不被承认';

-- ------------------------------------------------------------
-- 13. 不良代码字典
-- ------------------------------------------------------------
CREATE TABLE qc_defect_code (
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
CREATE UNIQUE INDEX uk_defect_code ON qc_defect_code (code) WHERE del_flag = 0;
CREATE INDEX idx_defect_code_category ON qc_defect_code (category);
COMMENT ON TABLE qc_defect_code IS '不良代码字典，帕累托图按此维度统计，禁止自由输入';

-- ------------------------------------------------------------
-- 14. 出货记录（追溯的出向）
-- ------------------------------------------------------------
CREATE TABLE qc_shipment (
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
CREATE UNIQUE INDEX uk_shipment_no ON qc_shipment (shipment_no) WHERE del_flag = 0;
CREATE INDEX idx_shipment_batch ON qc_shipment (batch_id);
CREATE INDEX idx_shipment_customer ON qc_shipment (customer);
COMMENT ON TABLE qc_shipment IS '出货记录。正向追溯（原料批 → 客户）和反向追溯（客诉 → 批次）都靠它';

-- ============================================================
-- 初始化数据：一个法兰盘 + 8 个检验项 + 一个标准
-- ============================================================
INSERT INTO qc_product (product_code, product_name, spec, unit)
VALUES ('P-FLANGE-DN50', '法兰盘', 'DN50 PN16', '件');

INSERT INTO qc_item (item_code, item_name, value_type, unit, default_min, default_max, measure_tool, group_name) VALUES
('I-OD',    '外径',     'NUMBER', 'mm',  11.95,  12.05, '千分尺', '尺寸'),
('I-WALL',  '壁厚',     'NUMBER', 'mm',  2.500,  3.000, '卡尺',   '尺寸'),
('I-HOLE',  '孔径',     'NUMBER', 'mm',  6.020,  6.080, '内径千分尺', '尺寸'),
('I-HARD',  '硬度',     'NUMBER', 'HRC', 45.00,  50.00, '硬度计', '性能');

INSERT INTO qc_item (item_code, item_name, value_type, options_json, group_name) VALUES
('I-SCRATCH', '表面划伤', 'ENUM', '[{"code":"NONE","name":"无划伤","pass":true},{"code":"LIGHT","name":"轻微划伤","pass":true},{"code":"HEAVY","name":"严重划伤","pass":false}]', '外观');

-- bool_pass_value 语义：录入值等于该值时判为合格
--   「毛刺」问的是"是否有毛刺"，无毛刺(false)才合格 → false
--   「材质证明」问的是"是否有证明"，有(true)才合格 → true
INSERT INTO qc_item (item_code, item_name, value_type, bool_pass_value, group_name) VALUES
('I-BURR',   '毛刺',     'BOOL', false, '外观'),
('I-MATERIAL','材质证明','BOOL', true,  '材质');

INSERT INTO qc_item (item_code, item_name, value_type, group_name) VALUES
('I-REMARK', '补充说明', 'TEXT', '备注');

INSERT INTO qc_standard (standard_code, product_id, version, status, sampling_type, sample_size, allowed_ng_count, effective_from, remark)
VALUES ('S-FLANGE-01', 1, 3, 'ACTIVE', 'FULL', 1, 0, CURRENT_DATE, '法兰盘成品检验标准 v3');

INSERT INTO qc_standard_item (standard_id, item_id, seq, group_name, is_critical, value_mode, point_count, min_value, max_value, is_destructive) VALUES
(1, 1, 10, '尺寸', true,  'MULTIPLE', 3, 11.95, 12.05, false),
(1, 2, 20, '尺寸', false, 'SINGLE',   1, 2.500, 3.000, false),
(1, 3, 30, '尺寸', false, 'MULTIPLE', 2, 6.020, 6.080, false),
(1, 4, 40, '性能', true,  'SINGLE',   1, 45.00, 50.00, false),
(1, 5, 50, '外观', false, 'SINGLE',   1, NULL,  NULL,  false),
(1, 6, 60, '外观', false, 'SINGLE',   1, NULL,  NULL,  false),
(1, 7, 70, '材质', true,  'SINGLE',   1, NULL,  NULL,  false),
(1, 8, 80, '备注', false, 'SINGLE',   1, NULL,  NULL,  false);

-- 量具示例：一把正常、一把临近到期、一把已过期
-- 已过期那把是故意留的，用来验证提交时会不会被拦住
INSERT INTO qc_gauge (gauge_code, gauge_name, gauge_type, accuracy, calibrate_date, calibrate_cycle_days, next_calibrate_date, status, owner_dept) VALUES
('G-001', '数显卡尺 0-150',   '卡尺',   '0.01mm', CURRENT_DATE - 120, 365, CURRENT_DATE + 245, 'ACTIVE',  '品质部'),
('G-002', '外径千分尺 0-25',  '千分尺', '0.001mm', CURRENT_DATE - 340, 365, CURRENT_DATE + 25,  'ACTIVE',  '品质部'),
('G-003', '洛氏硬度计',       '硬度计', 'HRC 1',  CURRENT_DATE - 500, 365, CURRENT_DATE - 135, 'ACTIVE',  '品质部');

-- 不良代码字典
INSERT INTO qc_defect_code (code, name, category, severity) VALUES
('D-SCRATCH', '划伤',     '外观', 'MINOR'),
('D-BURR',    '毛刺',     '外观', 'MINOR'),
('D-DENT',    '磕碰凹陷', '外观', 'MAJOR'),
('D-SIZE',    '尺寸超差', '尺寸', 'MAJOR'),
('D-THREAD',  '螺纹不良', '尺寸', 'CRITICAL'),
('D-HARD',    '硬度不足', '性能', 'CRITICAL'),
('D-MATERIAL','材质不符', '材质', 'CRITICAL'),
('D-MISSING', '漏装',     '装配', 'CRITICAL');

-- 批次 + 出货示例，用于演示追溯。批次只有 3 件，全检模式一单 3 件，不会触发 200 上限
INSERT INTO qc_batch (batch_no, work_order_no, product_id, inspect_type, qty, supplier, material_batch_no, status, arrived_at) VALUES
('B20261019001', 'WO-20261019-01', 1, 'FQC', 3, '宁波精铸一厂', 'M20261010-07', 'PENDING', now());

INSERT INTO qc_shipment (shipment_no, batch_id, customer, qty, shipped_at, remark) VALUES
('SH20261019001', 1, '苏州德茂机械', 3, now(), '示例出货记录，用于演示反向追溯');
