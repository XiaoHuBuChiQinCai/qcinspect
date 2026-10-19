<template>
  <el-card>
    <div class="bar">
      <el-select v-model="filterCategory" placeholder="全部分类" clearable style="width: 150px" @change="load">
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
      <el-button @click="load">刷新</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="create">新增不良代码</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="mb"
      title="不良代码是不良帕累托的统计维度。允许自由输入的话，同一种缺陷会被写成七八个名字，帕累托就废了，所以这张字典要专人维护。"
    />

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="code" label="代码" width="150" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column label="严重度" width="120">
        <template #default="{ row }">
          <el-tag :type="severityType(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="200" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑不良代码' : '新增不良代码'" width="440px">
      <el-form label-width="80px">
        <el-form-item label="代码" required>
          <el-input v-model="form.code" placeholder="如 D-SCRATCH，建议带 D- 前缀" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 划伤" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" allow-create filterable style="width: 100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option label="严重 · 影响安全或功能" value="CRITICAL" />
            <el-option label="主要 · 影响装配或客户感知" value="MAJOR" />
            <el-option label="次要 · 外观瑕疵" value="MINOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="典型表现形式，便于新人判断" />
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
import { ElMessage } from 'element-plus'
import http from '../api/http'

const categories = ['尺寸', '外观', '性能', '材质', '装配']
const rows = ref([])
const loading = ref(false)
const filterCategory = ref('')
const visible = ref(false)
const saving = ref(false)

const form = reactive({
  id: null,
  code: '',
  name: '',
  category: '外观',
  severity: 'MINOR',
  remark: ''
})

const severityText = s => ({ CRITICAL: '严重', MAJOR: '主要', MINOR: '次要' }[s] || s)
const severityType = s => ({ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[s] || 'info')

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/defect-codes', {
      params: filterCategory.value ? { category: filterCategory.value } : {}
    })
  } finally {
    loading.value = false
  }
}

function create() {
  Object.assign(form, { id: null, code: '', name: '', category: '外观', severity: 'MINOR', remark: '' })
  visible.value = true
}

function edit(row) {
  Object.assign(form, {
    id: row.id,
    code: row.code,
    name: row.name,
    category: row.category || '外观',
    severity: row.severity || 'MINOR',
    remark: row.remark || ''
  })
  visible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('代码和名称都是必填')
    return
  }
  saving.value = true
  try {
    await http.post('/defect-codes', { ...form, code: form.code.trim(), name: form.name.trim() })
    ElMessage.success('已保存')
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 16px; }
.spacer { flex: 1; }
.mb { margin-bottom: 16px; }
</style>
