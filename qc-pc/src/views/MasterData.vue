<template>
  <el-card>
    <el-tabs v-model="tab">
      <!-- ==================== 产品 ==================== -->
      <el-tab-pane label="产品" name="product">
        <div class="bar">
          <el-input
            v-model="productKeyword"
            placeholder="产品编号 / 名称"
            clearable
            style="width: 240px"
            @keyup.enter="loadProducts"
            @clear="loadProducts"
          />
          <el-button @click="loadProducts">查询</el-button>
          <div class="spacer" />
          <el-button v-if="isAdmin" type="primary" @click="openProduct()">新建产品</el-button>
        </div>

        <el-alert
          v-if="noStandard.length"
          type="warning"
          :closable="false"
          class="mb"
          :title="`有 ${noStandard.length} 个产品还没有启用的检验标准，到产线开单时会报错`"
        />

        <el-table :data="products" v-loading="productLoading">
          <el-table-column prop="product_code" label="产品编号" width="180" />
          <el-table-column prop="product_name" label="名称" min-width="140" />
          <el-table-column prop="spec" label="规格" min-width="120" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="检验标准" width="150">
            <template #default="{ row }">
              <span v-if="row.active_standard_count > 0" class="ok">
                启用 {{ row.active_standard_count }} 套
              </span>
              <span v-else-if="row.standard_count > 0" class="warn">
                只有草稿（{{ row.standard_count }} 套未启用）
              </span>
              <span v-else class="warn">还没有标准</span>
            </template>
          </el-table-column>
          <el-table-column label="批次" width="90">
            <template #default="{ row }">{{ row.batch_count }} 个</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="goStandard(row)">配标准</el-button>
              <el-button v-if="isAdmin" size="small" @click="openProduct(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 检验项 ==================== -->
      <el-tab-pane label="检验项" name="item">
        <div class="bar">
          <el-input
            v-model="itemKeyword"
            placeholder="编号 / 名称 / 分组"
            clearable
            style="width: 240px"
          />
          <div class="spacer" />
          <el-button v-if="isAdmin" type="primary" @click="openItem()">新建检验项</el-button>
        </div>

        <el-table :data="filteredItems" v-loading="itemLoading">
          <el-table-column prop="itemCode" label="编号" width="130" />
          <el-table-column prop="itemName" label="名称" min-width="140" />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.valueType)">{{ typeText(row.valueType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="默认限值" width="150">
            <template #default="{ row }">
              <span v-if="row.valueType === 'NUMBER'">
                {{ fmtNum(row.defaultMin) }} ~ {{ fmtNum(row.defaultMax) }}
              </span>
              <span v-else-if="row.valueType === 'BOOL'" class="muted">
                合格值：{{ row.boolPassValue ? '是' : '否' }}
              </span>
              <span v-else-if="row.valueType === 'ENUM'" class="muted">
                {{ optionCount(row) }} 个选项
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="measureTool" label="量具" width="120" />
          <el-table-column prop="groupName" label="分组" width="100" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button v-if="isAdmin" size="small" @click="openItem(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 产品弹窗 ==================== -->
    <el-dialog v-model="productVisible" :title="productForm.id ? '编辑产品' : '新建产品'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="产品编号" required>
          <el-input v-model="productForm.productCode" placeholder="如 P-FLANGE-DN50" />
        </el-form-item>
        <el-form-item label="产品名称" required>
          <el-input v-model="productForm.productName" placeholder="如 法兰盘" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="productForm.spec" placeholder="如 DN50 PN16" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="productForm.unit" placeholder="件 / 套 / kg" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="productForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div class="hint">
        产品建好后还要配一套「启用」的检验标准，才能真正开检验单——
        列表上的红色提示就是在提醒这件事。
      </div>
      <template #footer>
        <el-button @click="productVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProduct">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 检验项弹窗 ==================== -->
    <el-dialog v-model="itemVisible" :title="itemForm.id ? '编辑检验项' : '新建检验项'" width="620px">
      <el-form label-width="100px">
        <el-form-item label="编号" required>
          <el-input v-model="itemForm.itemCode" placeholder="如 I-OD" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="itemForm.itemName" placeholder="如 外径" />
        </el-form-item>
        <el-form-item label="数据类型" required>
          <el-radio-group v-model="itemForm.valueType">
            <el-radio value="NUMBER">数值</el-radio>
            <el-radio value="ENUM">枚举</el-radio>
            <el-radio value="BOOL">是/否</el-radio>
            <el-radio value="TEXT">文本</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="itemForm.valueType === 'NUMBER'">
          <el-form-item label="单位">
            <el-input v-model="itemForm.unit" placeholder="mm / HRC" style="width: 140px" />
          </el-form-item>
          <el-form-item label="默认下限">
            <el-input-number v-model="itemForm.defaultMin" :controls="false" :precision="3" style="width: 160px" />
          </el-form-item>
          <el-form-item label="默认上限">
            <el-input-number v-model="itemForm.defaultMax" :controls="false" :precision="3" style="width: 160px" />
          </el-form-item>
        </template>

        <el-form-item v-if="itemForm.valueType === 'BOOL'" label="合格值">
          <el-radio-group v-model="itemForm.boolPassValue">
            <el-radio :value="true">「是」算合格</el-radio>
            <el-radio :value="false">「否」算合格</el-radio>
          </el-radio-group>
          <div class="hint">
            问法是关键：「有无毛刺」应该选「否」算合格；「是否已热处理」才是「是」算合格。
            选错方向，这一项的结论会整个反过来。
          </div>
        </el-form-item>

        <!-- 枚举选项：选项就是判定依据，做成一格一格的，别让人手写 JSON -->
        <el-form-item v-if="itemForm.valueType === 'ENUM'" label="选项">
          <div class="options">
            <div v-for="(o, i) in itemForm.options" :key="i" class="option-row">
              <el-input v-model="o.code" placeholder="值（如 NONE）" style="width: 150px" />
              <el-input v-model="o.name" placeholder="显示名（如 无划伤）" style="width: 180px" />
              <el-radio-group v-model="o.pass">
                <el-radio :value="true">合格</el-radio>
                <el-radio :value="false">不合格</el-radio>
              </el-radio-group>
              <el-button link type="danger" @click="itemForm.options.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="addOption">+ 加一个选项</el-button>
            <div class="hint">至少要有一个选项判为合格，否则这个检验项永远不合格。</div>
          </div>
        </el-form-item>

        <el-form-item label="测量量具">
          <el-input v-model="itemForm.measureTool" placeholder="如 千分尺" style="width: 180px" />
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model="itemForm.groupName" placeholder="如 尺寸 / 外观" style="width: 180px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getStoredUser } from '../api/http'

const router = useRouter()
const user = getStoredUser() || {}
const isAdmin = computed(() => user.role === 'ADMIN')

const tab = ref('product')
const saving = ref(false)

const products = ref([])
const productLoading = ref(false)
const productKeyword = ref('')
const items = ref([])
const itemLoading = ref(false)
const itemKeyword = ref('')

const productVisible = ref(false)
const productForm = reactive({ id: null, productCode: '', productName: '', spec: '', unit: '', remark: '' })

const itemVisible = ref(false)
const itemForm = reactive({
  id: null, itemCode: '', itemName: '', valueType: 'NUMBER', unit: '',
  defaultMin: null, defaultMax: null, boolPassValue: false,
  groupName: '', measureTool: '', options: []
})

/** 没有启用标准的产品：建批次时看不出来，到开单才炸，所以摆在列表顶上 */
const noStandard = computed(() => products.value.filter(p => !p.active_standard_count))

const filteredItems = computed(() => {
  const kw = itemKeyword.value.trim().toLowerCase()
  if (!kw) return items.value
  return items.value.filter(i =>
    (i.itemCode || '').toLowerCase().includes(kw) ||
    (i.itemName || '').includes(itemKeyword.value.trim()) ||
    (i.groupName || '').includes(itemKeyword.value.trim())
  )
})

const typeText = t => ({ NUMBER: '数值', ENUM: '枚举', BOOL: '是/否', TEXT: '文本' }[t] || t)
const typeTag = t => ({ NUMBER: 'primary', ENUM: 'warning', BOOL: 'success', TEXT: 'info' }[t] || 'info')

function fmtNum(v) {
  return v === null || v === undefined ? '—' : v
}

/** 选项数量只是列表上的一个摘要，解析失败不该让整页报错 */
function optionCount(row) {
  try {
    const arr = JSON.parse(row.optionsJson || '[]')
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

async function loadProducts() {
  productLoading.value = true
  try {
    products.value = await http.get('/products/overview', {
      params: { keyword: productKeyword.value.trim() || undefined }
    })
  } finally {
    productLoading.value = false
  }
}

async function loadItems() {
  itemLoading.value = true
  try {
    items.value = await http.get('/items')
  } finally {
    itemLoading.value = false
  }
}

function goStandard(row) {
  router.push({ path: '/standards', query: { productId: row.id } })
}

function openProduct(row) {
  Object.assign(productForm, {
    id: row ? row.id : null,
    productCode: row ? row.product_code : '',
    productName: row ? row.product_name : '',
    spec: row ? row.spec : '',
    unit: row ? row.unit : '',
    remark: row ? row.remark : ''
  })
  productVisible.value = true
}

async function saveProduct() {
  if (!productForm.productCode.trim() || !productForm.productName.trim()) {
    ElMessage.warning('产品编号和名称都要填')
    return
  }
  saving.value = true
  try {
    await http.post('/products', {
      id: productForm.id,
      productCode: productForm.productCode.trim(),
      productName: productForm.productName.trim(),
      spec: productForm.spec || null,
      unit: productForm.unit || null,
      remark: productForm.remark || null
    })
    ElMessage.success('已保存')
    productVisible.value = false
    await loadProducts()
  } finally {
    saving.value = false
  }
}

function openItem(row) {
  const parsed = row ? parseOptions(row.optionsJson) : []
  Object.assign(itemForm, {
    id: row ? row.id : null,
    itemCode: row ? row.itemCode : '',
    itemName: row ? row.itemName : '',
    valueType: row ? row.valueType : 'NUMBER',
    unit: row ? row.unit : '',
    defaultMin: row ? row.defaultMin : null,
    defaultMax: row ? row.defaultMax : null,
    boolPassValue: row ? !!row.boolPassValue : false,
    groupName: row ? row.groupName : '',
    measureTool: row ? row.measureTool : '',
    options: parsed
  })
  itemVisible.value = true
}

function parseOptions(raw) {
  try {
    const arr = JSON.parse(raw || '[]')
    return Array.isArray(arr) ? arr.map(o => ({ code: o.code, name: o.name, pass: !!o.pass })) : []
  } catch {
    return []
  }
}

function addOption() {
  itemForm.options.push({ code: '', name: '', pass: true })
}

async function saveItem() {
  if (!itemForm.itemCode.trim() || !itemForm.itemName.trim()) {
    ElMessage.warning('编号和名称都要填')
    return
  }
  if (itemForm.valueType === 'ENUM') {
    const bad = itemForm.options.some(o => !o.code.trim() || !o.name.trim())
    if (!itemForm.options.length || bad) {
      ElMessage.warning('枚举选项的「值」和「显示名」都要填')
      return
    }
  }
  saving.value = true
  try {
    await http.post('/items', {
      id: itemForm.id,
      itemCode: itemForm.itemCode.trim(),
      itemName: itemForm.itemName.trim(),
      valueType: itemForm.valueType,
      unit: itemForm.valueType === 'NUMBER' ? itemForm.unit || null : null,
      defaultMin: itemForm.valueType === 'NUMBER' ? itemForm.defaultMin : null,
      defaultMax: itemForm.valueType === 'NUMBER' ? itemForm.defaultMax : null,
      boolPassValue: itemForm.valueType === 'BOOL' ? itemForm.boolPassValue : null,
      optionsJson: itemForm.valueType === 'ENUM'
        ? JSON.stringify(itemForm.options.map(o => ({ code: o.code.trim(), name: o.name.trim(), pass: !!o.pass })))
        : null,
      measureTool: itemForm.measureTool || null,
      groupName: itemForm.groupName || null
    })
    ElMessage.success('已保存')
    itemVisible.value = false
    await loadItems()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadProducts()
  loadItems()
})
</script>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; }
.spacer { flex: 1; }
.mb { margin-bottom: 12px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-secondary); }
.hint { margin-top: 6px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.options { width: 100%; }
.option-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
</style>
