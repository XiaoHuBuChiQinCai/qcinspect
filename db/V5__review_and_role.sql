-- ============================================================
-- 工件质检系统 V5：审核闭环 + 角色细分 + 明细量具提示
-- 适用：已在 V1~V4 之上运行的库；全新安装按 V1 → V2 → V3 → V4 → V5 顺序执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. 角色细分：ADMIN 管理员 / INSPECTOR 检验员 / REVIEWER 审核员
--
-- 为什么要单独拆出 REVIEWER：
--   检验员自己录的数据由自己放行，在 ISO 9001 里叫「自检自判」，属于体系漏洞。
--   把「判定」和「放行」拆成两个角色，才谈得上独立审核。
-- ------------------------------------------------------------
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS ck_user_role;
ALTER TABLE sys_user ADD CONSTRAINT ck_user_role
    CHECK (role IN ('ADMIN', 'INSPECTOR', 'REVIEWER'));
COMMENT ON COLUMN sys_user.role IS 'ADMIN 管理员 / INSPECTOR 检验员 / REVIEWER 审核员';

-- ------------------------------------------------------------
-- 2. 检验单审核痕迹
--
-- 审核不是把状态改一下就算完：谁审的、什么时候审的、审的时候说了什么，
-- 这三样缺一项，追溯时都无法回答「当时凭什么放行」。
-- ------------------------------------------------------------
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS reviewed_by    varchar(64);
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS reviewed_at    timestamptz;
ALTER TABLE qc_inspection ADD COLUMN IF NOT EXISTS review_comment varchar(500);
COMMENT ON COLUMN qc_inspection.reviewed_by    IS '审核人展示名，由登录态写入，不接受前端传值';
COMMENT ON COLUMN qc_inspection.reviewed_at    IS '审核时间';
COMMENT ON COLUMN qc_inspection.review_comment IS '审核意见；退回时必须填写';

-- ------------------------------------------------------------
-- 3. 操作日志增加审核动作
--    REVIEW = 审核通过归档，REJECT = 审核退回（回到 DRAFT 待修正）
-- ------------------------------------------------------------
ALTER TABLE qc_inspection_log DROP CONSTRAINT IF EXISTS ck_log_action;
ALTER TABLE qc_inspection_log ADD CONSTRAINT ck_log_action
    CHECK (action IN ('CREATE', 'SUBMIT', 'REOPEN', 'CLOSE', 'VOID', 'REVIEW', 'REJECT'));
COMMENT ON COLUMN qc_inspection_log.action IS
    'CREATE / SUBMIT / REOPEN / CLOSE / VOID / REVIEW（审核通过） / REJECT（审核退回）';

-- ------------------------------------------------------------
-- 4. 明细表补量具提示快照
--
-- 检验项主数据里有 measure_tool（卡尺 / 千分尺 / 硬度计），
-- 建单时把它一起快照进明细，移动端才能按项推荐量具，
-- 而不是让检验员在几十把量具里凭记忆挑。
-- ------------------------------------------------------------
ALTER TABLE qc_inspection_line ADD COLUMN IF NOT EXISTS measure_tool varchar(64);
COMMENT ON COLUMN qc_inspection_line.measure_tool IS '快照：该检验项建议的测量手段，移动端据此推荐量具';

-- ------------------------------------------------------------
-- 5. 存量数据补量具提示（新建单由程序写入，这里只补历史）
-- ------------------------------------------------------------
UPDATE qc_inspection_line l
   SET measure_tool = i.measure_tool
  FROM qc_item i
 WHERE l.item_id = i.id
   AND l.measure_tool IS NULL;

-- ------------------------------------------------------------
-- 6. 提示：演示用审核员账号
--
-- 密码哈希不写死在这里 —— BCrypt 哈希随盐变化，写死既不可解释也像后门。
-- 用 admin 登录后在 PC 端「用户管理」新建：
--   检验员 inspector / 审核员 reviewer
-- ------------------------------------------------------------
