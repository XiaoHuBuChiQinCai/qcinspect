-- V10：量具校准履历
--
-- 原来登记校准只做一件事：把 qc_gauge.calibrate_date 覆盖成新日期。
-- 后果是每次送检都把上一次的记录擦掉，体系审核来问「这把卡尺去年的校准证书呢」，
-- 系统里什么都拿不出来。所以单独建一张只增不改的履历表。
--
-- result 的语义决定了主表怎么走：
--   PASS    合格      → 有效期顺延到 calibrate_date + cycle_days
--   LIMITED 限用      → 也顺延，但必须写清限制范围
--   FAIL    不合格    → 有效期不顺延，量具置 SUSPENDED 停用，否则第二天照样有人拿它测活

CREATE TABLE IF NOT EXISTS qc_gauge_calibration (
    id                  bigserial     PRIMARY KEY,
    gauge_id            bigint        NOT NULL REFERENCES qc_gauge (id),
    calibrate_date      date          NOT NULL,
    cycle_days          int           NOT NULL,
    next_calibrate_date date          NOT NULL,
    result              varchar(16)   NOT NULL DEFAULT 'PASS',
    certificate_no      varchar(128),
    agency              varchar(128),
    calibrate_by        varchar(64),
    remark              text,
    del_flag            smallint      NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE qc_gauge_calibration IS '量具校准履历：一次校准一行，只增不改，体系审核靠它回答「历史校准记录」';
COMMENT ON COLUMN qc_gauge_calibration.result IS 'PASS 合格 / FAIL 不合格 / LIMITED 限用';
COMMENT ON COLUMN qc_gauge_calibration.certificate_no IS '校准证书编号，外审必查项';
COMMENT ON COLUMN qc_gauge_calibration.agency IS '校准机构，内部校准写内部实验室';
COMMENT ON COLUMN qc_gauge_calibration.next_calibrate_date IS '按当时的周期算出的到期日，是当时的结论，不随后续改动';

CREATE INDEX IF NOT EXISTS idx_gauge_cal_gauge
    ON qc_gauge_calibration (gauge_id, calibrate_date DESC);

-- 同一把量具同一天只允许一条有效履历：手抖点两下、两个人同时登记都要挡住。
-- 用部分唯一索引而不是纯程序判断，是因为并发下先查后插挡不住。
CREATE UNIQUE INDEX IF NOT EXISTS uk_gauge_cal_day
    ON qc_gauge_calibration (gauge_id, calibrate_date)
    WHERE del_flag = 0;

-- 回填：把现有的「上次校准」补成一条履历。
-- 不回填的话，老量具在新界面上看着像从来没校准过，反而比没有履历更吓人。
-- remark 写明这是迁移数据，别让人误以为是原始校准记录。
INSERT INTO qc_gauge_calibration
    (gauge_id, calibrate_date, cycle_days, next_calibrate_date, result, calibrate_by, remark)
SELECT g.id,
       g.calibrate_date,
       g.calibrate_cycle_days,
       g.next_calibrate_date,
       'PASS',
       'system',
       'V10 迁移回填，非原始校准记录'
  FROM qc_gauge g
 WHERE g.calibrate_date IS NOT NULL
   AND g.next_calibrate_date IS NOT NULL
   AND NOT EXISTS (SELECT 1
                     FROM qc_gauge_calibration c
                    WHERE c.gauge_id = g.id
                      AND c.calibrate_date = g.calibrate_date);

-- 状态约束要放开 SUSPENDED：V1/V4 建表时写死了只有 ACTIVE / SCRAPPED，
-- 校准不合格停用的量具没有合法状态可落，会直接撞约束报 500。
-- 先删后加，重复执行安全。
ALTER TABLE qc_gauge DROP CONSTRAINT IF EXISTS ck_gauge_status;
ALTER TABLE qc_gauge ADD CONSTRAINT ck_gauge_status
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'SCRAPPED'));

COMMENT ON COLUMN qc_gauge.status IS 'ACTIVE 可用 / SUSPENDED 校准不合格停用 / SCRAPPED 已报废';
