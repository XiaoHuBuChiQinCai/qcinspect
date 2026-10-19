import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', component: () => import('../views/BatchList.vue') },
  { path: '/inspection/:id', component: () => import('../views/InspectionEdit.vue') },
  { path: '/standards', component: () => import('../views/StandardList.vue') },
  { path: '/stat', component: () => import('../views/StatDashboard.vue') }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
