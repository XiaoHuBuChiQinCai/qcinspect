<template>
  <el-card>
    <div class="bar">
      <el-input
        v-model="keyword"
        placeholder="编码 / 全称 / 简称"
        clearable
        style="width: 220px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option label="在用" value="ACTIVE" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
      <el-button @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="create">新增供应商</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="mb"
      title="供应商原来只是批次上的一行文本，同一家厂录成「宁波精铸一厂」「宁波精铸一厂 」「宁波精铸」就会在合格率排名里变成三家，最差的那家被稀释掉。所以这里立档案，批次只许选，不许手打。"
    />

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="supplierCode" label="编码" width="110" />
      <el-table-column label="供应商" min-width="200">
        <template #default="{ row }">
          <div class="name">{{ row.supplier_name }}</div>
          <div v-if="row.short_name" class="short">{{ row.short_name }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="110">
        <template #default="{ row }">{{ categoryText(row.category) }}</template>
      </el-table-column>
      <el-table-column label="评级" width="80">
        <template #default="{ row }">
          <!-- 未评级显示「—」，不要显示 C：没评过级和评成差评是两回事 -->
          <el-tag v-if="row.level" :type="levelType(row.level)" size="small">{{ row.level }}</el-tag>
          <span v-else class="dash">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="contact" label="联系人" width="110" />
      <el-table-column prop="phone" label="电话" width="130" />
      <el-table-column label="批次" width="80" align="right">
        <template #default="{ row }">{{ row.batches }}</template>
      </el-table-column>
      <el-table-column label="件级合格率" width="120" align="right">
        <template #default="{ row }">
          <span :class="rateClass(row)">{{ pct(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '在用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" :type="row.status === 'ACTIVE' ? 'warning' : 'success'" plain @click="toggle(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tip">
      停用不删档：历史批次照样能按这家厂统计、能追溯。停用只是让它不再出现在新建批次的下拉里。
    </div>

    <el-dialog v-model="visible" :title="form.id ? '编辑供应商' : '新增供应商'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="全称" required>
          <el-input v-model="form.supplierName" placeholder="营业执照上的名称，同名两家厂请加「一分厂」区分" maxlength="128" />
        </el-form-item>
        <el-form-item label="简称">
          <el-input v-model="form.shortName" placeholder="列表和报表上用，如 宁波精铸" maxlength="64" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input v-model="form.supplierCode" placeholder="留空自动生成 SUPxxxx" maxlength="32" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width: 100%">
            <el-option label="原材料" value="MATERIAL" />
            <el-option label="外协加工" value="OUTSOURCE" />
            <el-option label="包装材料" value="PACKAGING" />
            <el-option label="检测等服务" value="SERVICE" />
          </el-select>
        </el-form-item>
        <el-form-item label="评级">
          <el-select v-model="form.level" clearable placeholder="未评级" style="width: 100%">
            <el-option label="A · 优质" value="A" />
            <el-option label="B · 合格" value="B" />
            <el-option label="C · 需改善" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" maxlength="64" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" maxlength="64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="供货范围、账期、资质证书到期时间等" />
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
const keyword = ref('')
const filterStatus = ref('')
const visible = ref(false)
const saving = ref(false)

const form = reactive({
  id: null,
  supplierCode: '',
  supplierName: '',
  shortName: '',
  category: 'MATERIAL',
  level: '',
  contact: '',
  phone: '',
  remark: ''
})

const categoryText = c => ({
  MATERIAL: '原材料', OUTSOURCE: '外协加工', PACKAGING: '包装材料', SERVICE: '服务'
}[c] || c)

const levelType = l => ({ A: 'success', B: '', C: 'warning' }[l] || 'info')

function pct(row) {
  const total = Number(row.piece_total || 0)
  // 一件都还没检过的供应商不显示 0%，那是「没数据」不是「全不合格」
  if (!total) return '-'
  return ((Number(row.piece_pass || 0) / total) * 100).toFixed(1) + '%'
}

function rateClass(row) {
  const total = Number(row.piece_total || 0)
  if (!total) return ''
  const rate = Number(row.piece_pass || 0) / total
  if (rate < 0.9) return 'bad'
  if (rate < 0.98) return 'warn2'
  return ''
}

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/suppliers', {
      params: {
        keyword: keyword.value.trim() || undefined,
        status: filterStatus.value || undefined
      }
    })
  } finally {
    loading.value = false
  }
}

function create() {
  Object.assign(form, {
    id: null, supplierCode: '', supplierName: '', shortName: '',
    category: 'MATERIAL', level: '', contact: '', phone: '', remark: ''
  })
  visible.value = true
}

function edit(row) {
  Object.assign(form, {
    id: row.id,
    supplierCode: row.supplier_code || '',
    supplierName: row.supplier_name || '',
    shortName: row.short_name || '',
    category: row.category || 'MATERIAL',
    level: row.level || '',
    contact: row.contact || '',
    phone: row.phone || '',
    remark: row.remark || ''
  })
  visible.value = true
}

async function save() {
  if (!form.supplierName.trim()) {
    ElMessage.warning('供应商全称不能为空')
    return
  }
  saving.value = true
  try {
    await http.post('/suppliers', {
      ...form,
      supplierName: form.supplierName.trim(),
      shortName: form.shortName.trim() || null,
      supplierCode: form.supplierCode.trim() || null,
      level: form.level || null,
      contact: form.contact.trim() || null,
      phone: form.phone.trim() || null,
      remark: form.remark.trim() || null
    })
    ElMessage.success('已保存')
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  const disabling = row.status === 'ACTIVE'
  if (disabling) {
    // 停用不是删除，但确实会让新建批次选不到这家厂，问一句
    await ElMessageBox.confirm(
      `停用「${row.supplier_name}」后，新建批次时选不到它，历史批次与统计不受影响。确定停用？`,
      '停用供应商',
      { type: 'warning' }
    )
  }
  await http.post(`/suppliers/${row.id}/status?status=${disabling ? 'DISABLED' : 'ACTIVE'}`)
  ElMessage.success(disabling ? '已停用' : '已启用')
  await load()
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 16px; }
.spacer { flex: 1; }
.mb { margin-bottom: 16px; }
.name { font-weight: 500; }
.short { font-size: 12px; color: #909399; }
.dash { color: #c0c4cc; }
.tip { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.6; }
.bad { color: #f56c6c; font-weight: 600; }
.warn2 { color: #e6a23c; font-weight: 600; }
</style>
