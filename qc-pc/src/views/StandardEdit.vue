<template>
  <div v-loading="loading">
    <el-page-header content="检验标准配置" @back="$router.push('/standards')" class="ph" />

    <!-- 表头 -->
    <el-card class="mb">
      <template #header>
        <div class="card-head">
          <span>标准信息</span>
          <el-tag size="small" :type="isDraft ? 'info' : 'success'">
            {{ isDraft ? '草稿（可编辑）' : '已生效（只读）' }}
          </el-tag>
        </div>
      </template>

      <el-form :model="form" label-width="110px" :disabled="!isDraft">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="标准编号" required>
              <el-input v-model="form.standardCode" placeholder="如 ST-FLANGE" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="抽样方式">
              <el-select v-model="form.samplingType" style="width: 100%">
                <el-option label="全检（批次每件都验）" value="FULL" />
                <el-option label="抽检（固定抽 N 件）" value="SAMPLE" />
                <el-option label="免检（不生成检验单）" value="NONE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="抽样件数">
              <el-input-number v-model="form.sampleSize" :min="1" :disabled="form.samplingType !== 'SAMPLE'"
                               style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="允收数 Ac">
              <el-input-number v-model="form.allowedNgCount" :min="0" style="width: 100%" />
              <div class="hint">允许的不合格<strong>件</strong>数，超过即整批判不合格</div>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="备注">
              <el-input v-model="form.remark" placeholder="改版原因、依据图纸版本等" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <!-- 检验项 -->
    <el-card>
      <template #header>
        <div class="card-head">
          <span>检验项（{{ rows.length }} 项，关键项 {{ criticalCount }} 项）</span>
          <div v-if="isDraft">
            <el-button @click="pickVisible = true">添加检验项</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <el-button type="success" :disabled="!rows.length" @click="activate">保存并生效</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="!rows.length" type="info" :closable="false"
                title="还没有配置检验项。点右上角「添加检验项」从检验项主数据里挑。" />

      <el-table v-else :data="rows" size="small" border>
        <el-table-column label="检验项" min-width="140">
          <template #default="{ row }">
            <div class="item-name">{{ row.__item.itemName }}</div>
            <div class="item-sub">{{ row.__item.itemCode }}</div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeName(row.__item.valueType) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="分组" width="120">
          <template #default="{ row }">
            <el-input v-model="row.groupName" size="small" :disabled="!isDraft"
                      :placeholder="row.__item.groupName || '未分组'" />
          </template>
        </el-table-column>

        <el-table-column label="判定依据" min-width="240">
          <template #default="{ row }">
            <!-- 数值型：上下限 -->
            <div v-if="row.__item.valueType === 'NUMBER'" class="num-range">
              <el-input-number v-model="row.minValue" :precision="4" :controls="false" size="small"
                               :disabled="!isDraft" placeholder="下限" class="num-input" />
              <span class="sep">~</span>
              <el-input-number v-model="row.maxValue" :precision="4" :controls="false" size="small"
                               :disabled="!isDraft" placeholder="上限" class="num-input" />
              <span v-if="row.__item.unit" class="unit">{{ row.__item.unit }}</span>
            </div>

            <!-- 枚举型：选项编辑 -->
            <div v-else-if="row.__item.valueType === 'ENUM'">
              <el-button size="small" :disabled="!isDraft" @click="openOptions(row)">
                配置选项（{{ optionsOf(row).length }}）
              </el-button>
              <div class="item-sub">
                合格：{{ optionsOf(row).filter(o => o.pass).map(o => o.name).join('、') || '未配置' }}
              </div>
            </div>

            <!-- 布尔型：合格值三态 -->
            <div v-else-if="row.__item.valueType === 'BOOL'">
              <el-radio-group v-model="row.boolPassValue" size="small" :disabled="!isDraft">
                <el-radio :value="true">录「有」算合格</el-radio>
                <el-radio :value="false">录「无」算合格</el-radio>
              </el-radio-group>
              <div class="item-sub">
                主数据默认：{{ row.__item.boolPassValue ? '有=合格' : '无=合格' }}，此处可覆盖
              </div>
            </div>

            <span v-else class="item-sub">不参与自动判定，仅记录文字</span>
          </template>
        </el-table-column>

        <el-table-column label="测量" width="150">
          <template #default="{ row }">
            <div v-if="row.__item.valueType === 'TEXT'" class="item-sub">—</div>
            <template v-else>
              <el-select v-model="row.valueMode" size="small" :disabled="!isDraft" style="width: 100%">
                <el-option label="单值" value="SINGLE" />
                <el-option label="多点" value="MULTIPLE" />
              </el-select>
              <el-input-number v-if="row.valueMode === 'MULTIPLE'" v-model="row.pointCount" :min="2" :max="20"
                               size="small" :disabled="!isDraft" style="width: 100%; margin-top: 4px" />
            </template>
          </template>
        </el-table-column>

        <el-table-column label="关键项" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.isCritical" :disabled="!isDraft" />
          </template>
        </el-table-column>

        <el-table-column label="破坏性" width="90" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.isDestructive" :disabled="!isDraft" />
          </template>
        </el-table-column>

        <el-table-column v-if="isDraft" label="" width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="rows.splice($index, 1)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加检验项 -->
    <el-dialog v-model="pickVisible" title="从检验项主数据里挑" width="760px">
      <el-input v-model="pickKeyword" placeholder="按名称 / 编号过滤" clearable class="mb" />
      <el-table :data="pickRows" height="380" size="small" @selection-change="onPick">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="itemCode" label="编号" width="110" />
        <el-table-column prop="itemName" label="名称" min-width="120" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ typeName(row.valueType) }}</template>
        </el-table-column>
        <el-table-column prop="groupName" label="分组" width="90" />
        <el-table-column prop="measureTool" label="建议量具" width="110" />
        <el-table-column label="已加入" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="isPicked(row.id)" type="success" size="small">是</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="item-sub">已选 {{ picked.length }} 项，已加入的会被跳过</span>
        <el-button @click="pickVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmPick">加入</el-button>
      </template>
    </el-dialog>

    <!-- 枚举选项 -->
    <el-dialog v-model="optVisible" :title="`配置选项：${optRow?.__item.itemName || ''}`" width="520px">
      <el-table :data="optDraft" size="small" border>
        <el-table-column label="编码" width="140">
          <template #default="{ row }">
            <el-input v-model="row.code" size="small" placeholder="OK" />
          </template>
        </el-table-column>
        <el-table-column label="显示名称" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.name" size="small" placeholder="无划伤" />
          </template>
        </el-table-column>
        <el-table-column label="合格" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.pass" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="" width="60" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="optDraft.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="mt" @click="optDraft.push({ code: '', name: '', pass: true })">添加选项</el-button>
      <template #footer>
        <el-button @click="optVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmOptions">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const route = useRoute()
const router = useRouter()

const standardId = ref(Number(route.params.id))
const loading = ref(false)
const saving = ref(false)
const isDraft = ref(true)

const form = reactive({
  standardCode: '',
  samplingType: 'FULL',
  sampleSize: 1,
  allowedNgCount: 0,
  remark: ''
})

/** 已配置的检验项，每行带 __item 指向主数据 */
const rows = ref([])
const allItems = ref([])

const criticalCount = computed(() => rows.value.filter(r => r.isCritical).length)

function typeName(t) {
  return { NUMBER: '数值', ENUM: '枚举', BOOL: '布尔', TEXT: '文本' }[t] || t
}

// ---------- 加载 ----------
async function load() {
  loading.value = true
  try {
    const [detail, items] = await Promise.all([
      http.get(`/standards/${standardId.value}`),
      http.get('/items')
    ])
    allItems.value = items
    const std = detail.standard
    Object.assign(form, {
      standardCode: std.standardCode,
      samplingType: std.samplingType,
      sampleSize: std.sampleSize,
      allowedNgCount: std.allowedNgCount,
      remark: std.remark
    })
    isDraft.value = std.status === 'DRAFT'
    rows.value = (detail.items || []).map(si => toRow(si))
  } finally {
    loading.value = false
  }
}

function toRow(si) {
  const item = allItems.value.find(i => i.id === si.itemId) || { itemName: si.itemName || `#${si.itemId}` }
  return {
    itemId: si.itemId,
    groupName: si.groupName || '',
    isCritical: !!si.isCritical,
    isRequired: si.isRequired !== false,
    valueMode: si.valueMode || 'SINGLE',
    pointCount: si.pointCount || 1,
    minValue: si.minValue,
    maxValue: si.maxValue,
    optionsJson: si.optionsJson || null,
    boolPassValue: si.boolPassValue === undefined ? null : si.boolPassValue,
    isDestructive: !!si.isDestructive,
    __item: item
  }
}

// ---------- 添加检验项 ----------
const pickVisible = ref(false)
const pickKeyword = ref('')
const picked = ref([])

const pickRows = computed(() => {
  const kw = pickKeyword.value.trim()
  if (!kw) return allItems.value
  return allItems.value.filter(i =>
    (i.itemName || '').includes(kw) || (i.itemCode || '').toLowerCase().includes(kw.toLowerCase()))
})

function isPicked(id) {
  return rows.value.some(r => r.itemId === id)
}

function onPick(sel) {
  picked.value = sel
}

function confirmPick() {
  let added = 0
  for (const item of picked.value) {
    if (isPicked(item.id)) continue
    rows.value.push({
      itemId: item.id,
      groupName: item.groupName || '',
      isCritical: false,
      isRequired: true,
      valueMode: 'SINGLE',
      pointCount: 1,
      minValue: item.defaultMin,
      maxValue: item.defaultMax,
      optionsJson: item.optionsJson || null,
      boolPassValue: null,
      isDestructive: false,
      __item: item
    })
    added++
  }
  ElMessage.success(added ? `已加入 ${added} 项` : '所选项都已在列表里')
  pickVisible.value = false
  picked.value = []
}

// ---------- 枚举选项 ----------
const optVisible = ref(false)
const optRow = ref(null)
const optDraft = ref([])

function optionsOf(row) {
  if (!row.optionsJson) return []
  try {
    return JSON.parse(row.optionsJson)
  } catch (e) {
    return []
  }
}

function openOptions(row) {
  optRow.value = row
  const cur = optionsOf(row)
  optDraft.value = cur.length
    ? cur.map(o => ({ ...o }))
    : [{ code: 'OK', name: '合格', pass: true }, { code: 'NG', name: '不合格', pass: false }]
  optVisible.value = true
}

function confirmOptions() {
  const list = optDraft.value.filter(o => o.code && o.name)
  if (!list.length) {
    ElMessage.warning('至少要有一个选项')
    return
  }
  if (!list.some(o => o.pass)) {
    ElMessage.warning('至少要有一个选项判为合格')
    return
  }
  optRow.value.optionsJson = JSON.stringify(list)
  optVisible.value = false
}

// ---------- 保存 ----------
function payload() {
  return rows.value.map(r => ({
    itemId: r.itemId,
    groupName: r.groupName || null,
    isCritical: !!r.isCritical,
    isRequired: r.isRequired !== false,
    valueMode: r.valueMode,
    pointCount: r.valueMode === 'MULTIPLE' ? r.pointCount : 1,
    minValue: r.__item.valueType === 'NUMBER' ? r.minValue : null,
    maxValue: r.__item.valueType === 'NUMBER' ? r.maxValue : null,
    optionsJson: r.__item.valueType === 'ENUM' ? r.optionsJson : null,
    boolPassValue: r.__item.valueType === 'BOOL' ? r.boolPassValue : null,
    isDestructive: !!r.isDestructive
  }))
}

async function save(quiet = false) {
  if (!rows.value.length) {
    ElMessage.warning('至少配置一个检验项')
    return false
  }
  if (!criticalCount.value) {
    ElMessage.warning('至少要标记一个关键项，否则任意项超差都不会判死整批')
    return false
  }
  saving.value = true
  try {
    await http.put(`/standards/${standardId.value}`, { ...form })
    await http.post(`/standards/${standardId.value}/items`, payload())
    if (!quiet) ElMessage.success('已保存')
    return true
  } finally {
    saving.value = false
  }
}

async function activate() {
  await ElMessageBox.confirm(
    '生效后标准不能再改动，要改只能发布新版本。确认生效？',
    '保存并生效',
    { type: 'warning' }
  )
  if (!await save(true)) return
  await http.post(`/standards/${standardId.value}/activate`)
  ElMessage.success('已生效')
  router.push('/standards')
}

onMounted(load)
</script>

<style scoped>
.ph { margin-bottom: 16px; }
.mb { margin-bottom: 16px; }
.mt { margin-top: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; margin-top: 2px; }
.item-name { font-weight: 500; }
.item-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.num-range { display: flex; align-items: center; gap: 6px; }
.num-input { width: 96px; }
.sep { color: var(--el-text-color-secondary); }
.unit { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
