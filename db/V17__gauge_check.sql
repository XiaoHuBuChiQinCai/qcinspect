-- V17：量具点检（期间核查）
--
-- 校准是外部机构每年做一次，点检是车间自己每月做的：拿标准件量一遍，
-- 偏差在允差内就继续用。两件事不能合并成一张表——
-- 校准有证书号、有机构、有周期顺延；点检只有「对标准件量出来多少」。
-- 混在一起的下场是：外审问「这张证书谁出的」，点检记录上答不出来。
--
-- 结论口径与后端一致：|实测 - 标称| <= 允差 为合格（闭区间，等于允差算合格）。
-- 偏差与允差都以 numeric(18,4) 落库，保证「界面算一遍、后端再算一遍」不会因为
-- 浮点误差得出两个结论（10.01 - 10 在 double 里是 0.010000000000000675）。

CREATE TABLE IF NOT EXISTS qc_gauge_check (
    id              bigserial     PRIMARY KEY,
    gauge_id        bigint        NOT NULL REFERENCES qc_gauge (id),
    check_date      date          NOT NULL,
    standard_no     varchar(64)   NOT NULL,
    standard_value  numeric(18,4) NOT NULL,
    measured_value  numeric(18,4) NOT NULL,
    tolerance       numeric(18,4) NOT NULL,
    deviation       numeric(18,4) NOT NULL,
    result          varchar(16)   NOT NULL,
    checked_by      varchar(64),
    remark          text,
    del_flag        smallint      NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_gauge_check_result CHECK (result IN ('PASS', 'FAIL'))
);

COMMENT ON TABLE  qc_gauge_check IS '量具点检（期间核查）：每月拿标准件量一遍，看这把量具还准不准';
COMMENT ON COLUMN qc_gauge_check.standard_no    IS '标准件编号，点检的依据。没有标准件的点检等于没做';
COMMENT ON COLUMN qc_gauge_check.standard_value IS '标准件标称值，快照；标准件改标不追溯历史记录';
COMMENT ON COLUMN qc_gauge_check.measured_value IS '用这把量具量出来的值';
COMMENT ON COLUMN qc_gauge_check.tolerance      IS '允差（±）。等于允差算合格';
COMMENT ON COLUMN qc_gauge_check.deviation      IS '偏差 = 实测 - 标称，程序算好存下来，别每次再算一遍';
COMMENT ON COLUMN qc_gauge_check.result         IS 'PASS 合格 / FAIL 不合格';

CREATE INDEX IF NOT EXISTS idx_gauge_check_gauge
    ON qc_gauge_check (gauge_id, check_date DESC);

-- 一把量具一天只点检一次：手抖点两下、两个人同时录都要挡住。
-- 用部分唯一索引而不是先查后插，是因为并发下先查后插挡不住。
CREATE UNIQUE INDEX IF NOT EXISTS uk_gauge_check_day
    ON qc_gauge_check (gauge_id, check_date)
    WHERE del_flag = 0;

-- 停用原因要跟着量具走。
--
-- 以前只有「校准不合格」这一种停用，文案写死在 assertUsable 里。
-- 现在点检不合格也会停用，再写死就会出现「点检不合格，界面说校准不合格」——
-- 人拿着这句话去送检，钱花了问题还在。所以单独存一句真实原因。
ALTER TABLE qc_gauge ADD COLUMN IF NOT EXISTS suspend_reason varchar(200);

COMMENT ON COLUMN qc_gauge.suspend_reason IS '停用原因：校准不合格 / 点检不合格，停用时写入，复校合格后清空';

-- 存量停用量具补一句原因，别让它们显示成「原因未知」
UPDATE qc_gauge
   SET suspend_reason = '校准不合格（V17 迁移前登记，未留明细）'
 WHERE status = 'SUSPENDED'
   AND (suspend_reason IS NULL OR suspend_reason = '');
