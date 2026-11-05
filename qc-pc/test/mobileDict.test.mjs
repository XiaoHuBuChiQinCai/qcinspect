import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {
  DISPOSITION_TEXT,
  DISPOSITION_STATUS_TEXT,
  BATCH_STATUS_TEXT,
  INSPECTION_RESULT_TEXT,
  dispositionText,
  dispositionStatusText,
  batchStatusText,
  resultText,
  fmtTime
} from '../../qc-mobile/common/dict.js'

/**
 * 移动端的文案映射只有这一份（qc-mobile/common/dict.js），
 * 而「到底有哪几个值」的唯一权威是数据库 CHECK 约束——不是前端，也不是后端枚举类。
 *
 * 这里直接读 db/*.sql 里最后一次出现的约束定义来比对：
 * CHECK 约束是「后来者覆盖前者」，V1 定的清单可能早被后面的脚本重建过（ck_log_action
 * 就吃过这个亏），所以按版本号排序、取最后一个，不能只读 V1。
 */
const DB_DIR = new URL('../../db/', import.meta.url)

function sqlFiles() {
  return fs
    .readdirSync(DB_DIR)
    .filter(f => /^V\d+__.+\.sql$/.test(f))
    .sort((a, b) => parseInt(a.slice(1), 10) - parseInt(b.slice(1), 10))
}

/** 找约束名最后一次出现在 CHECK (... IN (...)) 里的值清单 */
function lastCheckValues(constraint) {
  // 约束名后面必须紧跟 CHECK（中间只允许空白），否则 ck_defect_disposition
  // 会误匹配到 ck_defect_disposition_status
  const re = new RegExp(constraint + '\\s+CHECK[\\s\\S]{0,200}?IN\\s*\\(([^)]*)\\)', 'g')
  let values = null
  for (const f of sqlFiles()) {
    const sql = fs.readFileSync(new URL(f, DB_DIR), 'utf8')
    let m
    while ((m = re.exec(sql)) !== null) {
      const hit = [...m[1].matchAll(/'([A-Z_]+)'/g)].map(x => x[1])
      if (hit.length) values = hit
    }
  }
  return values
}

const sortKeys = o => Object.keys(o).sort()
const sortVals = v => [...v].sort()

test('不良处置方式与 ck_defect_disposition 一致', () => {
  const db = lastCheckValues('ck_defect_disposition')
  assert.ok(db, 'db/*.sql 里找不到 ck_defect_disposition，脚本被改名了要回来看这里')
  assert.deepEqual(sortKeys(DISPOSITION_TEXT), sortVals(db))
})

test('处置状态与 ck_defect_disposition_status 一致', () => {
  const db = lastCheckValues('ck_defect_disposition_status')
  assert.ok(db, 'db/*.sql 里找不到 ck_defect_disposition_status')
  assert.deepEqual(sortKeys(DISPOSITION_STATUS_TEXT), sortVals(db))
})

test('批次状态与 ck_batch_status 一致', () => {
  const db = lastCheckValues('ck_batch_status')
  assert.ok(db, 'db/*.sql 里找不到 ck_batch_status')
  assert.deepEqual(sortKeys(BATCH_STATUS_TEXT), sortVals(db))
})

test('检验结论与 ck_insp_result 一致', () => {
  const db = lastCheckValues('ck_insp_result')
  assert.ok(db, 'db/*.sql 里找不到 ck_insp_result')
  assert.deepEqual(sortKeys(INSPECTION_RESULT_TEXT), sortVals(db))
})

test('认不出的值不能变成空白格子', () => {
  // 后端加了新枚举、前端没跟上时，界面上宁可显示原值也不能显示空
  assert.equal(dispositionText('NEW_THING'), '未定')
  assert.equal(dispositionStatusText('NEW_THING'), 'NEW_THING')
  assert.equal(batchStatusText('NEW_THING'), 'NEW_THING')
  assert.equal(resultText('NEW_THING'), '未判定')
  // 空值
  assert.equal(dispositionStatusText(null), '')
  assert.equal(batchStatusText(undefined), '')
})

test('作废不是不合格', () => {
  assert.equal(resultText('NA'), '已作废')
  assert.notEqual(resultText('NA'), resultText('FAIL'))
})

test('时间只到分钟，空值给破折号', () => {
  assert.equal(fmtTime('2026-10-29T14:03:27'), '2026-10-29 14:03')
  assert.equal(fmtTime(null), '—')
  assert.equal(fmtTime(''), '—')
})
