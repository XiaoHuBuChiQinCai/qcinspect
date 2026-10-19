<template>
  <router-view v-if="isLogin" />

  <el-container v-else class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">工件质检</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">待检批次</el-menu-item>
        <el-menu-item index="/inspections">检验单</el-menu-item>
        <el-menu-item index="/standards">检验标准</el-menu-item>
        <el-menu-item index="/defects">不良处置</el-menu-item>
        <el-menu-item index="/gauges">量具台账</el-menu-item>
        <el-menu-item index="/trace">批次追溯</el-menu-item>
        <el-menu-item index="/stat">质量看板</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/defect-codes">不良代码</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/users">用户管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="dept">品质管理部</span>
        <div class="spacer" />
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
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { clearSession, getStoredUser } from './api/http'

const route = useRoute()
const router = useRouter()

const user = ref(getStoredUser() || {})
const isLogin = computed(() => route.path === '/login')

const isAdmin = computed(() => user.value.role === 'ADMIN')
const roleText = computed(
  () => ({ ADMIN: '管理员', INSPECTOR: '检验员', REVIEWER: '审核员' }[user.value.role] || '未知角色')
)
const roleType = computed(
  () => ({ ADMIN: 'warning', INSPECTOR: '', REVIEWER: 'success' }[user.value.role] || 'info')
)

const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })

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
</style>
