-- ============================================================
-- 工件质检系统 V7：AQL 抽样方案（GB/T 2828.1-2012）
-- 适用：已在 V1~V6 之上运行的库；全新安装按 V1 → V7 顺序执行
-- 本脚本幂等，可重复执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. 抽样方案表
--
-- 为什么要有这张表：
--   V1 里抽样量与批次大小是脱钩的——标准上写死「抽 5 件」，来料 50 件抽 5 件、
--   来料 5000 件还是抽 5 件。这在质量体系上说不通：批量越大，同样的样本量
--   对批质量的代表性越弱。国标 GB/T 2828.1 的做法就是「批量 → 样本量字码 →
--   样本量与接收数」，也就是样本量必须是批量的函数。
--
-- 为什么做成数据表而不是写死在代码里：
--   不同客户认的 AQL / 检验水平 / 检验严格度不一样（正常、加严、放宽），
--   方案表一改就是一行 INSERT，不用重新发版；历史单据有自己的快照，不会跟着变。
--
-- 为什么只内置 AQL 2.5 一般检验水平 II：
--   这一档是来料/成品检验最常用的一档，且下表 11 行数据与
--   GB/T 38029-2019 附录C、GB/T 15552-2015 附录B、FZ/T 43015-2021 附录A
--   三份国标附录逐一核对过。其他 AQL 值不凭记忆补——要加就照国标表原样插行，
--   插完立刻在系统里生效。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qc_sampling_plan (
    id                bigserial     PRIMARY KEY,
    plan_code         varchar(32)   NOT NULL DEFAULT 'GB2828-1',  -- 方案组，如 GB2828-1
    inspection_level  varchar(8)    NOT NULL DEFAULT 'II',        -- 一般检验水平 I / II / III
    aql               numeric(6,3)  NOT NULL,                     -- 接收质量限，如 2.500
    lot_size_min      int           NOT NULL,                     -- 批量下限（含）
    lot_size_max      int           NOT NULL,                     -- 批量上限（含）
    code_letter       varchar(4)    NOT NULL,                     -- 样本量字码 A~R
    sample_size       int           NOT NULL,                     -- 样本量 n
    accept_qty        int           NOT NULL,                     -- 接收数 Ac
    reject_qty        int           NOT NULL,                     -- 拒收数 Re
    remark            varchar(128),
    del_flag          smallint      NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_plan_lot   CHECK (lot_size_min > 0 AND lot_size_max >= lot_size_min),
    CONSTRAINT ck_plan_qty   CHECK (sample_size > 0 AND accept_qty >= 0 AND reject_qty = accept_qty + 1),
    CONSTRAINT ck_plan_level CHECK (inspection_level IN ('I','II','III','S-1','S-2','S-3','S-4'))
);

-- 同一方案组 + 水平 + AQL 下，批量区间不能重叠
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_range
    ON qc_sampling_plan (plan_code, inspection_level, aql, lot_size_min) WHERE del_flag = 0;
-- 建单时按 (水平, AQL, 批量) 命中一行，这条索引支撑该查询
CREATE INDEX IF NOT EXISTS idx_plan_match
    ON qc_sampling_plan (inspection_level, aql, lot_size_min) WHERE del_flag = 0;

COMMENT ON TABLE  qc_sampling_plan IS '抽样方案表，默认内置 GB/T 2828.1-2012 一般检验水平 II / AQL 2.5 正常检验一次抽样';
COMMENT ON COLUMN qc_sampling_plan.plan_code IS '方案组标识。ANSI/ASQ Z1.4 与 GB/T 2828.1 等价，可另建一组';
COMMENT ON COLUMN qc_sampling_plan.aql IS '接收质量限 AQL，单位 %。2.500 表示 2.5%';
COMMENT ON COLUMN qc_sampling_plan.code_letter IS '样本量字码，便于和客户/供应商对表时直接报字码';
COMMENT ON COLUMN qc_sampling_plan.accept_qty IS '接收数 Ac：不合格件数 ≤ Ac 判该批接收，≥ Re 判拒收';
COMMENT ON COLUMN qc_sampling_plan.reject_qty IS '拒收数 Re，一次抽样恒等于 Ac + 1';

-- ------------------------------------------------------------
-- 2. 内置数据：GB/T 2828.1-2012 正常检验一次抽样，一般检验水平 II，AQL 2.5
-- ------------------------------------------------------------
INSERT INTO qc_sampling_plan
    (plan_code, inspection_level, aql, lot_size_min, lot_size_max, code_letter, sample_size, accept_qty, reject_qty)
VALUES
    ('GB2828-1', 'II', 2.500,     2,     8, 'A',   2,  0,  1),
    ('GB2828-1', 'II', 2.500,     9,    15, 'B',   3,  0,  1),
    ('GB2828-1', 'II', 2.500,    16,    25, 'C',   5,  0,  1),
    ('GB2828-1', 'II', 2.500,    26,    50, 'D',   8,  0,  1),
    ('GB2828-1', 'II', 2.500,    51,    90, 'E',  13,  1,  2),
    ('GB2828-1', 'II', 2.500,    91,   150, 'F',  20,  1,  2),
    ('GB2828-1', 'II', 2.500,   151,   280, 'G',  32,  2,  3),
    ('GB2828-1', 'II', 2.500,   281,   500, 'H',  50,  3,  4),
    ('GB2828-1', 'II', 2.500,   501,  1200, 'J',  80,  5,  6),
    ('GB2828-1', 'II', 2.500,  1201,  3200, 'K', 125,  7,  8),
    ('GB2828-1', 'II', 2.500,  3201, 10000, 'L', 200, 10, 11)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3. 标准：加抽样方案选择
--
-- sampling_plan = FIXED：老行为，样本量取标准上写死的 sample_size
-- sampling_plan = AQL  ：样本量与接收数由「批量 + AQL + 检验水平」查表得出
-- 默认 FIXED，存量标准行为不变
-- ------------------------------------------------------------
ALTER TABLE qc_standard ADD COLUMN IF NOT EXISTS sampling_plan    varchar(16)  NOT NULL DEFAULT 'FIXED';
ALTER TABLE qc_standard ADD COLUMN IF NOT EXISTS aql              numeric(6,3);
ALTER TABLE qc_standard ADD COLUMN IF NOT EXISTS inspection_level varchar(8);

ALTER TABLE qc_standard DROP CONSTRAINT IF EXISTS ck_standard_plan;
ALTER TABLE qc_standard ADD  CONSTRAINT ck_standard_plan CHECK (sampling_plan IN ('FIXED','AQL'));

COMMENT ON COLUMN qc_standard.sampling_plan IS 'FIXED 固定样本量 / AQL 按批量查抽样方案表';
COMMENT ON COLUMN qc_standard.aql IS 'AQL 模式下的接收质量限，NULL 表示用默认 2.5';
COMMENT ON COLUMN qc_standard.inspection_level IS 'AQL 模式下的检验水平，NULL 表示用默认 II';
COMMENT ON COLUMN qc_standard.sample_size IS 'FIXED 模式下的抽检件数；AQL 模式下该值不参与计算';

-- ------------------------------------------------------------
-- 4. 检验单：抽样依据快照
--
-- 铁律：明细带标准快照，标准改版不影响历史单。抽样依据同理——
-- 一年后回看这张单，必须能答出「当时凭什么抽这么多件、凭什么判接收」，
-- 所以把批量和依据文字一起冻在单上，而不是回头去查方案表。
-- ------------------------------------------------------------
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS lot_qty        int;
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS sampling_basis varchar(200);

COMMENT ON COLUMN qc_inspection.lot_qty IS '快照：建单时的批次数量（批量 N）';
COMMENT ON COLUMN qc_inspection.sampling_basis IS '快照：抽样依据，如「GB/T 2828.1 一般检验水平 II / AQL 2.5：批量 300，字码 H，抽 50 件，Ac=3」';
