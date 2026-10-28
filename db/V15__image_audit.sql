-- V15：图片删除要进操作日志，宽松一下 action 的取值约束
--
-- 照片是判定证据，删除必须能在全局审计里查到「谁在什么时候删了哪张图」，
-- 于是需要一个新的 action。
--
-- ⚠ 这个 CHECK 是「后来者覆盖前者」的写法：V1 建表时定了 5 个值，
-- V5 为了审核动作 DROP 掉重建成 7 个值，这里再 DROP 重建。
-- 也就是说**当前生效的清单只存在于最后一个改动它的脚本里**，
-- 照 V1 抄会把 V5 加的 REVIEW/REJECT 一起丢掉，表现是所有审核动作
-- 一提交就 500（约束违反）。改动前必须 grep 全库：
--     grep -rn "ck_log_action" db/*.sql
-- 取最后一个脚本的清单再往上加，不是取 V1 的。
--
-- 不干脆去掉 CHECK：约束挡的是拼错的动作名（比如 'VOIDED'），那类错误一旦入库，
-- 审计检索按 action 筛就永远漏掉那几条。加值比拆约束安全。
--
-- 幂等写法：先 DROP IF EXISTS 再 ADD，重复执行不会因为约束已存在而报错。

ALTER TABLE qc_inspection_log DROP CONSTRAINT IF EXISTS ck_log_action;

ALTER TABLE qc_inspection_log
    ADD CONSTRAINT ck_log_action
        CHECK (action IN ('CREATE', 'SUBMIT', 'REOPEN', 'CLOSE', 'VOID',
                          'REVIEW', 'REJECT', 'IMAGE_DELETE'));

COMMENT ON COLUMN qc_inspection_log.action IS
    'CREATE / SUBMIT / REOPEN / CLOSE / VOID / REVIEW（审核通过） / REJECT（审核退回） / IMAGE_DELETE（删除照片，照片是判定证据故单独留痕）';
