-- ============================================================
-- V9：出货单作废（红冲）
--
-- 为什么需要它：出货登记没有审核环节，现场点错数量、点错批次、
-- 客户临时取消，这些都不该靠「删库」解决——删掉就再也说不清
-- 「当时到底发没发、谁销的、为什么」。
--
-- 所以这里不做物理删除，也不复用 del_flag（del_flag 是隐藏数据，
-- 而作废是业务事实，必须能查、能看理由）。单独开 status：
--   NORMAL 正常
--   VOID   已作废（不计入批次累计出货量，可重新登记）
--
-- 作废后批次累计出货量的统计会把这张单排除掉，否则作废等于白作废，
-- 批次还是被占着数量出不了第二单。
-- ============================================================

ALTER TABLE qc_shipment ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'NORMAL';

-- 作废理由必填，不留空：一年后回来看看不懂的空记录没有意义
ALTER TABLE qc_shipment ADD COLUMN IF NOT EXISTS void_reason text;

ALTER TABLE qc_shipment ADD COLUMN IF NOT EXISTS voided_by varchar(64);

ALTER TABLE qc_shipment ADD COLUMN IF NOT EXISTS voided_at timestamptz;

COMMENT ON COLUMN qc_shipment.status IS 'NORMAL 正常 / VOID 已作废（不计入累计出货量）';
COMMENT ON COLUMN qc_shipment.void_reason IS '作废理由，必填';
COMMENT ON COLUMN qc_shipment.voided_by IS '作废人展示名';
COMMENT ON COLUMN qc_shipment.voided_at IS '作废时间';

-- 出货记录页默认按状态筛选，加上日期区间后是全表扫描最常见的形状
CREATE INDEX IF NOT EXISTS idx_shipment_status ON qc_shipment (status);
CREATE INDEX IF NOT EXISTS idx_shipment_shipped_at ON qc_shipment (shipped_at DESC);
