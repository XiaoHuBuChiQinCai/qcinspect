import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api/http'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', component: () => import('../views/BatchList.vue') },
  { path: '/inspection/:id', component: () => import('../views/InspectionEdit.vue') },
  { path: '/report/:id', component: () => import('../views/ReportPrint.vue') },
  { path: '/standards', component: () => import('../views/StandardList.vue') },
  { path: '/standards/:id', component: () => import('../views/StandardEdit.vue') },
  { path: '/gauges', component: () => import('../views/GaugeList.vue') },
  { path: '/defects', component: () => import('../views/DefectList.vue') },
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
  return true
})

export default router
