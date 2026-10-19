<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand">
        <div class="brand-title">工件质检系统</div>
        <div class="brand-sub">Quality Inspection</div>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" size="large" placeholder="账号" autocomplete="username" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
      <div class="hint">首次部署的默认账号见 README，登录后请立即修改密码</div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { saveSession } from '../api/http'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const data = await http.post('/auth/login', { ...form })
    saveSession(data.token, data.user)
    ElMessage.success(`欢迎，${data.user.realName || data.user.username}`)
    router.replace(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4eaf2 100%);
}
.login-card {
  width: 380px;
  padding: 40px 36px 28px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
.brand { text-align: center; margin-bottom: 28px; }
.brand-title { font-size: 22px; font-weight: 600; letter-spacing: 3px; }
.brand-sub { margin-top: 6px; font-size: 12px; color: #909399; letter-spacing: 1px; }
.submit { width: 100%; margin-top: 4px; letter-spacing: 4px; }
.hint { margin-top: 18px; font-size: 12px; color: #b1b3b8; text-align: center; line-height: 1.6; }
</style>
