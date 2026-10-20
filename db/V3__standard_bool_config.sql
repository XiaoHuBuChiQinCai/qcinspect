-- ============================================================
-- V3：标准项补「布尔合格值」覆盖字段
--
-- 为什么需要：
--   同一个检验项在不同产品上，合格值的语义可能相反。
--   例：「毛刺」在 A 产品是缺陷问法（有毛刺 = NG），
--       在 B 产品的另一个工步里可能叫「去毛刺处理」（已处理 = OK）。
--   之前合格值只能配在 qc_item 主数据上，全局唯一，无法按标准差异化。
--
-- 适用场景：已用 V1 建过库、想原地升级。全新安装直接跑 V1，本脚本不用跑。
-- ============================================================

BEGIN;

ALTER TABLE qc_standard_item
    ADD COLUMN IF NOT EXISTS bool_pass_value boolean;

-- 备注：这句话必须写成单个字符串字面量。
-- 拆成两行相邻字面量时，psql 能跑（PostgreSQL 允许换行拼接），
-- 但 Spring 的 spring.sql.init 脚本执行器会把换行压成空格再发出去，
-- PostgreSQL 就会报「语法错误 在 '第二段' 或附近」。
COMMENT ON COLUMN qc_standard_item.bool_pass_value IS
    '布尔型合格值（覆盖主数据）。NULL = 沿用 qc_item.bool_pass_value；true = 录「有」算合格，false = 录「无」算合格';

COMMIT;
