-- =============================================================================
-- V23：纠正预防措施（CAPA）
--
-- 到这里为止，系统能回答「哪一类不良最多」（帕累托）、「那批坏件现在在哪」（返修票）、
-- 「过程稳不稳」（SPC）、「量出来的数信不信」（GR&R）。四件事都能算得清清楚楚。
--
-- 唯独答不上一句：「同一个毛病，为什么修了三次还在犯？」
--
-- 帕累托上那个排第一的不良代码，三个月里在五个批次上各出现一次。报表上它是
-- 一根柱子，车间里它是五件互不相干的麻烦事——每次都是「这一批返工掉」，
-- 没有人去查它为什么反复发生。闭环只闭到了「把坏件处理掉」，
-- 没闭到「让它不再出现」。
--
-- 这张表补那半截：针对一个具体问题的根因、措施、责任人、期限，以及最要紧的
-- 「验证有效才能关闭」。
--
-- 为什么把它做成独立单据而不是不良上的几个字段：
--   1. 一次 CAPA 常常跨多批不良（帕累托上那一根柱子）
--   2. 它有自己的一条时间线：查原因 → 定措施 → 执行 → 验证 → 关闭
--   3. 外审要看的正是这条时间线，而不是某个不良记录上的备注
-- =============================================================================

CREATE TABLE IF NOT EXISTS qc_capa (
    id              bigserial     PRIMARY KEY,

    -- CAPA + yyyyMMdd + 3 位流水，走 DocNoService
    capa_no         varchar(32)   NOT NULL,

    -- 来源：这条措施是被什么逼出来的。保留 source_ref（单号快照）——外审要能顺着
    -- 一个措施倒回触发它的那张单，而单号是会随主数据变动的
    source_type     varchar(16)   NOT NULL DEFAULT 'DEFECT',
    source_id       bigint,
    source_ref      varchar(64),

    title           varchar(200)  NOT NULL,
    problem         text          NOT NULL,

    -- 冗余产品与不良代码：再犯检测（同一产品同一不良关过又来了）要按这两列查
    product_id      bigint,
    defect_code     varchar(32),

    severity        varchar(16)   NOT NULL DEFAULT 'MINOR',

    -- 四段式。root_cause 与 action_plan 是「能不能进验证」的硬门槛：
    -- 措施做完了却说不清为什么发生，等于没做，下次还会犯
    root_cause      text,                     -- 根本原因
    action_plan     text,                     -- 纠正措施：针对这个根因做什么
    prevent_plan    text,                     -- 预防措施：同类问题在别处怎么不发生

    owner           varchar(64)   NOT NULL,   -- 责任人
    due_date        date,                     -- 要求完成日
    started_at      timestamptz,              -- 开始处理的时间（用来区分「在改」和「只是记下来了」）

    -- OPEN 待分析 / DOING 措施执行中 / VERIFYING 待验证 / CLOSED 已关闭 / CANCELLED 已撤销
    status          varchar(16)   NOT NULL DEFAULT 'OPEN',

    -- 验证结论。close_effective 是三态：TRUE 有效 / FALSE 无效（退回继续改） / NULL 未验证
    close_effective boolean,
    verify_note     text,
    verified_by     varchar(64),
    verified_at     timestamptz,

    closed_by       varchar(64),
    closed_at       timestamptz,
    cancel_reason   text,
    remark          text,

    del_flag        smallint      NOT NULL DEFAULT 0,
    created_by      varchar(64),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(64),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_capa_source CHECK (source_type IN ('DEFECT', 'COMPLAINT', 'AUDIT', 'OTHER')),
    CONSTRAINT ck_capa_status CHECK (status IN ('OPEN', 'DOING', 'VERIFYING', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_capa_severity CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR')),

    -- 待验证 = 措施已经做完、等着别人来判断有没有用。
    -- 到这一步必须已经有根因和措施，否则「验证」验的是什么
    CONSTRAINT ck_capa_verifying CHECK (
        status <> 'VERIFYING' OR (
            root_cause  IS NOT NULL AND length(btrim(root_cause))  > 0
            AND action_plan IS NOT NULL AND length(btrim(action_plan)) > 0)),

    -- 关闭比待验证更严：除了根因和措施，还必须有验证结论与有效性判定。
    -- 这一条同时堵住「DOING 直接跳 CLOSED」——不经过验证就不能关
    CONSTRAINT ck_capa_closed CHECK (
        status <> 'CLOSED' OR (
            root_cause  IS NOT NULL AND length(btrim(root_cause))  > 0
            AND action_plan IS NOT NULL AND length(btrim(action_plan)) > 0
            AND verify_note IS NOT NULL AND length(btrim(verify_note)) > 0
            AND close_effective IS NOT NULL)),

    CONSTRAINT ck_capa_cancel CHECK (
        status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0))
);

COMMENT ON TABLE qc_capa IS '纠正预防措施。帕累托指出问题、这里回答「改没改、改完有没有用」';
COMMENT ON COLUMN qc_capa.root_cause IS '根本原因。空着的 CAPA 进不了验证环节——说不清为什么发生，措施就是猜的';
COMMENT ON COLUMN qc_capa.close_effective IS '验证是否有效。FALSE 表示措施不够，退回继续改，不是关闭';
COMMENT ON COLUMN qc_capa.source_ref IS '来源单号快照，外审要能顺着措施倒回触发它的那张单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_capa_no ON qc_capa (capa_no)
    WHERE del_flag = 0;

-- 一个来源只许一张有效 CAPA：撤销的不占槽位，可以重开
CREATE UNIQUE INDEX IF NOT EXISTS uk_capa_source ON qc_capa (source_type, source_id)
    WHERE source_id IS NOT NULL AND status <> 'CANCELLED' AND del_flag = 0;

-- 待办按「还开着 + 期限」扫
CREATE INDEX IF NOT EXISTS idx_capa_status ON qc_capa (status, due_date)
    WHERE del_flag = 0;

-- 再犯检测：同一产品同一不良代码，关过又来了
CREATE INDEX IF NOT EXISTS idx_capa_recur ON qc_capa (product_id, defect_code, status)
    WHERE del_flag = 0 AND product_id IS NOT NULL AND defect_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_capa_owner ON qc_capa (owner, status)
    WHERE del_flag = 0;
