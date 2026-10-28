-- V14：改版遗留的复核记录
--
-- V13 之后的 stale-usage 已经把「哪些作废版本上还挂着判定单」和「值不值得查」算出来了。
-- 但它只回答到「要不要查」，答不了「查过没有」。
--
-- 这个缺口在体系审核时最要命：审核员看到「12 张单是按已作废的 v1 判的，其中 10 张
-- 判合格，而现行版本收紧了」，下一句一定问「你们发现之后做了什么」。系统里如果
-- 什么都没有，那就是一条未闭环的不符合项；靠人拿本子记，一年后谁也翻不出来。
--
-- 所以这一版只做一件事：让复核这件事可以留痕。
--   1. 建 qc_stale_review，一次复核一行
--   2. stale-usage 的每一行带上最近一次复核，前端据此显示「已复核 / 未复核」
--
-- 为什么允许同一个版本多条记录、不做唯一约束：
--   质量记录的原则是只增不改。「10 月抽查了 3 张，结论维持」和「11 月客户投诉后
--   又查了一遍，这次发现两张确实超差」是同一次复核的不同轮次，后者不能覆盖前者——
--   覆盖掉就等于把「当时查过、当时判断没问题」这个事实抹了。最新一条代表当前状态，
--   历史全部保留，跟 qc_defect 的处置轨迹是同一个思路。
--
-- 为什么冗余存 product_id / version / standard_code：
--   复核记录是给审核看的证据，不能因为标准后来被删、产品改了名就变成一行看不懂的数字。
--   存快照的代价是多占几十字节，收益是三年后还能自己解释自己。
--
-- 为什么不用外键：
--   这张表的读取场景是「列表里带出最新复核」，走的是按 standard_id 的部分索引；
--   加外键反而让删版本的运维操作变复杂（本项目其他表也一律不加，保持一致）。

CREATE TABLE IF NOT EXISTS qc_stale_review (
    id               bigserial    PRIMARY KEY,
    standard_id      bigint       NOT NULL,
    product_id       bigint,
    version          integer,
    standard_code    varchar(64),
    product_name     varchar(128),
    risk_level       varchar(16),
    verdict          varchar(16)  NOT NULL,
    inspection_count integer,
    remark           text         NOT NULL,
    reviewer         varchar(64)  NOT NULL,
    reviewed_at      timestamptz  NOT NULL DEFAULT now(),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    del_flag         integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_stale_review_verdict CHECK (verdict IN ('KEPT', 'ACTION'))
);

COMMENT ON TABLE  qc_stale_review IS '按已作废标准版本下的判定单做了复核的记录。只增不改，最新一条代表当前状态';
COMMENT ON COLUMN qc_stale_review.standard_id IS '被复核的作废标准版本 ID。不带外键：删版本后这条记录仍要能读';
COMMENT ON COLUMN qc_stale_review.verdict IS 'KEPT 抽查后维持原判 / ACTION 复核发现问题、需要重新处置（返工/让步/追溯）。只给两种：含糊的「已阅」等于没记';
COMMENT ON COLUMN qc_stale_review.risk_level IS '复核当时该版本的风险档快照，方便日后对比「当时判断偏高还是偏低」';
COMMENT ON COLUMN qc_stale_review.inspection_count IS '复核当时这一版下挂着的判定单数快照。没有它，记录看不出「查的是 2 张还是 200 张里的几张」';
COMMENT ON COLUMN qc_stale_review.remark IS '必填。写清抽查了哪几张、依据什么、结论怎么来的——这是记录的全部价值所在';
COMMENT ON COLUMN qc_stale_review.reviewer IS '复核人姓名快照，不存用户 ID：用户停用后 ID 查不出名字，证据要能自解释';

-- 列表场景：按版本取最近一次复核
CREATE INDEX IF NOT EXISTS idx_stale_review_standard
    ON qc_stale_review (standard_id, reviewed_at DESC) WHERE del_flag = 0;

-- 工作台场景：最近做过哪些复核
CREATE INDEX IF NOT EXISTS idx_stale_review_time
    ON qc_stale_review (reviewed_at DESC, id DESC) WHERE del_flag = 0;
