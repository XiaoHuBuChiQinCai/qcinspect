<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">工件质检</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">待检批次</el-menu-item>
        <el-menu-item index="/standards">检验标准</el-menu-item>
        <el-menu-item index="/defects">不良处置</el-menu-item>
        <el-menu-item index="/gauges">量具台账</el-menu-item>
        <el-menu-item index="/trace">批次追溯</el-menu-item>
        <el-menu-item index="/stat">质量看板</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>品质管理部</span>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
</script>

<style>
html, body, #app { height: 100%; margin: 0; }
.layout { height: 100%; }
.aside { border-right: 1px solid #e4e7ed; background: #fff; }
.logo { height: 56px; line-height: 56px; text-align: center; font-size: 16px; font-weight: 500; letter-spacing: 2px; }
.header { display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fff; }
</style>
