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

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="username" label="账号" width="140" />
      <el-table-column prop="realName" label="姓名" width="140" />
      <el-table-column label="角色" width="130">
        <template #default="{ row }">
          <el-tag :type="roleType(row.role)" size="small">{{ roleText(row.role) }}</el-tag>
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

    <el-dialog v-model="visible" :title="form.id ? '编辑账号' : '新增账号'" width="460px">
      <el-form label-width="80px">
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const rows = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)

const form = reactive({
  id: null,
  username: '',
  realName: '',
  role: 'INSPECTOR',
  password: '',
  enabled: true
})

const roleText = r => ({ ADMIN: '管理员', INSPECTOR: '检验员', REVIEWER: '审核员' }[r] || r)
const roleType = r => ({ ADMIN: 'warning', INSPECTOR: '', REVIEWER: 'success' }[r] || 'info')

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

function create() {
  Object.assign(form, {
    id: null,
    username: '',
    realName: '',
    role: 'INSPECTOR',
    password: '',
    enabled: true
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
    enabled: row.enabled !== false
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
      password: form.password || null
    })
    ElMessage.success('已保存')
    visible.value = false
    await load()
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

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; margin-bottom: 16px; }
.title { font-size: 15px; font-weight: 500; }
.spacer { flex: 1; }
.mb { margin-bottom: 16px; }
</style>
