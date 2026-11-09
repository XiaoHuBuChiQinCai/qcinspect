<template>
  <el-card>
    <template #header>
      <div class="hd">
        <span>做一次 GR&amp;R 研究</span>
        <span class="sub">同一批零件，几个人各测几遍，看读数在人和件之间怎么散开</span>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="零件要能覆盖公差带"
      description="挑 10 件包括合格、接近上下限、废品在内的一批零件——全挑刚下线的同一批，零件变差会偏小，%GRR 反而被算大。评价人不能是同一个人的三遍，得是真的三个人、各用各的手法。"
      style="margin-bottom: 16px"
    />

    <!-- ============ 一、研究对象 ============ -->
    <div class="sec">一、研究对象</div>
    <el-row :gutter="12">
      <el-col :span="8">
        <div class="lbl">量具</div>
        <el-select v-model="form.gaugeId" filterable placeholder="选一把量具" style="width: 100%">
          <el-option
            v-for="g in gauges"
            :key="g.id"
            :label="`${g.gaugeCode} ${g.gaugeName || ''}`"
            :value="g.id"
          />
        </el-select>
      </el-col>
      <el-col :span="8">
        <div class="lbl">检验项（可不选）</div>
        <el-select v-model="form.itemId" clearable filterable placeholder="选了自动带出特性与上下限" style="width: 100%" @change="onItem">
          <el-option v-for="it in items" :key="it.id" :label="`${it.itemCode} ${it.itemName}`" :value="it.id" />
        </el-select>
      </el-col>
      <el-col :span="8">
        <div class="lbl">产品（可不选）</div>
        <el-select v-model="form.productId" clearable filterable placeholder="这批零件属于哪个产品" style="width: 100%">
          <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
        </el-select>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="8">
        <div class="lbl">被研究的特性</div>
        <el-input v-model="form.characteristic" placeholder="比如：外径 / 壁厚 / 硬度" />
      </el-col>
      <el-col :span="4">
        <div class="lbl">单位</div>
        <el-input v-model="form.unit" placeholder="mm" />
      </el-col>
      <el-col :span="6">
        <div class="lbl">规格下限</div>
        <el-input-number v-model="form.lsl" :precision="4" :controls="false" placeholder="可不填" style="width: 100%" />
      </el-col>
      <el-col :span="6">
        <div class="lbl">规格上限</div>
        <el-input-number v-model="form.usl" :precision="4" :controls="false" placeholder="可不填" style="width: 100%" />
      </el-col>
    </el-row>

    <!-- ============ 二、研究结构 ============ -->
    <div class="sec">二、研究结构</div>
    <el-row :gutter="12">
      <el-col :span="5">
        <div class="lbl">评价人数</div>
        <el-input-number v-model="form.appraiserCount" :min="2" :max="5" style="width: 100%" @change="rebuild" />
      </el-col>
      <el-col :span="5">
        <div class="lbl">零件数</div>
        <el-input-number v-model="form.partCount" :min="2" :max="10" style="width: 100%" @change="rebuild" />
      </el-col>
      <el-col :span="5">
        <div class="lbl">每件重复次数</div>
        <el-input-number v-model="form.trialCount" :min="2" :max="3" style="width: 100%" @change="rebuild" />
      </el-col>
      <el-col :span="9">
        <div class="lbl">评价人</div>
        <div class="names">
          <el-input v-for="(n, i) in names" :key="i" v-model="names[i]" :placeholder="`第 ${i + 1} 位`" />
        </div>
      </el-col>
    </el-row>
    <div class="sub tip">
      行业惯例是 3 人 × 10 件 × 3 次。零件少于 5 件时零件变差估不准，%GRR 只能当参考——系统会照算，但会在结论里注明。
    </div>

    <!-- ============ 三、读数 ============ -->
    <div class="sec">
      三、读数
      <span class="sub">
        已填 {{ filled.filled }} / {{ filled.total }}
        <template v-if="filled.filled === filled.total && filled.total">，全部填完</template>
      </span>
      <span class="sub paste-hint">可以从 Excel 整块粘贴：点住某人的第一格按 Ctrl+V，行列自动铺开</span>
      <!-- 预览放在标题行：录的时候人在表格里，眼睛不会往下滚到底部那条操作栏 -->
      <span v-if="preview" class="live" :class="tone">
        %GRR {{ preview.pctGrr }}% · {{ gradeLabel(preview.verdict) }} · ndc {{ preview.ndc }}
      </span>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="(name, i) in names" :key="i" :label="name || `第 ${i + 1} 位`" :name="String(i)">
        <table class="grid">
          <thead>
            <tr>
              <th class="corner">零件</th>
              <th v-for="k in form.trialCount" :key="k">第 {{ k }} 次</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="j in form.partCount" :key="j">
              <th class="corner">{{ j }}</th>
              <td v-for="k in form.trialCount" :key="k">
                <input
                  class="cell"
                  :value="matrix[i][j - 1][k - 1]"
                  :placeholder="'—'"
                  @input="onInput(i, j - 1, k - 1, $event)"
                  @paste="onPaste(i, j - 1, k - 1, $event)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </el-tab-pane>
    </el-tabs>

    <div class="lbl" style="margin-top: 12px">备注</div>
    <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="这批零件是怎么挑的、量具刚校准过没有——评审时最先问的就是这两句" />

    <!-- ============ 底部：实时预览 + 提交 ============ -->
    <div class="bar">
      <div v-if="preview" class="preview">
        <span class="pct" :class="tone">{{ preview.pctGrr }}%</span>
        <el-tag :type="tagType" size="small">{{ gradeLabel(preview.verdict) }}</el-tag>
        <span class="sub">
          ndc {{ preview.ndc }} · EV {{ preview.pctEv }}% · AV {{ preview.pctAv }}%
          <template v-if="preview.pctGrrTol != null"> · 按公差 {{ preview.pctGrrTol }}%</template>
        </span>
      </div>
      <div v-else class="preview sub">
        读数还没填全，结论先不算——半截数据算出来的 %GRR 比不给更误导
      </div>
      <div class="spacer" />
      <el-button @click="router.push('/msa')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存研究</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { compute, checkShape, filledCells, gradeLabel } from '../utils/grr'

const router = useRouter()

const gauges = ref([])
const products = ref([])
const items = ref([])
const saving = ref(false)
const activeTab = ref('0')

const form = reactive({
  gaugeId: null,
  productId: null,
  itemId: null,
  characteristic: '',
  unit: '',
  lsl: null,
  usl: null,
  appraiserCount: 3,
  partCount: 10,
  trialCount: 3,
  remark: ''
})

const names = ref(['', '', ''])
const matrix = ref([])

onMounted(async () => {
  rebuild()
  const [g, p, it] = await Promise.all([
    http.get('/gauges', { params: { status: 'ACTIVE' } }),
    http.get('/products'),
    http.get('/items')
  ])
  gauges.value = g || []
  products.value = p || []
  items.value = it || []
})

/** 评价人数变了要同步姓名数组，多出来的补空、少了截掉 */
watch(
  () => form.appraiserCount,
  (n) => {
    const next = names.value.slice(0, n)
    while (next.length < n) next.push('')
    names.value = next
  }
)

/**
 * 结构变了就重建矩阵，尽量保留已经填过的值。
 *
 * 现场常见的是「本来按 3 人录，发现第 3 个人没来，改成 2 人」——
 * 重建时把前两列原样搬过去，别让人重填一遍 60 个格子。
 */
function rebuild() {
  const next = []
  for (let i = 0; i < form.appraiserCount; i++) {
    const parts = []
    for (let j = 0; j < form.partCount; j++) {
      const trials = []
      for (let k = 0; k < form.trialCount; k++) {
        const old = matrix.value[i] && matrix.value[i][j] ? matrix.value[i][j][k] : ''
        trials.push(old === undefined ? '' : old)
      }
      parts.push(trials)
    }
    next.push(parts)
  }
  matrix.value = next
}

function onInput(i, j, k, e) {
  matrix.value[i][j][k] = e.target.value
}

/**
 * 从 Excel 整块粘贴。
 *
 * 90 个格子一个个敲是不现实的，现场多是先在纸上或 Excel 里记好再一次性录进来。
 * 只有多行或多列才接管（单格粘贴照常交给输入框），否则「粘贴一个数」会被吃掉。
 */
function onPaste(i, j, k, e) {
  const text = (e.clipboardData || window.clipboardData).getData('text')
  if (!text || (!text.includes('\t') && !text.includes('\n'))) return
  e.preventDefault()
  const rows = text.replace(/\r/g, '').split('\n').filter((r) => r.trim().length)
  let count = 0
  let skipped = 0
  rows.forEach((line, dj) => {
    line.split('\t').forEach((cell, dk) => {
      const jj = j + dj
      const kk = k + dk
      if (jj < form.partCount && kk < form.trialCount && i < form.appraiserCount) {
        matrix.value[i][jj][kk] = cell.trim()
        count++
      } else {
        skipped++
      }
    })
  })
  if (skipped > 0) {
    ElMessage.warning(`粘贴了 ${count} 个，有 ${skipped} 个超出当前表格范围（${form.partCount} 件 × ${form.trialCount} 次），超出的没放进去`)
  } else {
    ElMessage.success(`粘贴了 ${count} 个读数`)
  }
}

const filled = computed(() => filledCells(matrix.value))

const preview = computed(() => compute(matrix.value, form.lsl, form.usl))

const tagType = computed(() => ({ GOOD: 'success', MARGINAL: 'warning', BAD: 'danger' }[preview.value?.verdict] || 'info'))
const tone = computed(() => ({ GOOD: 'good', MARGINAL: 'warn', BAD: 'bad' }[preview.value?.verdict] || ''))

function onItem(id) {
  const it = items.value.find((x) => x.id === id)
  if (!it) return
  if (!form.characteristic) form.characteristic = it.itemName || ''
  if (!form.unit) form.unit = it.unit || ''
  if (form.lsl === null && it.defaultMin !== null && it.defaultMin !== undefined) form.lsl = Number(it.defaultMin)
  if (form.usl === null && it.defaultMax !== null && it.defaultMax !== undefined) form.usl = Number(it.defaultMax)
}

async function save() {
  if (!form.gaugeId) return ElMessage.warning('先选一把量具——研究的对象就是它')
  if (!form.characteristic.trim()) return ElMessage.warning('写清研究的是哪个特性')
  const namesFilled = names.value.map((n) => (n || '').trim())
  if (namesFilled.some((n) => !n)) return ElMessage.warning('每位评价人的名字都要填，不然出了再现性问题不知道问谁')
  if (new Set(namesFilled).size !== namesFilled.length) return ElMessage.warning('评价人重名了，加个区分，比如「张三(一车间)」')

  const shape = checkShape(form.appraiserCount, form.partCount, form.trialCount)
  if (shape) return ElMessage.warning(shape)

  const { filled: ok, total } = filled.value
  if (ok < total) return ElMessage.warning(`还有 ${total - ok} 个格子没填（共 ${total} 个）`)

  const readings = matrix.value.map((parts) => parts.map((trials) => trials.map((v) => Number(v))))
  saving.value = true
  try {
    await http.post('/msa', {
      gaugeId: form.gaugeId,
      productId: form.productId,
      itemId: form.itemId,
      characteristic: form.characteristic.trim(),
      unit: form.unit.trim() || null,
      lsl: form.lsl,
      usl: form.usl,
      appraisers: namesFilled,
      readings,
      remark: form.remark.trim() || null
    })
    ElMessage.success('研究已保存')
    router.push('/msa')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hd {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.sec {
  font-weight: 600;
  margin: 18px 0 8px;
  font-size: 14px;
}
.lbl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
.tip {
  margin-top: 8px;
  line-height: 1.7;
}
.paste-hint {
  margin-left: 12px;
}
.live {
  margin-left: 14px;
  font-size: 13px;
  font-weight: 600;
}
.names {
  display: flex;
  gap: 8px;
}
.grid {
  border-collapse: collapse;
  font-size: 13px;
}
.grid th,
.grid td {
  border: 1px solid var(--el-border-color-lighter);
  padding: 0;
}
.grid thead th {
  background: var(--el-fill-color-lighter);
  font-weight: 500;
  padding: 6px 10px;
  white-space: nowrap;
}
.grid .corner {
  background: var(--el-fill-color-lighter);
  font-weight: 500;
  padding: 6px 10px;
  width: 64px;
  text-align: center;
}
.cell {
  width: 96px;
  border: 0;
  outline: none;
  padding: 7px 8px;
  font-size: 13px;
  background: transparent;
  color: var(--el-text-color-primary);
}
.cell:focus {
  background: var(--el-color-primary-light-9);
}
.bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 0 0;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}
.spacer {
  flex: 1;
}
.preview {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pct {
  font-size: 22px;
  font-weight: 600;
}
.good {
  color: #67c23a;
}
.warn {
  color: #e6a23c;
}
.bad {
  color: #f56c6c;
}
</style>
