-- 单号计数器。
--
-- 背景：检验单（QC）/ 出货单（SH）/ 客诉（TS）原来都是「先 count(*) 再 +1」取号。
-- 两个人同时开单时会拿到同一个号，撞上唯一索引直接抛 PG 的
-- 「duplicate key value violates unique constraint」，用户看到的是一个 500 和一段英文，
-- 既看不懂也不知道重试一下就好。
--
-- 唯一索引挡住的只是「写进库」，挡不住「号码已经重复分配」这件事本身。要让并发安全，
-- 取号这一步必须原子化：这里用一行计数器 + UPDATE ... RETURNING，同一 prefix 的并发
-- 请求在行锁上排队，各自拿到不同的值，且与单据插入在同一个事务里——
-- 事务回滚时计数一起回滚，既不留空号也不重复。
--
-- prefix = 类型 + 日期（如 QC20261029）。按天一行，一年最多 3 × 365 行。
CREATE TABLE IF NOT EXISTS qc_no_seq (
    prefix      varchar(32) PRIMARY KEY,
    last_no     integer     NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE qc_no_seq IS '单号计数器（prefix = 类型 + 日期），取号用 UPDATE ... RETURNING 保证并发唯一';

-- 存量数据对齐：迁移当天可能已经有单了，计数器从「已有最大号」起步，
-- 否则第一次取号会从 1 开始，与历史单撞号。运行时也会再做一次同样的对齐
-- （见 DocNoService），这里只是把第一次的代价提前到迁移时付掉。
INSERT INTO qc_no_seq (prefix, last_no)
SELECT 'QC' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD'),
       count(*)
  FROM qc_inspection
 WHERE inspection_no LIKE 'QC' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') || '%'
ON CONFLICT (prefix) DO NOTHING;

INSERT INTO qc_no_seq (prefix, last_no)
SELECT 'SH' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD'),
       count(*)
  FROM qc_shipment
 WHERE shipment_no LIKE 'SH' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') || '%'
ON CONFLICT (prefix) DO NOTHING;

INSERT INTO qc_no_seq (prefix, last_no)
SELECT 'TS' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD'),
       count(*)
  FROM qc_complaint
 WHERE complaint_no LIKE 'TS' || to_char(now() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') || '%'
ON CONFLICT (prefix) DO NOTHING;
