-- ============================================================
-- 工件质检系统 V8：返工复检闭环
-- 适用：已在 V1~V7 之上运行的库；全新安装按 V1 → V8 顺序执行
-- 本脚本幂等，可重复执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. 复检单的来源字段
--
-- 为什么要有这两个字段：
--   V1~V7 的返工闭环是断的。qc_defect.recheck_inspection_id 能让缺陷指向一张
--   复检单，但反过来，复检单不知道自己是谁的复检；更要紧的是系统里根本没有
--   「开复检单」的入口——DefectService 强制「返工完成后必须挂一张复检单」，
--   检验员却被卡在原地：只能去批次页手工开一张普通检验单，再回来抄单号。
--   这一步一旦靠人抄，抄错单号就等于把两批货的追溯链接错了。
--
-- 为什么单独存 recheck_defect_id 而不只靠 defect 表反查：
--   一张原单可以有多条不良。返工 A 不良和返工 B 不良是两次独立复检，
--   复检单必须能答出「我在验的是哪一条不良返工后的件」。
--
-- 为什么不用 inspect_type 塞一个 RECHECK 值：
--   inspect_type 表达的是「这是来料检/过程检/出货检」，是业务环节；
--   复检是相对于某张原单的关系，两者正交。混用会让 IQC 的统计把复检单
--   算成另一类来料，来料批次数的口径立刻失真。
-- ------------------------------------------------------------

ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS recheck_of_id      bigint;
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS recheck_defect_id  bigint;

COMMENT ON COLUMN qc_inspection.recheck_of_id     IS '复检来源单：本单是哪张单的返工复检，普通单为空';
COMMENT ON COLUMN qc_inspection.recheck_defect_id IS '复检针对的不良记录 id，与 recheck_of_id 成对出现';

CREATE INDEX IF NOT EXISTS idx_inspection_recheck_of
    ON qc_inspection (recheck_of_id) WHERE recheck_of_id IS NOT NULL;

-- 一条不良只允许挂一张**有效**复检单。
-- 唯一索引在这里不是性能考虑，是防重复：缺陷页上「开复检单」被连点两下，
-- 或者两个人同时点，会造出两张孤儿复检单，而缺陷只认最后一张，另一张
-- 就变成了没人管、也没人知道它验的是什么的单子。
--
-- 为什么要带 `status <> 'VOID'`：复检单开错了（比如件数填错、标准选错）会作废重开。
-- 如果索引不看状态，作废的那张仍占着唯一槽位，缺陷就再也开不出新单，
-- 而「返工完成必须挂有效复检单」的校验又不放行——不良会永久卡死。
CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_recheck_defect
    ON qc_inspection (recheck_defect_id)
    WHERE recheck_defect_id IS NOT NULL AND status <> 'VOID' AND del_flag = 0;

-- ------------------------------------------------------------
-- 2. 复检件数与允收数的历史说明
--
-- 不需要改表：qc_inspection.sample_qty / accept_qty 已经是快照字段，
-- 复检单照样写进去，只是取值规则不同（见 InspectionService.createRecheck）：
--   件数 = 该不良的返工件数，不按 AQL 重抽
--   Ac   = 0，返工件必须全部合格
-- 依据记在 sampling_basis 里，事后能答出「凭什么只验 3 件」。
-- ------------------------------------------------------------
