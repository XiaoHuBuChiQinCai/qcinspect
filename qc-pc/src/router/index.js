import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', component: () => import('../views/BatchList.vue') },
  { path: '/inspection/:id', component: () => import('../views/InspectionEdit.vue') },
  { path: '/standards', component: () => import('../views/StandardList.vue') },
  { path: '/standards/:id', component: () => import('../views/StandardEdit.vue') },
  { path: '/gauges', component: () => import('../views/GaugeList.vue') },
  { path: '/defects', component: () => import('../views/DefectList.vue') },
  { path: '/trace', component: () => import('../views/TraceQuery.vue') },
  { path: '/stat', component: () => import('../views/StatDashboard.vue') }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
