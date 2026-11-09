-- ============================================================
-- V20：登录失败锁定
-- 适用：已在 V1~V19 之上运行的库；全新安装按 V1 → V20 顺序执行
-- ============================================================
--
-- 这个系统原来对「连续输错密码」没有任何反应：错一百次和错一次的结果一样，
-- 唯一的成本是花时间。内网也不是理由——车间平板的密码往往是 6 位数字，
-- 而且登录页就在那里，谁都能对着它慢慢试。
--
-- 为什么计数落在 sys_user 上，不另建登录尝试表：
--   需要回答的问题只有一个——「这个账号现在还能不能登」。
--   原子自增 + 锁定截止时间，一条 UPDATE 就能答，而且重启不丢。
--   另建表还得 join 回来才知道该不该拦，历史行也要单独定保留期。
--
-- 为什么只锁账号、不锁 IP：
--   这是厂内系统，几十台设备走同一个出口，锁 IP 会把整条产线一起锁掉。
--   真要防的是「针对某个账号慢慢猜」，那锁账号才是对准的。
--
-- 为什么锁 15 分钟后自动解，且失败不再延长：
--   延长等于给了别人一个「按着某个人不让上班」的开关——知道账号就能锁死他。
--   固定窗口把这种伤害限制在 15 分钟内，而 15 分钟也足够让暴力破解变得不划算。
-- ------------------------------------------------------------

ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS failed_attempts int NOT NULL DEFAULT 0
        CHECK (failed_attempts >= 0),
    ADD COLUMN IF NOT EXISTS locked_until    timestamptz,
    ADD COLUMN IF NOT EXISTS last_failed_at  timestamptz;

COMMENT ON COLUMN sys_user.failed_attempts IS
    '连续密码错误次数。达到 5 次即锁定，并在锁定的同时清零——解锁后重新给 5 次机会';
COMMENT ON COLUMN sys_user.locked_until IS
    '锁定截止时间。为空表示未锁定；过期即自动放行，不需要人操作';
COMMENT ON COLUMN sys_user.last_failed_at IS
    '最近一次密码错误的时间。管理员判断「是他自己记错还是有人在试」主要看这一列';

-- 管理员那一页要一眼看出「谁被锁了」，所以只索引被锁住的行
CREATE INDEX IF NOT EXISTS idx_user_locked
    ON sys_user (locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON INDEX idx_user_locked IS '按锁定截止时间查被锁账号；未锁定的行不进索引';
