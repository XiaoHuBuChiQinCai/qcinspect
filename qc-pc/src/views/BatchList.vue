<template>
  <el-card>
    <div class="bar">
      <el-input
        v-model="keyword"
        placeholder="批次号 / 供应商 / 原料批号 / 工单号"
        clearable
        style="width: 280px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 150px" @change="load">
        <el-option label="待检" value="PENDING" />
        <el-option label="检验中" value="INSPECTING" />
        <el-option label="已完成" value="DONE" />
      </el-select>
      <el-button type="primary" plain @click="load">查询</el-button>
      <div class="spacer" />
      <span class="count">共 {{ rows.length }} 个批次</span>
      <el-button type="primary" @click="openBatch">新建批次</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" size="default">
      <el-table-column prop="batchNo" label="批次号" width="170" />
      <el-table-column prop="workOrderNo" label="工单号" width="160" />
      <el-table-column prop="inspectType" label="检验类型" width="110" />
      <el-table-column prop="qty" label="数量" width="90" />
      <el-table-column prop="supplier" label="供应商" min-width="140" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" size="small" :disabled="row.status === 'DONE'" @click="openInspection(row)">
            {{ row.status === 'INSPECTING' ? '继续检验' : '开检验单' }}
          </el-button>
          <el-button size="small" @click="openShipment(row)">登记出货</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 出货登记：qc_shipment 是追溯链的终点，没有它客诉来了查不到货发给了谁 -->
    <el-dialog v-model="shipVisible" title="登记出货" width="540px" @closed="shipCtx = null">
      <el-alert v-if="shipCtx && shipCtx.blocking" type="warning" :closable="false" class="mb"
                :title="`${shipCtx.blocking}。确认要出的话，请勾选下方的「例外放行」`" />

      <el-descriptions :column="3" border size="small" class="mb">
        <el-descriptions-item label="批次">{{ shipCtx?.batchNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="批次数量">{{ shipCtx?.batchQty ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="已出货">{{ shipCtx?.shippedQty ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="还能出">
          <strong :class="shipCtx?.remainingQty ? '' : 'zero'">{{ shipCtx?.remainingQty ?? '—' }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="检验结论" :span="2">
          <template v-if="shipCtx?.result">
            <el-tag :type="resultType(shipCtx.result)" size="small">{{ resultText(shipCtx.result) }}</el-tag>
            <span class="sub">　{{ shipCtx.inspectionNo }}</span>
          </template>
          <span v-else class="sub">该批次还没有检验结论</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-form :model="ship" label-width="90px">
        <el-form-item label="客户" required>
          <el-input v-model="ship.customer" placeholder="收货单位，按合同或送货单上的名字填" maxlength="128" />
        </el-form-item>
        <el-form-item label="出货数量" required>
          <el-input-number v-model="ship.qty" :min="1" :max="shipCtx?.remainingQty || undefined"
                           style="width: 180px" />
          <span class="sub">　最多 {{ shipCtx?.remainingQty ?? 0 }} 件</span>
        </el-form-item>
        <el-form-item label="出货时间">
          <el-date-picker v-model="ship.shippedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="留空取当前时间" style="width: 240px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ship.remark" type="textarea" :rows="2"
                    placeholder="例外放行时请写清楚让步依据、批准人" />
        </el-form-item>
        <el-form-item v-if="shipCtx && shipCtx.blocking" label=" ">
          <el-checkbox v-model="ship.force">例外放行（我确认这批货带着上述问题出货）</el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipSaving" @click="doShipment">确认出货</el-button>
      </template>
    </el-dialog>

    <!-- 新建批次：料到了要先进系统，否则后面既开不了检验单也谈不上追溯 -->
    <el-dialog v-model="batchVisible" title="新建批次" width="560px">
      <el-form :model="batch" label-width="100px">
        <el-form-item label="批次号" required>
          <el-input v-model="batch.batchNo" placeholder="如 B20261022001，全厂唯一" maxlength="64" />
        </el-form-item>
        <el-form-item label="产品" required>
          <div class="row">
            <el-select v-model="batch.productId" placeholder="选产品" filterable style="width: 300px">
              <el-option v-for="p in products" :key="p.id"
                         :label="`${p.productCode} ${p.productName}`" :value="p.id" />
            </el-select>
            <el-button @click="openProduct">新建产品</el-button>
          </div>
          <div v-if="productWarning" class="warn-tip">{{ productWarning }}</div>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="batch.qty" :min="1" style="width: 160px" />
          <span class="sub">　件</span>
        </el-form-item>
        <el-form-item label="检验类型" required>
          <el-select v-model="batch.inspectType" style="width: 160px">
            <el-option label="IQC 来料" value="IQC" />
            <el-option label="IPQC 过程" value="IPQC" />
            <el-option label="FQC 成品" value="FQC" />
            <el-option label="OQC 出货" value="OQC" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="batch.supplier" placeholder="来料供应商，统计合格率要用" maxlength="128" />
        </el-form-item>
        <el-form-item label="原料批号">
          <el-input v-model="batch.materialBatchNo" placeholder="供应商的原始批号，追溯要顺着它往回查" maxlength="64" />
        </el-form-item>
        <el-form-item label="工单号">
          <el-input v-model="batch.workOrderNo" placeholder="车间工单号" maxlength="64" />
        </el-form-item>
        <el-form-item label="到货时间">
          <el-date-picker v-model="batch.arrivedAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空取当前时间" style="width: 240px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="batch.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSaving" @click="doCreateBatch">保存</el-button>
      </template>
    </el-dialog>

    <!-- 快捷建产品：录批次时发现产品还没建，不该逼人跑去另一个页面 -->
    <el-dialog v-model="productVisible" title="新建产品" width="440px" append-to-body>
      <el-form :model="productForm" label-width="90px">
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
      </el-form>
      <div class="hint">建完记得去「检验标准」给它配一套启用标准，否则开检验单会报错。</div>
      <template #footer>
        <el-button @click="productVisible = false">取消</el-button>
        <el-button type="primary" :loading="productSaving" @click="doCreateProduct">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const filterStatus = ref('')
const keyword = ref('')

const shipVisible = ref(false)
const shipSaving = ref(false)
const shipCtx = ref(null)
const ship = reactive({ batchId: null, customer: '', qty: 1, shippedAt: null, remark: '', force: false })

// ---------------- 新建批次 ----------------
const products = ref([])
const batchVisible = ref(false)
const batchSaving = ref(false)
const batch = reactive({
  batchNo: '', productId: null, qty: 1, inspectType: 'FQC',
  supplier: '', materialBatchNo: '', workOrderNo: '', arrivedAt: null, remark: ''
})

const productVisible = ref(false)
const productSaving = ref(false)
const productForm = reactive({ productCode: '', productName: '', spec: '', unit: '' })

/** 选中产品没有启用标准时提前警告：等到开单那一刻才报错，人已经在车间了 */
const productWarning = computed(() => {
  const p = products.value.find(x => x.id === batch.productId)
  if (!p || p.active_standard_count > 0) return ''
  return p.standard_count > 0
    ? '该产品的标准还是草稿，没有启用的版本，开检验单会报错'
    : '该产品还没有检验标准，开检验单会报错'
})

const statusText = s => ({ PENDING: '待检', INSPECTING: '检验中', DONE: '已完成' }[s] || s)
const statusType = s => ({ PENDING: 'info', INSPECTING: 'warning', DONE: 'success' }[s] || '')
const resultText = r => ({ PASS: '合格', FAIL: '不合格', NA: '未判定' }[r] || r)
const resultType = r => ({ PASS: 'success', FAIL: 'danger', NA: 'info' }[r] || 'info')

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/batches', {
      params: { status: filterStatus.value || undefined, keyword: keyword.value.trim() || undefined }
    })
  } finally {
    loading.value = false
  }
}

/**
 * 开检验单前先看一眼这个批次有没有没走完的单：
 * 有草稿就接着填，有待审核就打开看（只读）。
 * 否则同一批次会挂出两张检验单，两个结论，追溯时没法解释。
 */
async function openInspection(row) {
  const page = await http.get('/inspections/page', { params: { batchId: row.id, size: 5 } })
  const open = (page.records || []).find(r => r.status === 'DRAFT' || r.status === 'SUBMITTED')
  if (open) {
    ElMessage.info(open.status === 'SUBMITTED' ? '该批次已提交，正在等审核' : '接着上次的草稿继续')
    router.push(`/inspection/${open.id}`)
    return
  }

  let sampleQty
  try {
    const { value } = await ElMessageBox.prompt(
      '留空就按标准自动算：AQL 抽样按批量查国标表，固定抽样取标准里配的件数，全检取批次件数。'
      + '确实要人工指定，填 1~200 的整数（AQL 抽样下会以方案表为准）。',
      `开检验单 · ${row.batchNo}`,
      {
        inputValue: '',
        inputPlaceholder: `留空自动 · 本批 ${row.qty || '-'} 件`
      }
    )
    const v = String(value ?? '').trim()
    if (v && !/^[1-9]\d{0,2}$/.test(v)) {
      ElMessage.warning('件数需为 1 ~ 200 的整数，或留空让系统按标准算')
      return
    }
    sampleQty = v ? Number(v) : undefined
  } catch {
    return
  }
  // 检验员由后端取登录态，前端不传名字
  const data = await http.post('/inspections', { batchId: row.id, sampleQty })
  router.push(`/inspection/${data.inspectionId}`)
}

onMounted(() => {
  loadProducts()
  load()
})

/**
 * 打开出货登记。
 * 先拉一次 context 拿到「还剩多少可发」和「检验结论」——这两个数前端不能自己猜，
 * 累计出货量要后端算，否则并发登记时两边都以为还有余量。
 */
async function openShipment(row) {
  shipCtx.value = await http.get(`/shipments/context/${row.id}`)
  Object.assign(ship, {
    batchId: row.id,
    customer: '',
    qty: shipCtx.value.remainingQty || 1,
    shippedAt: null,
    remark: '',
    force: false
  })
  shipVisible.value = true
}

async function doShipment() {
  if (!ship.customer.trim()) {
    ElMessage.warning('客户不能为空，否则这张出货记录在客诉时没用')
    return
  }
  shipSaving.value = true
  try {
    await http.post('/shipments', {
      batchId: ship.batchId,
      customer: ship.customer.trim(),
      qty: ship.qty,
      shippedAt: ship.shippedAt || null,
      remark: ship.remark.trim() || null,
      force: ship.force
    })
    ElMessage.success('出货已登记')
    shipVisible.value = false
  } finally {
    shipSaving.value = false
  }
}

/** 产品列表走 overview：顺带带回「有没有启用标准」，省一次请求也省一次踩坑 */
async function loadProducts() {
  products.value = await http.get('/products/overview')
}

function openBatch() {
  Object.assign(batch, {
    batchNo: '',
    productId: products.value.length === 1 ? products.value[0].id : null,
    qty: 1,
    inspectType: 'FQC',
    supplier: '',
    materialBatchNo: '',
    workOrderNo: '',
    arrivedAt: null,
    remark: ''
  })
  batchVisible.value = true
}

async function doCreateBatch() {
  if (!batch.batchNo.trim()) {
    ElMessage.warning('批次号不能为空')
    return
  }
  if (!batch.productId) {
    ElMessage.warning('请选择产品')
    return
  }
  batchSaving.value = true
  try {
    await http.post('/batches', {
      batchNo: batch.batchNo.trim(),
      productId: batch.productId,
      qty: batch.qty,
      inspectType: batch.inspectType,
      supplier: batch.supplier.trim() || null,
      materialBatchNo: batch.materialBatchNo.trim() || null,
      workOrderNo: batch.workOrderNo.trim() || null,
      arrivedAt: batch.arrivedAt || null,
      remark: batch.remark.trim() || null
    })
    ElMessage.success('批次已建立')
    batchVisible.value = false
    if (productWarning.value) {
      ElMessage.warning(productWarning.value)
    }
    await Promise.all([load(), loadProducts()])
  } finally {
    batchSaving.value = false
  }
}

function openProduct() {
  Object.assign(productForm, { productCode: '', productName: '', spec: '', unit: '' })
  productVisible.value = true
}

async function doCreateProduct() {
  if (!productForm.productCode.trim() || !productForm.productName.trim()) {
    ElMessage.warning('产品编号和名称都要填')
    return
  }
  productSaving.value = true
  try {
    const p = await http.post('/products', {
      productCode: productForm.productCode.trim(),
      productName: productForm.productName.trim(),
      spec: productForm.spec.trim() || null,
      unit: productForm.unit.trim() || null
    })
    ElMessage.success('产品已建立')
    productVisible.value = false
    await loadProducts()
    // 建完直接选中，用户不用回头再找一遍
    batch.productId = p.id
  } finally {
    productSaving.value = false
  }
}
</script>

<style scoped>
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 16px; }
.spacer { flex: 1; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.zero { color: var(--el-color-danger); }
.mb { margin-bottom: 14px; }
.row { display: flex; gap: 8px; align-items: center; width: 100%; }
.warn-tip { font-size: 12px; color: var(--el-color-warning); margin-top: 4px; line-height: 1.5; }
.hint { margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
