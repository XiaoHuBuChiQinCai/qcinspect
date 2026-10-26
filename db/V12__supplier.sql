-- V12：供应商主数据
--
-- 到 V11 为止，系统里唯一还在用「自由文本」的主数据就是供应商。
-- 产品有编码、检验项有字典、不良有代码表，唯独 qc_batch.supplier 是一行 varchar，
-- 让人手打。后果是同一家厂会出现「宁波精铸一厂」「宁波精铸一厂 」「宁波精铸」
-- 三种写法，供应商合格率排名裂成三行——看上去每家都不算太差，实际上那家最差的
-- 被稀释掉了。月报「最差 5 家供应商」这种结论直接就不可信。
--
-- 这一版把供应商立成主数据：
--   1. 建 qc_supplier（编码、名称、类别、联系人、评级、状态）
--   2. qc_batch 加 supplier_id 外键列，**保留原来的 supplier 文本列**
--   3. 把库里已有的自由文本刷成主数据并回填 supplier_id
--
-- 为什么保留 supplier 文本列：批次是「历史事实」，建批那天的供应商名称就是当时
-- 那个名字。供货商后来改名或合并，历史批次不该跟着变。所以两列并存：
-- supplier_id 用于统计归并，supplier 是名称快照，打报告时用快照。

CREATE TABLE IF NOT EXISTS qc_supplier (
    id            bigserial     PRIMARY KEY,
    supplier_code varchar(32)   NOT NULL,
    supplier_name varchar(128)  NOT NULL,
    short_name    varchar(64),
    category      varchar(16)   NOT NULL DEFAULT 'MATERIAL',
    contact       varchar(64),
    phone         varchar(64),
    level         varchar(8),
    status        varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    remark        text,
    del_flag      smallint      NOT NULL DEFAULT 0,
    created_by    varchar(64),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(64),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_supplier_category CHECK (category IN ('MATERIAL', 'OUTSOURCE', 'PACKAGING', 'SERVICE')),
    CONSTRAINT ck_supplier_status   CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_supplier_level    CHECK (level IS NULL OR level IN ('A', 'B', 'C'))
);

COMMENT ON TABLE  qc_supplier IS '供应商主数据。批次上的 supplier 是名称快照，统计归并一律走 supplier_id';
COMMENT ON COLUMN qc_supplier.supplier_code IS '供应商编码，厂内唯一，如 SUP0001';
COMMENT ON COLUMN qc_supplier.supplier_name IS '供应商全称，厂内唯一——同名两家厂在质量统计里必然被合并，不如在建档时就拆开';
COMMENT ON COLUMN qc_supplier.short_name IS '简称，列表和报表上用';
COMMENT ON COLUMN qc_supplier.category IS 'MATERIAL 原材料 / OUTSOURCE 外协加工 / PACKAGING 包装材料 / SERVICE 检测等服务';
COMMENT ON COLUMN qc_supplier.level IS 'A/B/C 供应商评级，未评级留空。空不等于 C，不要拿空值当差评';
COMMENT ON COLUMN qc_supplier.status IS 'ACTIVE 在用 / DISABLED 停用。停用不删档，历史批次还要能追溯';

-- 编码与名称各自唯一（只在未删除范围内）。程序里也判，但并发下先查后插挡不住
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_code ON qc_supplier (supplier_code) WHERE del_flag = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_name ON qc_supplier (supplier_name) WHERE del_flag = 0;
CREATE INDEX IF NOT EXISTS idx_supplier_status ON qc_supplier (status);

-- 批次挂主数据。不加 NOT NULL：历史批次可能压根没录供应商，
-- 硬加约束会让库升不上去
ALTER TABLE qc_batch ADD COLUMN IF NOT EXISTS supplier_id bigint;
CREATE INDEX IF NOT EXISTS idx_batch_supplier ON qc_batch (supplier_id);

-- ------------------------------------------------------------
-- 存量数据迁移：自由文本 → 主数据 → 回填
-- ------------------------------------------------------------
-- 编号从现有最大 SUP 编号往后接，保证重复执行不会撞唯一索引。
-- V2 以后的脚本都要求幂等：这套库已经在线上跑了，运维是「手工执行一遍新脚本」，
-- 手一抖跑第二次很常见。
INSERT INTO qc_supplier (supplier_code, supplier_name, remark, created_by, created_at)
SELECT 'SUP' || lpad(CAST(b.mx + row_number() OVER (ORDER BY t.nm) AS text), 4, '0'),
       t.nm,
       'V12 迁移自动建档（来自批次上的自由文本），非人工录入：请补全联系人、类别与评级',
       'system',
       now()
FROM (
    SELECT DISTINCT btrim(supplier) AS nm
    FROM qc_batch
    WHERE del_flag = 0 AND supplier IS NOT NULL AND btrim(supplier) <> ''
) t
CROSS JOIN (
    SELECT coalesce(max(CAST(substring(supplier_code FROM 4) AS int)), 0) AS mx
    FROM qc_supplier
    WHERE supplier_code ~ '^SUP[0-9]+$'
) b
WHERE NOT EXISTS (
    SELECT 1 FROM qc_supplier s WHERE s.del_flag = 0 AND s.supplier_name = t.nm
);

-- 回填批次的外键。只填 supplier_id 为空的，重跑不会覆盖人工改过的归属
UPDATE qc_batch b
SET    supplier_id = s.id
FROM   qc_supplier s
WHERE  b.supplier_id IS NULL
  AND  b.del_flag = 0
  AND  s.del_flag = 0
  AND  btrim(b.supplier) = s.supplier_name;
