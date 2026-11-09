-- V22 量具测量系统分析（MSA / GR&R）
--
-- 量具台账只管「校准没过期」，那是时间维度。校准合格只说明这把尺和标准器对得上，
-- 不说明它能分辨出这道公差上的差异：一把分度值 0.05 的卡尺去测 10.00±0.02 的外径，
-- 校准证书照样是合格的，测出来的数据照样不能用。
--
-- 这张表存一次 GR&R 研究的原始读数，评价指标（%GRR / ndc）一律在读取时现算，
-- 不落库。落库就会出现「公式改了，旧结论还是旧的」，同一个数两个出处。

CREATE TABLE IF NOT EXISTS qc_msa_study (
    id               bigserial     PRIMARY KEY,
    study_no         varchar(32)   NOT NULL,               -- MSA + yyyyMMdd + 3 位流水
    gauge_id         bigint        REFERENCES qc_gauge (id),
    gauge_code       varchar(32)   NOT NULL,               -- 快照：量具台账改名/报废不影响历史结论
    gauge_name       varchar(128),
    product_id       bigint        REFERENCES qc_product (id),
    item_id          bigint        REFERENCES qc_item (id),
    characteristic   varchar(128)  NOT NULL,               -- 被研究的特性：外径 / 壁厚 / 硬度
    unit             varchar(16),
    lsl              numeric(14,4),
    usl              numeric(14,4),
    appraiser_count  int           NOT NULL,
    part_count       int           NOT NULL,
    trial_count      int           NOT NULL,
    appraisers       jsonb         NOT NULL,               -- ["张三","李四","王五"]
    readings         jsonb         NOT NULL,               -- [评价人][零件][试次]，与上面三个计数同形
    remark           text,
    del_flag         smallint      NOT NULL DEFAULT 0,
    created_by       varchar(64),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       varchar(64),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    -- 范围跟着 K1/K2/K3 系数表走：超出这个范围算不出 %GRR，收了也只是个假数字
    CONSTRAINT ck_msa_appraisers CHECK (appraiser_count BETWEEN 2 AND 5),
    CONSTRAINT ck_msa_parts      CHECK (part_count BETWEEN 2 AND 10),
    CONSTRAINT ck_msa_trials     CHECK (trial_count BETWEEN 2 AND 3),
    CONSTRAINT ck_msa_spec       CHECK (lsl IS NULL OR usl IS NULL OR lsl < usl),
    CONSTRAINT ck_msa_name       CHECK (length(btrim(characteristic)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_msa_no ON qc_msa_study (study_no) WHERE del_flag = 0;

-- 「这把量具做过几次 GRR、上次是什么结论」是点检和审核时问得最多的一句
CREATE INDEX IF NOT EXISTS idx_msa_gauge ON qc_msa_study (gauge_id, created_at DESC);

-- 走 SPC 的人会反过来问：这个检验项的量具够不够用
CREATE INDEX IF NOT EXISTS idx_msa_item ON qc_msa_study (item_id, created_at DESC);
