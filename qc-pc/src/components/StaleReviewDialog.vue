<template>
  <el-dialog
    :model-value="modelValue"
    title="记录改版遗留的复核"
    width="600px"
    @update:model-value="v => emit('update:modelValue', v)"
  >
    <el-descriptions :column="3" border size="small" class="mb">
      <el-descriptions-item label="产品">{{ row.product_name || '—' }}</el-descriptions-item>
      <el-descriptions-item label="版本">
        v{{ row.version }}
        <span v-if="row.current_version"> → 现行 v{{ row.current_version }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="判定单">{{ row.inspection_count || 0 }} 张</el-descriptions-item>
    </el-descriptions>

    <!-- 复核人当下要判断的正是这个：不是「有几张单」，而是「这批判定还站不站得住」 -->
    <el-alert
      v-if="row.risk_advice"
      :type="row.risk === 'HIGH' ? 'error' : 'warning'"
      :closable="false"
      show-icon
      class="mb"
      :title="`风险：${row.risk_text || '—'}`"
      :description="row.risk_advice"
    />

    <el-radio-group v-model="form.verdict" class="mb">
      <el-radio value="KEPT">抽查后维持原判</el-radio>
      <el-radio value="ACTION">发现问题，需要处理</el-radio>
    </el-radio-group>

    <el-input
      v-model="form.remark"
      type="textarea"
      :rows="4"
      maxlength="500"
      show-word-limit
      :placeholder="form.verdict === 'KEPT'
        ? '抽查了哪几张（单号）、按什么判断、为什么维持原判'
        : '发现了什么问题、涉及哪些单、怎么处理（返工 / 让步 / 追溯 / 通知客户）'"
    />
    <div class="hint">
      这条记录是给体系审核看的：审核员会问「发现旧版放行风险之后你们做了什么」。
      写清抽查范围和处理方式，比写「已阅」有用得多。
    </div>

    <div v-if="history.length" class="history">
      <div class="history-title">此前复核 {{ history.length }} 次（只增不改，最新在最前）</div>
      <div v-for="h in history" :key="h.id" class="history-row">
        <el-tag :type="h.verdict === 'KEPT' ? 'success' : 'warning'" size="small" effect="plain">
          {{ h.verdictText }}
        </el-tag>
        <span class="who">{{ h.reviewer }} · {{ fmt(h.reviewedAt) }}</span>
        <div class="why">{{ h.remark }}</div>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交复核</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

/**
 * 改版遗留的复核记录。
 *
 * <p>工作台和标准页共用一份：同一个动作在两个页面上各写一遍，
 * 「理由必填、至少几个字」这种规则迟早只在一个地方生效。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** stale-usage 的一行 */
  row: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'done'])

const saving = ref(false)
const history = ref([])
const form = ref({ verdict: 'KEPT', remark: '' })

// 每次打开都复位：上一次填的理由留在下一次提交里，是最容易被忽略的脏数据
watch(() => props.modelValue, async v => {
  if (!v) return
  form.value = { verdict: 'KEPT', remark: '' }
  history.value = []
  if (props.row.standard_id) {
    try {
      const list = await http.get(`/standards/${props.row.standard_id}/reviews`)
      history.value = Array.isArray(list) ? list : []
    } catch {
      // 历史拉不到不该挡住「记录这次复核」这件正事
      history.value = []
    }
  }
})

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

async function submit() {
  const remark = form.value.remark.trim()
  if (remark.length < 5) {
    ElMessage.warning('请写清抽查了哪几张、依据什么，至少 5 个字')
    return
  }
  saving.value = true
  try {
    await http.post(`/standards/${props.row.standard_id}/review`, {
      verdict: form.value.verdict,
      remark
    })
    ElMessage.success(
      form.value.verdict === 'KEPT' ? '已记录：维持原判' : '已记录：需要处理'
    )
    emit('update:modelValue', false)
    emit('done')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; margin-top: 8px; }
.history { margin-top: 14px; border-top: 1px dashed var(--el-border-color-lighter); padding-top: 10px; }
.history-title { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.history-row { margin-bottom: 8px; }
.who { font-size: 12px; color: var(--el-text-color-secondary); margin-left: 8px; }
.why { font-size: 12px; color: var(--el-text-color-regular); line-height: 1.5; margin-top: 2px; }
</style>
