-- ============================================================
-- V2：检验单补「件」的粒度 + 提交即锁定
--
-- 适用场景：已经用 V1 建过库、想原地升级。
-- 全新安装直接跑 V1 即可，V1 已包含以下全部改动，不要再跑本脚本。
--
-- 升级前请确认库里没有待迁移的检验单：
--   SELECT count(*) FROM qc_inspection;
-- 若为 0，直接执行；若不为 0，注意 sample_qty > 1 的老单据只有 1 件的明细行，
-- 迁移后 passed_qty + failed_qty = sample_qty 的约束会挡住后续更新，建议先清理测试数据。
-- ============================================================

BEGIN;

-- ---------- 1. 允收数语义修正 ----------
COMMENT ON COLUMN qc_standard.allowed_ng_count IS
    '允收数 Ac：该标准允许的最大不合格件数。判批时用不合格件数与之比较，不是不合格项数';

-- ---------- 2. 检验单：件数统计 + 判定理由 + VOID 状态 ----------
ALTER TABLE qc_inspection
    ADD COLUMN IF NOT EXISTS accept_qty    int           NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS passed_qty    int           NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_qty    int           NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS judge_reason  varchar(500);

COMMENT ON COLUMN qc_inspection.accept_qty   IS '快照：建单时的允收数 Ac，标准改版不影响本单';
COMMENT ON COLUMN qc_inspection.passed_qty   IS '合格件数，判定后回写';
COMMENT ON COLUMN qc_inspection.failed_qty   IS '不合格件数，判定后回写';
COMMENT ON COLUMN qc_inspection.judge_reason IS '整批判定说明';

-- 已存在的单据：按旧规则视为单件、全合格，先给一个自洽的初值
UPDATE qc_inspection
   SET passed_qty = sample_qty
 WHERE status IN ('SUBMITTED', 'CLOSED') AND result = 'PASS';

ALTER TABLE qc_inspection DROP CONSTRAINT IF EXISTS ck_insp_status;
ALTER TABLE qc_inspection ADD CONSTRAINT ck_insp_status
    CHECK (status IN ('DRAFT','SUBMITTED','CLOSED','VOID'));

ALTER TABLE qc_inspection DROP CONSTRAINT IF EXISTS ck_insp_qty;
ALTER TABLE qc_inspection ADD CONSTRAINT ck_insp_qty
    CHECK (passed_qty >= 0 AND failed_qty >= 0
           AND (status IN ('DRAFT','VOID') OR passed_qty + failed_qty = sample_qty));

-- ---------- 3. 明细：件号 ----------
ALTER TABLE qc_inspection_line
    ADD COLUMN IF NOT EXISTS piece_no int NOT NULL DEFAULT 1;

COMMENT ON COLUMN qc_inspection_line.piece_no IS
    '件号，从 1 开始。一个批次抽 N 件，每个检验项就有 N 行';

ALTER TABLE qc_inspection_line DROP CONSTRAINT IF EXISTS uk_line_piece_item;
CREATE UNIQUE INDEX uk_line_piece_item
    ON qc_inspection_line (inspection_id, piece_no, item_id);

-- ---------- 4. 不良记录：定位到件 ----------
ALTER TABLE qc_defect
    ADD COLUMN IF NOT EXISTS piece_no int NOT NULL DEFAULT 1;
COMMENT ON COLUMN qc_defect.piece_no IS '件号，不良记录必须能定位到具体哪一件';

-- ---------- 5. 操作日志 ----------
CREATE TABLE IF NOT EXISTS qc_inspection_log (
    id              bigserial     PRIMARY KEY,
    inspection_id   bigint        NOT NULL REFERENCES qc_inspection (id) ON DELETE CASCADE,
    action          varchar(16)   NOT NULL,
    from_status     varchar(16),
    to_status       varchar(16),
    reason          text,
    operator        varchar(64),
    summary         text,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_log_action CHECK (action IN ('CREATE','SUBMIT','REOPEN','CLOSE','VOID'))
);
CREATE INDEX IF NOT EXISTS idx_log_inspection
    ON qc_inspection_log (inspection_id, created_at DESC);
COMMENT ON TABLE qc_inspection_log IS
    '检验单操作日志。已提交的单据不允许原地修改，改动一律通过 reopen 并在此留痕';

COMMIT;
