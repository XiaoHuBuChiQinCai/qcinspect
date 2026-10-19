<template>
  <div>
    <el-card class="mb">
      <div class="bar">
        <el-input v-model="shipmentNo" placeholder="输入出货单号反向追溯，如 SH20261019001" style="width: 320px"
                  @keyup.enter="traceByShipment" />
        <el-button type="primary" @click="traceByShipment">按出货单追溯</el-button>
        <span class="or">或</span>
        <el-select v-model="batchId" placeholder="选一个批次正向追溯" style="width: 240px">
          <el-option v-for="b in batches" :key="b.id" :label="`${b.batchNo}（${b.qty} 件）`" :value="b.id" />
        </el-select>
        <el-button @click="traceByBatch">按批次追溯</el-button>
      </div>
    </el-card>

    <template v-if="data">
      <el-card class="mb">
        <template #header>批次与产品</template>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="批次号">{{ data.batch?.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="工单号">{{ data.batch?.workOrderNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="产品">
            {{ data.product ? `${data.product.productCode} ${data.product.productName}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="数量">{{ data.batch?.qty }} 件</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ data.batch?.supplier || '—' }}</el-descriptions-item>
          <el-descriptions-item label="原料批号">{{ data.batch?.materialBatchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="检验类型">{{ data.batch?.inspectType }}</el-descriptions-item>
          <el-descriptions-item label="批次状态">{{ data.batch?.status }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="mb">
        <template #header>检验记录（{{ (data.inspections || []).length }} 张）</template>
        <el-empty v-if="!data.inspections?.length" description="这个批次还没有检验单" />
        <div v-for="item in data.inspections" :key="item.inspection.id" class="insp">
          <el-descriptions :column="4" border size="small">
            <el-descriptions-item label="检验单号">{{ item.inspection.inspectionNo }}</el-descriptions-item>
            <el-descriptions-item label="结论">
              <el-tag size="small" :type="resultType(item.inspection.result)">
                {{ item.inspection.result }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="标准版本">v{{ item.inspection.standardVersion }}</el-descriptions-item>
            <el-descriptions-item label="检验员">{{ item.inspection.inspectorName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="抽样/合格/不合格">
              {{ item.inspection.sampleQty }} / {{ item.inspection.passedQty }} / {{ item.inspection.failedQty }}
            </el-descriptions-item>
            <el-descriptions-item label="允收数 Ac">{{ item.inspection.acceptQty }}</el-descriptions-item>
            <el-descriptions-item label="判定说明" :span="2">{{ item.inspection.judgeReason || '—' }}</el-descriptions-item>
          </el-descriptions>

          <div v-if="item.defects?.length" class="defects">
            <div class="defect-title">不良明细（{{ item.defects.length }} 条，未闭环 {{ item.openDefects }}）</div>
            <el-table :data="item.defects" size="small" border>
              <el-table-column prop="pieceNo" label="件号" width="70" />
              <el-table-column prop="defectName" label="不良" width="120" />
              <el-table-column prop="defectCode" label="代码" width="110" />
              <el-table-column prop="measuredNote" label="处置状态" width="110">
                <template #default="{ row }">{{ statusText(row.dispositionStatus) }}</template>
              </el-table-column>
              <el-table-column label="让步理由" min-width="160">
                <template #default="{ row }">{{ row.concessionReason || '—' }}</template>
              </el-table-column>
              <el-table-column label="复检单" width="100">
                <template #default="{ row }">{{ row.recheckInspectionId || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-card>

      <el-card>
        <template #header>出货记录（{{ (data.shipments || []).length }} 条）</template>
        <el-empty v-if="!data.shipments?.length" description="还没出货。没有出货记录，客诉来了就查不到货去了哪" />
        <el-table v-else :data="data.shipments" size="small">
          <el-table-column prop="shipmentNo" label="出货单号" width="180" />
          <el-table-column prop="customer" label="客户" min-width="160" />
          <el-table-column prop="qty" label="数量" width="90" />
          <el-table-column prop="shippedAt" label="出货时间" width="180" />
          <el-table-column prop="remark" label="备注" min-width="160" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const shipmentNo = ref('')
const batchId = ref(null)
const batches = ref([])
const data = ref(null)

function resultType(r) {
  return { PASS: 'success', FAIL: 'danger' }[r] || 'info'
}

function statusText(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', DONE: '已完成', WAIVED: '已让步' }[s] || s
}

async function traceByShipment() {
  if (!shipmentNo.value.trim()) {
    ElMessage.warning('请输入出货单号')
    return
  }
  data.value = await http.get(`/trace/shipment/${encodeURIComponent(shipmentNo.value.trim())}`)
}

async function traceByBatch() {
  if (!batchId.value) {
    ElMessage.warning('请选择一个批次')
    return
  }
  data.value = await http.get(`/trace/batch/${batchId.value}`)
}

onMounted(async () => {
  batches.value = await http.get('/batches')
  if (batches.value.length) batchId.value = batches.value[0].id
})
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; }
.or { color: var(--el-text-color-secondary); }
.mb { margin-bottom: 16px; }
.insp { margin-bottom: 18px; }
.defects { margin-top: 10px; }
.defect-title { font-size: 13px; margin-bottom: 6px; color: var(--el-text-color-secondary); }
</style>
