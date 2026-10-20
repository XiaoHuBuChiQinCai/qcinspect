import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * 这个项目原本是 HBuilderX 版：pages / main.js / App.vue / pages.json 都直接放在项目根目录。
 * uni-app 的 CLI 默认只找 `src/`，所以用 UNI_INPUT_DIR 把输入目录指回根目录 ——
 * 好处是源码结构一行不用改，HBuilderX 和命令行两种方式都能构建同一个项目。
 *
 * 注意：这个变量必须由 package.json 的 scripts 用 cross-env 在进程启动前注入，
 * 在这里赋值太晚（CLI 的 initEnv 在加载 vite.config 之前就跑完了）。
 */
process.env.UNI_INPUT_DIR = process.env.UNI_INPUT_DIR || process.cwd()

export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5174,
    proxy: {
      // 本地起 H5 调试时把 /api 转到后端，跟线上 Nginx 的行为保持一致
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
