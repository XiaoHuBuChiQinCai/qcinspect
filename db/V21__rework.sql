-- ============================================================
-- 工件质检系统 V21：返修工单（返修票）
-- 适用：已在 V1~V20 之上运行的库；全新安装按 V1 → V21 顺序执行
-- 本脚本幂等，可重复执行
-- ============================================================

-- ------------------------------------------------------------
-- 0. 为什么要有这张表
--
-- V4 给了不良四个处置方式，V8 又把「返工 → 复检」这段路接上了。但中间
-- 那段真正发生在车间的事一直没有落点：
--
--   不良判出来 → 处置选「返工」→ [ 送修区 ] → 返修完成 → 开复检单
--                                      ↑
--                                 这里只有一句备注
--
-- 结果是三个具体的问题，都不是「不够好用」，而是记录本身不成立：
--
--   1. 返修区丢件。不良列表上写着「返工」，但没人知道这 5 件送去哪了、
--      谁在修、说好什么时候回来。返修区堆着一堆无主的坏件，月底盘点
--      对不上数——系统里没有任何一张纸能回答「这 5 件现在在哪」。
--
--   2. 返工损耗凭空蒸发。返修不是万能的：送去 5 件，修好 4 件、修废 1 件
--      是常态。原来的模型里没有地方记这 1 件，它既不算合格、也没进报废，
--      在合格率报表上直接消失。返工率、返修报废率这些指标因此根本算不出来。
--
--   3. 复检件数没有依据。复检单的件数取的是缺陷记录的 defect_qty，也就是
--      「当初判出来坏了多少件」。但送去返修的可能只是其中一部分，
--      也可能修废了 1 件。按 defect_qty 开复检，会出现「只回来 4 件、
--      复检单上写着 5 件」——多出来的那件是不存在的。
--
-- 所以这张表的核心不是流程审批，是三件事：**件在哪、谁来修、回来多少**。
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qc_rework_order (
    id              bigserial     PRIMARY KEY,
    rework_no       varchar(32)   NOT NULL,          -- RW + yyyyMMdd + 3 位流水
    defect_id       bigint        NOT NULL REFERENCES qc_defect (id) ON DELETE CASCADE,
    inspection_id   bigint        NOT NULL,          -- 冗余：按单/按批查返修不必再绕不良
    batch_id        bigint        NOT NULL,
    product_id      bigint        NOT NULL,
    item_id         bigint,
    defect_code     varchar(32),
    defect_name     varchar(64)   NOT NULL,
    qty             int           NOT NULL,          -- 送修数量
    requirement     text,                            -- 返修要求（怎么修、修到什么程度）
    assignee        varchar(64),                     -- 返修人 / 返修工位，自由文本
    due_date        date,                            -- 要求完成日
    status          varchar(16)   NOT NULL DEFAULT 'OPEN',
    reworked_qty    int,                             -- 返好件数
    scrapped_qty    int,                             -- 返废件数
    finish_note     text,                            -- 修废的原因说明
    recheck_inspection_id bigint,                    -- 完工后生成的复检单
    assigned_by     varchar(64),
    assigned_at     timestamptz,
    started_at      timestamptz,
    finished_by     varchar(64),
    finished_at     timestamptz,
    cancel_reason   varchar(255),
    remark          text,
    del_flag        smallint      NOT NULL DEFAULT 0,
    created_by      varchar(64),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(64),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_rework_status CHECK (status IN ('OPEN', 'DOING', 'DONE', 'CANCELLED')),

    -- 送修 0 件不是单据，是误操作
    CONSTRAINT ck_rework_qty CHECK (qty > 0),

    -- 完工必须报数，且返好 + 返废 = 送修。
    --
    -- 这条约束放在库层而不只放程序里，是因为它是「账要对得上」的最后一道：
    -- 应用层校验可以被新加的接口绕过，库层这一条绕不过去。曾经有系统把
    -- 报废件记成 0 就算完工，两年后算返修率时才发现分母一直是错的。
    CONSTRAINT ck_rework_finish CHECK (
        status <> 'DONE'
        OR (reworked_qty IS NOT NULL AND scrapped_qty IS NOT NULL
            AND reworked_qty >= 0 AND scrapped_qty >= 0
            AND reworked_qty + scrapped_qty = qty)
    ),

    -- 取消要写理由：返修单被取消，意味着这批坏件换了条路走（报废/让步/转外协），
    -- 不写理由就等于让 5 件东西从记录里消失
    CONSTRAINT ck_rework_cancel CHECK (
        status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)
    )
);

COMMENT ON TABLE  qc_rework_order IS '返修工单：不良处置为返工时的实物跟踪票，记件在哪、谁修、回来多少';
COMMENT ON COLUMN qc_rework_order.qty IS '送修数量，默认取缺陷记录的不良件数，允许按实际送修调整';
COMMENT ON COLUMN qc_rework_order.reworked_qty IS '完工时报的返好件数；>0 才开复检单，复检件数取这个值';
COMMENT ON COLUMN qc_rework_order.scrapped_qty IS '返修过程中报废的件数；这部分要进统计，否则返工损耗永远看不见';
COMMENT ON COLUMN qc_rework_order.due_date IS '要求完成日，超期未完工的票要在待办里点名';

-- 一条不良只允许有一张**有效**返修单。
--
-- 与 V8 的复检单唯一索引同一个道理：连点两下、或者两个人同时开票，
-- 会造出两张票，返修区两拨人各修一半，报数时两边都说自己那半是全部，
-- 加起来是 10 件而实际只有 5 件。
--
-- 取消的票不占槽位：开错了（数量填错、不良选错）本来就要作废重开，
-- 若照旧占位，这条不良就永远开不出新票，而它又确实需要返修。
CREATE UNIQUE INDEX IF NOT EXISTS uk_rework_defect
    ON qc_rework_order (defect_id)
    WHERE status <> 'CANCELLED' AND del_flag = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rework_no
    ON qc_rework_order (rework_no) WHERE del_flag = 0;

CREATE INDEX IF NOT EXISTS idx_rework_status
    ON qc_rework_order (status, due_date);

CREATE INDEX IF NOT EXISTS idx_rework_inspection
    ON qc_rework_order (inspection_id);

CREATE INDEX IF NOT EXISTS idx_rework_batch
    ON qc_rework_order (batch_id);

-- 按产品统计返修量与返修报废率用
CREATE INDEX IF NOT EXISTS idx_rework_product
    ON qc_rework_order (product_id, created_at DESC);

-- ------------------------------------------------------------
-- 2. 单号种子
--
-- 不需要改表：qc_no_seq 是通用计数器，RW 前缀首次取号时自动插入。
-- ------------------------------------------------------------
