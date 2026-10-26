import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getStoredUser, getToken } from '../api/http'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  // 首页就是工作台：登录后第一眼应该是「有几件事等我做」，而不是一屏批次
  { path: '/', component: () => import('../views/Workbench.vue') },
  { path: '/batches', component: () => import('../views/BatchList.vue') },
  { path: '/inspections', component: () => import('../views/InspectionList.vue') },
  { path: '/inspection/:id', component: () => import('../views/InspectionEdit.vue') },
  { path: '/report/:id', component: () => import('../views/ReportPrint.vue') },
  { path: '/standards', component: () => import('../views/StandardList.vue') },
  { path: '/standards/:id', component: () => import('../views/StandardEdit.vue') },
  { path: '/gauges', component: () => import('../views/GaugeList.vue') },
  { path: '/defects', component: () => import('../views/DefectList.vue') },
  // 主数据维护只对管理员开放：这些表一改，所有产品的判定口径都跟着变
  { path: '/master-data', component: () => import('../views/MasterData.vue'), meta: { roles: ['ADMIN'] } },
  { path: '/defect-codes', component: () => import('../views/DefectCodeList.vue'), meta: { roles: ['ADMIN'] } },
  // 供应商档案：批次上的供应商不再手打，只能从这份档案里选
  { path: '/suppliers', component: () => import('../views/SupplierList.vue'), meta: { roles: ['ADMIN'] } },
  { path: '/users', component: () => import('../views/UserList.vue'), meta: { roles: ['ADMIN'] } },
  // 日志里有作废理由、退回意见，等于把争议记录摊开给人看，只给管理员
  { path: '/audit', component: () => import('../views/AuditLog.vue'), meta: { roles: ['ADMIN'] } },
  { path: '/trace', component: () => import('../views/TraceQuery.vue') },
  { path: '/shipments', component: () => import('../views/ShipmentList.vue') },
  // 客诉登记对所有登录用户开放：客诉往往是销售、客服先接到的
  { path: '/complaints', component: () => import('../views/ComplaintList.vue') },
  { path: '/stat', component: () => import('../views/StatDashboard.vue') },
  // 质量月报：管理评审要交的那张表。看板回答「今天怎么样」，月报回答「这个月该让谁整改」
  { path: '/monthly', component: () => import('../views/MonthlyReport.vue') },
  // 过程能力分析：判定层只管收不收，这一页回答「这道工序稳不稳」
  { path: '/capability', component: () => import('../views/Capability.vue') },
  // 检验员分析：看的是「记录站不站得住」，不是绩效排名，所以只给管理员与审核员
  { path: '/inspectors', component: () => import('../views/InspectorStat.vue'), meta: { roles: ['ADMIN', 'REVIEWER'] } }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(to => {
  if (to.meta.public) {
    return true
  }
  if (!getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 前端拦住入口只是体验，真正的边界在后端 @PreAuthorize —— 直接改 hash 也进不来数据
  const user = getStoredUser() || {}
  if (Array.isArray(to.meta.roles) && !to.meta.roles.includes(user.role)) {
    ElMessage.warning('当前账号无权访问该页面')
    return { path: '/' }
  }
  return true
})

export default router
