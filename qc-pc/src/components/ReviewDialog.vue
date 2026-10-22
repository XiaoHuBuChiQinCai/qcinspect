<template>
  <el-dialog
    :model-value="modelValue"
    title="审核检验单"
    width="520px"
    @update:model-value="v => emit('update:modelValue', v)"
  >
    <el-descriptions :column="2" border size="small" class="mb">
      <el-descriptions-item label="检验单">{{ row.inspectionNo }}</el-descriptions-item>
      <el-descriptions-item label="批次">{{ row.batchNo || '—' }}</el-descriptions-item>
      <el-descriptions-item label="判定">{{ resultText(row.result) }}</el-descriptions-item>
      <el-descriptions-item label="检验员">{{ row.inspectorName || '—' }}</el-descriptions-item>
      <el-descriptions-item label="合格 / 不合格">{{ row.passedQty }} / {{ row.failedQty }}</el-descriptions-item>
      <el-descriptions-item label="NG 行数">{{ row.ngCount }}</el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-if="selfReview"
      type="warning"
      :closable="false"
      class="mb"
      title="这是你本人录入的单据。允许代审，但会在日志中标注「管理员代审本人录入单据」"
    />

    <el-radio-group v-model="approve" class="mb">
      <el-radio :value="true">审核通过并归档</el-radio>
      <el-radio :value="false">退回检验员修正</el-radio>
    </el-radio-group>

    <el-input
      v-model="comment"
      type="textarea"
      :rows="3"
      :placeholder="approve ? '审核意见（选填）' : '退回原因（必填，检验员按此修改）'"
    />

    <div class="hint">
      {{ approve
        ? '通过后单据锁定为「已归档」，批次状态同步为已完成，后续如需更正只能由管理员解锁。'
        : '退回后单据回到草稿，判定结果清空，检验员修正后可重新提交。' }}
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getStoredUser } from '../api/http'

/**
 * 审核对话框。检验单列表和工作台共用一份，
 * 免得「退回必须写原因」这类规则在两个页面上各写一遍、各漏一处。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  row: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'done'])

const saving = ref(false)
const approve = ref(true)
const comment = ref('')

// 每次打开都复位，否则上一次退回填的原因会留在下一次「通过」里
watch(() => props.modelValue, v => {
  if (v) {
    approve.value = true
    comment.value = ''
  }
})

function displayName() {
  const u = getStoredUser() || {}
  return u.realName || u.username || ''
}

const selfReview = computed(
  () => !!props.row.inspectorName && props.row.inspectorName === displayName()
)

const resultText = r => ({ PASS: '合格', FAIL: '不合格', NA: '未判定' }[r] || r || '—')

async function submit() {
  if (!approve.value && !comment.value.trim()) {
    ElMessage.warning('退回必须写明原因，否则检验员不知道要改什么')
    return
  }
  saving.value = true
  try {
    await http.post(`/inspections/${props.row.id}/review`, {
      approve: approve.value,
      comment: comment.value.trim() || null
    })
    ElMessage.success(approve.value ? '已审核通过并归档' : '已退回检验员')
    emit('update:modelValue', false)
    emit('done')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.hint { margin-top: 10px; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
</style>
