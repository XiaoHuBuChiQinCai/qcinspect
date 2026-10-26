<template>
  <router-view v-if="isLogin" />

  <el-container v-else class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">工件质检</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">工作台</el-menu-item>
        <el-menu-item index="/batches">待检批次</el-menu-item>
        <el-menu-item index="/inspections">检验单</el-menu-item>
        <el-menu-item index="/standards">检验标准</el-menu-item>
        <el-menu-item index="/defects">不良处置</el-menu-item>
        <el-menu-item index="/gauges">量具台账</el-menu-item>
        <el-menu-item index="/trace">批次追溯</el-menu-item>
        <el-menu-item index="/shipments">出货记录</el-menu-item>
        <el-menu-item index="/complaints">客户反馈</el-menu-item>
        <el-menu-item index="/stat">质量看板</el-menu-item>
        <el-menu-item index="/monthly">质量月报</el-menu-item>
        <el-menu-item index="/capability">过程能力</el-menu-item>
        <el-menu-item v-if="canReview" index="/inspectors">检验员分析</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/master-data">主数据</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/defect-codes">不良代码</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/suppliers">供应商档案</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/users">用户管理</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/audit">操作日志</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="dept">品质管理部</span>
        <div class="spacer" />

        <!-- 通知：审核退回、让步放行这类「要你动一下」的事件从后端主动推到这里 -->
        <el-popover v-model:visible="noticeVisible" placement="bottom-end" :width="380" trigger="click" @show="loadNotices">
          <template #reference>
            <span class="bell">
              <el-badge :value="unread" :hidden="!unread" :max="99">
                <span class="bell-text">通知</span>
              </el-badge>
            </span>
          </template>
          <div class="notice-head">
            <span>未读 {{ unread }} 条</span>
            <el-button link type="primary" size="small" :disabled="!unread" @click="readAll">全部已读</el-button>
          </div>
          <div class="notice-list">
            <div v-if="!notices.length" class="notice-empty">没有新通知</div>
            <div
              v-for="n in notices"
              :key="n.id"
              :class="['notice', n.readFlag ? 'read' : '']"
              @click="openNotice(n)"
            >
              <div class="n-title">{{ n.title }}</div>
              <div v-if="n.content" class="n-content">{{ n.content }}</div>
              <div class="n-time">{{ n.createdAt }}</div>
            </div>
          </div>
        </el-popover>

        <el-dropdown @command="onCommand">
          <span class="user">
            {{ user.realName || user.username }}
            <el-tag size="small" :type="roleType" effect="plain">{{ roleText }}</el-tag>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="pwd">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="pwdVisible" title="修改密码" width="420px">
    <el-form label-width="80px">
      <el-form-item label="原密码">
        <el-input v-model="pwd.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="确认">
        <el-input v-model="pwd.confirm" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdVisible = false">取消</el-button>
      <el-button type="primary" :loading="pwdSaving" @click="submitPwd">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { clearSession, getStoredUser, getToken } from './api/http'

const route = useRoute()
const router = useRouter()

const user = ref(getStoredUser() || {})
const isLogin = computed(() => route.path === '/login')

const isAdmin = computed(() => user.value.role === 'ADMIN')
// 审核员也要能看：他的活就是判断该抽查哪张单
const canReview = computed(() => ['ADMIN', 'REVIEWER'].includes(user.value.role))
const roleText = computed(
  () => ({ ADMIN: '管理员', INSPECTOR: '检验员', REVIEWER: '审核员' }[user.value.role] || '未知角色')
)
const roleType = computed(
  () => ({ ADMIN: 'warning', INSPECTOR: '', REVIEWER: 'success' }[user.value.role] || 'info')
)

const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })

// ---------------- 通知 ----------------
const unread = ref(0)
const notices = ref([])
const noticeVisible = ref(false)
let noticeTimer = null

/** 角标：轮询用的，失败不弹框（silent），否则后端一抖每分钟糊一个错误提示 */
async function loadNoticeSummary() {
  if (!getToken()) return
  try {
    const r = await http.get('/notices/summary', { silent: true })
    unread.value = r?.unread || 0
  } catch {
    // 角标是锦上添花，取不到就保持原值
  }
}

async function loadNotices() {
  if (!getToken()) return
  notices.value = await http.get('/notices', { params: { limit: 20 } })
}

async function openNotice(n) {
  if (!n.readFlag) {
    await http.post(`/notices/${n.id}/read`)
    n.readFlag = 1
    unread.value = Math.max(0, unread.value - 1)
  }
  noticeVisible.value = false
  if (n.bizType === 'INSPECTION' && n.bizId) {
    router.push(`/inspection/${n.bizId}`)
  } else if (n.bizType === 'DEFECT') {
    router.push('/defects')
  }
}

async function readAll() {
  await http.post('/notices/read-all')
  notices.value.forEach(n => {
    n.readFlag = 1
  })
  unread.value = 0
}

onMounted(() => {
  loadNoticeSummary()
  // 质检现场没有推送通道，用轮询兜底：一分钟一次足够，退回来的单不会等半小时才被看见
  noticeTimer = setInterval(loadNoticeSummary, 60000)
})

onUnmounted(() => {
  if (noticeTimer) clearInterval(noticeTimer)
})

async function onCommand(cmd) {
  if (cmd === 'pwd') {
    pwd.oldPassword = ''
    pwd.newPassword = ''
    pwd.confirm = ''
    pwdVisible.value = true
    return
  }
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  clearSession()
  router.replace('/login')
}

async function submitPwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写原密码和新密码')
    return
  }
  if (pwd.newPassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdSaving.value = true
  try {
    await http.post('/auth/password', { oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    pwdVisible.value = false
    clearSession()
    router.replace('/login')
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style>
html, body, #app { height: 100%; margin: 0; }
.layout { height: 100%; }
.aside { border-right: 1px solid #e4e7ed; background: #fff; }
.logo { height: 56px; line-height: 56px; text-align: center; font-size: 16px; font-weight: 500; letter-spacing: 2px; }
.header { display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fff; }
.dept { color: #606266; }
.spacer { flex: 1; }
.user { cursor: pointer; color: #303133; display: inline-flex; align-items: center; gap: 6px; outline: none; }

/* ---- 通知 ---- */
.bell { cursor: pointer; margin-right: 18px; outline: none; }
.bell-text { display: inline-block; padding: 0 10px; font-size: 13px; color: #606266; line-height: 22px; }
.notice-head {
  display: flex; align-items: center; justify-content: space-between;
  padding-bottom: 8px; border-bottom: 1px solid var(--el-border-color-lighter); font-size: 13px;
}
.notice-list { max-height: 360px; overflow-y: auto; }
.notice { padding: 10px 4px; border-bottom: 1px solid var(--el-border-color-lighter); cursor: pointer; }
.notice:hover { background: var(--el-fill-color-light); }
.notice.read { opacity: .6; }
.n-title { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); }
.n-content { font-size: 12px; color: var(--el-text-color-regular); margin-top: 4px; line-height: 1.5; }
.n-time { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 4px; }
.notice-empty { text-align: center; color: var(--el-text-color-secondary); font-size: 13px; padding: 24px 0; }
</style>
