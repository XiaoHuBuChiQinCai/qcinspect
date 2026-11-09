-- ============================================================
-- V18：企业微信出站推送
-- 适用：已在 V1~V17 之上运行的库；全新安装按 V1 → V18 顺序执行
-- ============================================================
--
-- 站内通知有一个绕不开的盲点：人不在系统里就收不到。
-- 审核退回写进了 qc_notice，检验员在车间，他不会坐在电脑前刷角标，
-- 单子就这么压了三天——通知机制没错，错在「通知只活在那个没人打开的页面里」。
-- 这一版把同一条通知顺带发到企业微信，人手上那台手机会响。
--
-- 为什么推送状态落在 qc_notice 上，而不是另建一张推送队列：
--   推的就是「这条通知到没到手机」，一条通知对一条推送，天然一对一。
--   另建表每次都得 join 回来才能回答「这条通知到底送出去没有」，
--   而管理员想知道的就是这一张表上的答案。
--
-- 状态口径（NONE 与 SKIPPED 必须分开，见下）：
--   NONE    通道没开，或历史数据，没走过推送
--   PENDING 已挂上队列，还没发出去
--   SENT    企业微信回了 errcode = 0
--   FAILED  发了但失败：网络、凭证、IP 白名单、userid 不存在
--   SKIPPED 压根没发：接收人没绑定企业微信账号
-- FAILED 与 SKIPPED 的区别是「该找谁」：前者查凭证与可信 IP，后者去补账号映射。
-- 合成一个值，管理员就只能对着一条错误说「不知道找谁」。
-- ------------------------------------------------------------

ALTER TABLE qc_notice
    ADD COLUMN IF NOT EXISTS push_state  varchar(16) NOT NULL DEFAULT 'NONE'
        CHECK (push_state IN ('NONE', 'PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    ADD COLUMN IF NOT EXISTS push_target varchar(64),
    ADD COLUMN IF NOT EXISTS push_at     timestamptz,
    ADD COLUMN IF NOT EXISTS push_error  varchar(500);

COMMENT ON COLUMN qc_notice.push_state  IS 'NONE 未启用 / PENDING 待发 / SENT 已发 / FAILED 发送失败 / SKIPPED 接收人未绑定企业微信';
COMMENT ON COLUMN qc_notice.push_target IS '实际发给谁（企业微信 UserID）。排障时最常看的就是「你以为发给他，其实发给了别人」';
COMMENT ON COLUMN qc_notice.push_at     IS '最后一次发送尝试的时间';
COMMENT ON COLUMN qc_notice.push_error  IS '失败原因原文。企业微信的 errmsg 都保留，别翻译成笼统的「发送失败」';

-- 管理员只看「没送到的」，成功的那几百条不该进这个索引
CREATE INDEX IF NOT EXISTS idx_notice_push_attention
    ON qc_notice (push_state, id DESC)
    WHERE push_state IN ('PENDING', 'FAILED', 'SKIPPED');

COMMENT ON INDEX idx_notice_push_attention IS '推送排障专用：只看待发、失败、未绑定的';

-- ------------------------------------------------------------
-- 接收人 → 企业微信账号
--
-- 刻意做成人工绑定，不做「按姓名自动匹配通讯录」：
--   重名在工厂里不是小概率事件（两个张伟），自动匹配一旦歪了，
--   就是把别人的退回意见推给了不相干的人——比不推更糟。
-- 留空是合法状态：这个账号就只用站内通知。
-- ------------------------------------------------------------
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS wecom_userid varchar(64);

COMMENT ON COLUMN sys_user.wecom_userid IS '企业微信 UserID（通讯录里的账号）。留空表示此人只收站内通知';

-- 一个企业微信账号只能绑一个人，否则同一条退回会发两遍
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wecom_userid
    ON sys_user (wecom_userid)
    WHERE wecom_userid IS NOT NULL AND wecom_userid <> '' AND del_flag = 0;
