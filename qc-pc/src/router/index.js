import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getStoredUser, getToken } from '../api/http'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', component: () => import('../views/BatchList.vue') },
  { path: '/inspections', component: () => import('../views/InspectionList.vue') },
  { path: '/inspection/:id', component: () => import('../views/InspectionEdit.vue') },
  { path: '/report/:id', component: () => import('../views/ReportPrint.vue') },
  { path: '/standards', component: () => import('../views/StandardList.vue') },
  { path: '/standards/:id', component: () => import('../views/StandardEdit.vue') },
  { path: '/gauges', component: () => import('../views/GaugeList.vue') },
  { path: '/defects', component: () => import('../views/DefectList.vue') },
  // 主数据维护只对管理员开放：这些表一改，所有产品的判定口径都跟着变
  { path: '/defect-codes', component: () => import('../views/DefectCodeList.vue'), meta: { roles: ['ADMIN'] } },
  { path: '/users', component: () => import('../views/UserList.vue'), meta: { roles: ['ADMIN'] } },
  { path: '/trace', component: () => import('../views/TraceQuery.vue') },
  { path: '/stat', component: () => import('../views/StatDashboard.vue') }
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
