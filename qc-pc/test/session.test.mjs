import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseStoredUser, TOKEN_KEY, USER_KEY } from '../src/utils/session.js'

/**
 * 「未知角色」这个 bug 不报错、不白屏，只是右上角标签变成灰的、侧栏少半截，
 * 而且只在「这台浏览器先用手机 H5 登录过」时出现——最难复现的那种。
 * 所以口径抽成纯函数，在这里锁死。
 */

// uni-app H5 的 uni.setStorageSync 存对象时写的真实格式
const H5_WRAPPED = '{"type":"object","data":{"id":1,"username":"admin","realName":"张三","role":"ADMIN"}}'
const PC_RAW = '{"id":1,"username":"admin","realName":"张三","role":"ADMIN"}'

test('PC 自己写的裸 JSON 能读出来', () => {
  const u = parseStoredUser(PC_RAW)
  assert.equal(u.role, 'ADMIN')
  assert.equal(u.username, 'admin')
})

test('H5 写进来的包装值要剥一层，否则右上角就是「未知角色」', () => {
  const u = parseStoredUser(H5_WRAPPED)
  assert.equal(u.role, 'ADMIN', '没剥壳的话这里会是 undefined')
  assert.equal(u.realName, '张三')
})

test('没登录过就是 null，不是 {} 也不是抛异常', () => {
  assert.equal(parseStoredUser(null), null)
  assert.equal(parseStoredUser(''), null)
  assert.equal(parseStoredUser('null'), null)
  assert.equal(parseStoredUser(undefined), null)
})

test('存坏了当没登录，不能让整个页面崩在启动阶段', () => {
  assert.equal(parseStoredUser('{'), null)
  assert.equal(parseStoredUser('admin'), null)
  assert.equal(parseStoredUser('"admin"'), null)
  assert.equal(parseStoredUser('123'), null)
  assert.equal(parseStoredUser('[1,2]'), null)
})

test('包装壳里的 data 是空值时不返回空壳对象', () => {
  // saveSession(token, null) 之后就是这个样子
  assert.equal(parseStoredUser('{"type":"object","data":null}'), null)
})

test('用户对象自己带 type 字段不会被误剥', () => {
  // 三个键就不符合 uni 的包装特征，按原样返回
  const raw = '{"type":"object","data":{"a":1},"role":"ADMIN"}'
  const u = parseStoredUser(raw)
  assert.equal(u.role, 'ADMIN')
})

/**
 * 两端必须用不同的 localStorage 键。
 *
 * 线上 PC 在 /、H5 在 /h5/，同一个域共用一份 localStorage；而两边的序列化格式
 * 天生不兼容（uni 会包一层）。共用键的后果不是「互相顶掉」那么客气——是后登录的
 * 那个前端能正常调接口，但读不出角色和姓名，看起来像权限丢了。
 */
test('PC 与移动端不共用会话键', () => {
  const mobile = readFileSync(new URL('../../qc-mobile/common/api.js', import.meta.url), 'utf8')
  const pick = (src, name) => {
    const m = src.match(new RegExp(`const ${name} = '([^']+)'`))
    assert.ok(m, `没在源码里找到 ${name}`)
    return m[1]
  }
  const mobileToken = pick(mobile, 'TOKEN_KEY')
  const mobileUser = pick(mobile, 'USER_KEY')

  assert.notEqual(mobileToken, TOKEN_KEY, '移动端的 token 键和 PC 撞了')
  assert.notEqual(mobileUser, USER_KEY, '移动端的用户键和 PC 撞了')
  // PC 保持老键名，线上已登录的电脑不用重新登录
  assert.equal(TOKEN_KEY, 'qc_token')
  assert.equal(USER_KEY, 'qc_user')
})
