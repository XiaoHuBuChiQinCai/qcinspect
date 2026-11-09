<template>
  <el-card>
    <div class="bar">
      <span class="title">账号与角色</span>
      <div class="spacer" />
      <el-button type="primary" @click="create">新增账号</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="mb"
      title="判定与放行分属两个岗位：检验员负责录入与提交，审核员负责放行归档。审核员不能审核自己录入的单据。"
    />

    <!--
      推送排障提示放在这一页，是因为修的办法就在这一页：补企业微信账号。
      放在「系统健康」那种页面里，管理员看到故障还得再跳一次才知道怎么改。
    -->
    <el-alert
      v-if="push.enabled && attentionTotal > 0"
      type="warning"
      :closable="false"
      class="mb"
      show-icon
    >
      <template #title>
        有 {{ attentionTotal }} 条通知没送到手机：{{ attentionDetail }}
        <el-button link type="primary" @click="openProblems">查看并重发</el-button>
      </template>
    </el-alert>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="username" label="账号" width="140" />
      <el-table-column prop="realName" label="姓名" width="140" />
      <el-table-column label="角色" width="130">
        <template #default="{ row }">
          <el-tag :type="roleType(row.role)" size="small">{{ roleText(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="企业微信" width="150">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.wecomUserid"
            :content="`UserID：${row.wecomUserid}`"
            placement="top"
          >
            <el-tag type="success" size="small">已绑定</el-tag>
          </el-tooltip>
          <el-tag v-else type="info" size="small">只收站内通知</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="200">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" @click="resetPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="problemVisible" title="没送到手机的通知" size="760px">
      <el-alert
        type="info"
        :closable="false"
        class="mb"
        title="「接收人没绑企业微信」要在这里补账号；「发送失败」多半是凭证或服务器 IP 没进白名单，错误原文里写着原因。"
      />
      <el-table :data="push.problems" size="small">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="receiver" label="接收人" width="100" />
        <el-table-column prop="bizNo" label="单号" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="stateType(row.pushState)" size="small">{{ stateText(row.pushState) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="220">
          <template #default="{ row }">
            <span class="err">{{ row.pushError || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" :loading="retrying === row.id" @click="retry(row)">重发</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="visible" :title="form.id ? '编辑账号' : '新增账号'" width="460px">
      <el-form label-width="96px">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" placeholder="登录名，建议用拼音或工号" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="显示在检验单、日志上的名字" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员 · 全部权限" value="ADMIN" />
            <el-option label="检验员 · 录入与提交" value="INSPECTOR" />
            <el-option label="审核员 · 放行与退回" value="REVIEWER" />
          </el-select>
        </el-form-item>
        <el-form-item label="企业微信">
          <el-input v-model="form.wecomUserid" placeholder="通讯录里的 UserID，留空只收站内通知" />
          <div class="hint">不是手机号、也不是姓名。填错的表现是「企业微信里没有这个账号」。</div>
        </el-form-item>
        <el-form-item :label="form.id ? '新密码' : '密码'" :required="!form.id">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改密码' : '至少 6 位'"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const rows = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)

/* 企业微信推送的排障数据。通道没开时不请求，免得这一页平白多一个 403/404 */
const push = reactive({ enabled: false, missing: '', states: {}, problems: [] })
const problemVisible = ref(false)
const retrying = ref(null)

const form = reactive({
  id: null,
  username: '',
  realName: '',
  role: 'INSPECTOR',
  password: '',
  enabled: true,
  wecomUserid: ''
})

const roleText = r => ({ ADMIN: '管理员', INSPECTOR: '检验员', REVIEWER: '审核员' }[r] || r)
const roleType = r => ({ ADMIN: 'warning', INSPECTOR: '', REVIEWER: 'success' }[r] || 'info')

const stateText = s => ({
  PENDING: '排队中', SENT: '已发送', FAILED: '发送失败', SKIPPED: '未绑定'
}[s] || s)
const stateType = s => ({ PENDING: 'info', SENT: 'success', FAILED: 'danger', SKIPPED: 'warning' }[s] || 'info')

const skippedCount = computed(() => push.states.SKIPPED || 0)
const failedCount = computed(() => push.states.FAILED || 0)
const pendingCount = computed(() => push.states.PENDING || 0)
const attentionTotal = computed(() => skippedCount.value + failedCount.value + pendingCount.value)
const attentionDetail = computed(() => {
  const parts = []
  if (failedCount.value) parts.push(`${failedCount.value} 条发送失败`)
  if (skippedCount.value) parts.push(`${skippedCount.value} 条接收人没绑企业微信`)
  if (pendingCount.value) parts.push(`${pendingCount.value} 条还在排队`)
  return parts.join('、')
})

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/auth/users')
  } finally {
    loading.value = false
  }
}

async function loadPush() {
  try {
    const data = await http.get('/system/notice-push?recent=20')
    push.enabled = !!data.enabled
    push.missing = data.missing || ''
    push.states = data.states || {}
    push.problems = data.problems || []
  } catch {
    // 通道没开或接口不可用时，这一页照常可用——账号管理不该被推送配置拖住
    push.enabled = false
    push.problems = []
  }
}

function openProblems() {
  problemVisible.value = true
  loadPush()
}

async function retry(row) {
  retrying.value = row.id
  try {
    const data = await http.post(`/system/notice-push/${row.id}/retry`)
    // 后端说的是实话：没绑账号时它不会假装已入队
    ElMessage[data.queued ? 'success' : 'warning'](data.message)
    await loadPush()
  } finally {
    retrying.value = null
  }
}

function create() {
  Object.assign(form, {
    id: null,
    username: '',
    realName: '',
    role: 'INSPECTOR',
    password: '',
    enabled: true,
    wecomUserid: ''
  })
  visible.value = true
}

function edit(row) {
  Object.assign(form, {
    id: row.id,
    username: row.username,
    realName: row.realName,
    role: row.role,
    password: '',
    enabled: row.enabled !== false,
    wecomUserid: row.wecomUserid || ''
  })
  visible.value = true
}

async function save() {
  if (!form.username.trim()) {
    ElMessage.warning('账号不能为空')
    return
  }
  if (!form.id && form.password.length < 6) {
    ElMessage.warning('新建账号的密码至少 6 位')
    return
  }
  saving.value = true
  try {
    await http.post('/auth/users', {
      id: form.id,
      username: form.username.trim(),
      realName: form.realName,
      role: form.role,
      enabled: form.enabled,
      // 空字符串要发 null：后端把 null 当「解绑」，空串会被当成一个要校验的值
      wecomUserid: form.wecomUserid.trim() || null,
      password: form.password || null
    })
    ElMessage.success('已保存')
    visible.value = false
    await load()
    await loadPush()
  } finally {
    saving.value = false
  }
}

async function resetPwd(row) {
  let pwd
  try {
    const { value } = await ElMessageBox.prompt(`为 ${row.username} 设置新密码`, '重置密码', {
      inputType: 'password',
      inputPattern: /^.{6,}$/,
      inputErrorMessage: '密码至少 6 位'
    })
    pwd = value
  } catch {
    return
  }
  await http.post(`/auth/users/${row.id}/reset-password`, { newPassword: pwd })
  ElMessage.success('密码已重置，请通知本人尽快修改')
}

onMounted(() => {
  load()
  loadPush()
})
</script>

<style scoped>
.bar { display: flex; align-items: center; margin-bottom: 16px; }
.title { font-size: 15px; font-weight: 500; }
.spacer { flex: 1; }
.mb { margin-bottom: 16px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; margin-top: 2px; }
.err { font-size: 12px; color: var(--el-text-color-regular); word-break: break-all; }
</style>
