# ---------- 构建阶段 ----------
# 用固定小版本，不用 latest：Vite 5 在 Node 18+ 都能跑，但浮动标签迟早会撞上主版本升级
FROM node:22-alpine AS build
WORKDIR /build

# 国内服务器拉 npm 官方源经常卡住，这里显式走镜像站；要换回官方源改这一行即可
COPY package.json ./
RUN npm install --no-audit --no-fund --registry=https://registry.npmmirror.com

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 只留静态产物和站点配置，镜像里不带 node 与源码
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
