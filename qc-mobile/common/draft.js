/**
 * 未提交填写的本地保护。
 *
 * <p>服务端的「暂存」按钮救不了这个场景：车间 wifi 断了，暂存也发不出去。
 * 而一张单全表 30 多个测点，填了半小时，手机切个后台被系统回收、
 * 手滑点了返回、H5 页面被刷新一下，内容就全没了。这是最容易让检验员
 * 放弃手机端、退回纸笔的地方——比任何功能缺失都致命。
 *
 * <p>所以在本地另存一份。它不是「草稿」的第二权威，只是**一份等着被提交的抄本**：
 * 提交成功即删，服务端始终是唯一可信来源。
 *
 * <p>这个文件零依赖，`qc-pc/test/mobileDraft.test.mjs` 直接 import 跑断言。
 */

export const DRAFT_PREFIX = 'qc_draft_'
/** 本机最多留几张单的抄本。留太多会在 localStorage 里堆成年累月的垃圾 */
export const KEEP_DRAFTS = 5
/** 超过这个时间的抄本不再提示恢复：标准可能已经改版，恢复一份三周前的填写反而害人 */
export const DRAFT_TTL_MS = 72 * 3600 * 1000

/**
 * 抄本的 key。
 *
 * <p>必须同时带**人**和**单**。只带单号的话，同一台平板换个人登录，
 * 甲填了一半的单会被乙看见、乙一提交就变成了「乙录的数据」——
 * 质量记录里这是伪造。带人之后最坏情况只是甲自己重登能拿回来。
 *
 * @param user 当前登录用户对象（用 id，没有 id 退回展示名）
 * @param inspectionId 检验单 id
 * @returns key；缺任何一项时返回空串，调用方据此跳过
 */
export function draftKey(user, inspectionId) {
  const who = whoOf(user)
  const id = inspectionId === null || inspectionId === undefined ? '' : String(inspectionId).trim()
  if (!who || !id) return ''
  return DRAFT_PREFIX + who + '_' + id
}

function whoOf(user) {
  if (!user) return ''
  if (user.id !== null && user.id !== undefined && String(user.id).trim() !== '') {
    return 'u' + String(user.id).trim()
  }
  const name = user.realName || user.username
  return name ? 'n' + String(name).trim() : ''
}

/**
 * 把 `collectLines()` 的结果压成 `{ lineId: {values, text, gauge} }`。
 *
 * <p>两侧都过这一道，比较时就不必操心「'' 还是 null」「数字还是字符串」这些差异——
 * 这类差异会让 `'' vs null` 被误判成「有改动」，于是每打开一次页面都弹一次
 * 「要恢复上次填写吗」，问到人直接把提示关掉、真丢数据那次也不看了。
 */
export function normalizeLines(lines) {
  const map = {}
  const list = Array.isArray(lines) ? lines : []
  list.forEach(l => {
    if (!l || l.lineId === null || l.lineId === undefined) return
    const values = Array.isArray(l.values)
      ? l.values.map(v => (v === null || v === undefined ? '' : String(v)))
      : []
    map[String(l.lineId)] = {
      values,
      text: l.textValue === null || l.textValue === undefined ? '' : String(l.textValue),
      gauge: l.gaugeId === null || l.gaugeId === undefined ? '' : String(l.gaugeId)
    }
  })
  return map
}

/**
 * 两组填写有哪些行不一样。
 *
 * @returns 有差异的 lineId 数组（尽量还原成数字，方便页面里定位到行）
 */
export function diffLines(current, saved) {
  const cur = normalizeLines(current)
  const old = normalizeLines(saved)
  const ids = new Set(Object.keys(cur).concat(Object.keys(old)))
  const out = []
  ids.forEach(id => {
    if (!sameLine(cur[id], old[id])) {
      const n = Number(id)
      out.push(Number.isNaN(n) ? id : n)
    }
  })
  return out
}

function sameLine(a, b) {
  if (!a || !b) return !a && !b
  if (a.text !== b.text || a.gauge !== b.gauge) return false
  if (a.values.length !== b.values.length) return false
  for (let i = 0; i < a.values.length; i += 1) {
    if (a.values[i] !== b.values[i]) return false
  }
  return true
}

/**
 * 要不要提示「恢复上次没提交的填写」。
 *
 * <p>只在**确实有内容**、**还没过期**、**和服务端现有内容不一样**时才弹。
 * 和服务端一样说明人已经暂存过了，再问一次纯属打扰。
 *
 * @param draft `{lines, savedAt}`，本地读出来的抄本
 * @param currentLines 服务端刚拉下来的填写
 * @param now 当前毫秒时间戳（由调用方传入，便于测试）
 */
export function shouldRestore({ draft, currentLines, now, ttlMs }) {
  if (!draft || !Array.isArray(draft.lines) || draft.lines.length === 0) return false
  const savedAt = Number(draft.savedAt)
  const at = Number(now)
  if (!Number.isFinite(savedAt) || !Number.isFinite(at)) return false
  const age = at - savedAt
  // 时钟被往回调过时会算出负数；小范围漂移容忍，大范围的直接不信
  if (age < -3600 * 1000) return false
  const ttl = Number(ttlMs) > 0 ? Number(ttlMs) : DRAFT_TTL_MS
  if (age > ttl) return false
  return diffLines(draft.lines, currentLines).length > 0
}

/**
 * 算出该从本机删掉哪些旧抄本。
 *
 * <p>localStorage 不会自己回收；不主动清，一年后手机上躺着几百张单的残片。
 * 规则：过期的全删，剩下的按时间留最近的 `keep` 张（`keepKey` 是刚写的那张，必留）。
 *
 * @param index `[{key, savedAt}]`，本机抄本的索引
 * @param keepKey 刚写入、必须保留的 key
 * @returns 待删除的 key 数组
 */
export function pruneIndex(index, keepKey, now, ttlMs, keep) {
  const limit = Number(keep) > 0 ? Number(keep) : KEEP_DRAFTS
  const ttl = Number(ttlMs) > 0 ? Number(ttlMs) : DRAFT_TTL_MS
  const at = Number(now)
  const others = (Array.isArray(index) ? index : []).filter(e => e && e.key && e.key !== keepKey)

  const dead = []
  const alive = []
  others.forEach(e => {
    const savedAt = Number(e.savedAt)
    if (!Number.isFinite(savedAt) || at - savedAt > ttl) dead.push(e.key)
    else alive.push(e)
  })

  alive.sort((a, b) => Number(b.savedAt) - Number(a.savedAt))
  // keepKey 自己占一个名额
  alive.slice(Math.max(limit - 1, 0)).forEach(e => dead.push(e.key))
  return dead
}
