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
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
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

onMounted(load)

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
</script>

<style scoped>
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 16px; }
.spacer { flex: 1; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.zero { color: var(--el-color-danger); }
.mb { margin-bottom: 14px; }
</style>
