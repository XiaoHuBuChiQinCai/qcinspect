# 部署到腾讯云轻量应用服务器

面向 **2 核 2G / 50G SSD / 4M 带宽** 这一档。整套东西（PostgreSQL + 后端 + PC 前端）都跑在
一台机器上，用 Docker Compose 编排。

---

## 一、镜像怎么选

**结论：选「系统镜像 → Ubuntu 24.04 LTS 64 位」**（Debian 12 也可以，见下）。

购买页默认停在「应用镜像」页签，下拉里那些 **Hermes Agent / OpenClaw / Octop / 宝塔面板
全部不要选**，理由分两类：

| 类型 | 为什么不选 |
|---|---|
| Hermes Agent、OpenClaw、Octop（AI 助手类） | 预装一整套与本项目无关的常驻服务，2G 内存本来就不宽裕，白占几百 MB；端口、进程、开机自启还会跟你自己的东西打架 |
| 宝塔 / LNMP 面板 | 面板本身常驻吃内存，且它装的 PostgreSQL / JDK 版本由面板决定，跟你需要的 **PG 16 + JDK 21** 很难刚好对上。本项目 Dockerfile 已经把运行时锁死了，宿主机装什么都不需要 |

选系统镜像的原因很直白：**这套东西的依赖全在镜像里**。宿主机只要能跑 Docker，
`docker compose up` 就能起，不需要预装 Java、PostgreSQL、Node。

Ubuntu 24.04 与 Debian 12 二选一：

- **Ubuntu 24.04 LTS** —— 腾讯云镜像更新及时，社区资料多，`get.docker.com` 一键脚本兼容最好
- **Debian 12** —— 常驻服务更少，同样 2G 内存能多挤出 60–80MB；但遇到问题搜到的答案少一些

不确定就选 Ubuntu 24.04。**Arch / CentOS Stream / OpenCloudOS 不要选**，Docker 与内核版本的坑
不值得为省这几十兆去踩。

地域选离厂区最近的（截图里是北京）。轻量服务器的地域买了之后不能改。

---

## 二、2G 内存到底够不够

先算账（本编排已经按这个账配好了）：

| 组件 | 实占 | 说明 |
|---|---|---|
| 系统 + dockerd | ~300 MB | Ubuntu 24.04 空载 |
| PostgreSQL 16 | ~250 MB | `shared_buffers=128M`，已在编排里压住 |
| qc-app（Java） | ~800 MB | `-Xmx512m` + Metaspace 192M + 线程栈 |
| qc-web（Nginx） | ~20 MB | 静态文件 + 反代 |
| **合计** | **~1.37 GB** | 2G 里还剩 ~600 MB 余量 |

结论：**够用，但没有富余**。由此有两条硬约束：

1. **不跑 MinIO**。对象存储单独要 200–300MB，塞进去就贴着红线了。照片改落宿主机磁盘卷
   （`STORAGE_TYPE=local`，`qc-files` 卷），50G 盘放几十万张现场照片没问题
2. **必须显式限 JVM 堆**。`qc-server/Dockerfile` 里写的是 `-XX:MaxRAMPercentage=70`，
   在 2G 宿主上 JVM 会认领 **1.4G 堆**，加上 PG 立刻开始 swap，最后被 OOM killer 干掉。
   `docker-compose.prod.yml` 里已经用 `JAVA_OPTS=-Xmx512m` 覆盖掉，别删

真要同时上 MinIO 或并发用户多，升到 2C4G 更省事。

---

## 三、上线三步

### 1. 装 Docker

```bash
# 用 ubuntu 用户登录（腾讯云镜像默认就是它，不要再开 root）
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# 退出重登一次，让 docker 组生效
```

验证：`docker version` 能打出来，且 `docker compose version` 有输出（Compose V2 随 Docker 一起装）。

### 2. 加 swap（**这步别跳，原因是构建会 OOM**）

2G 机器上 `docker compose build` 编 Java 后端，Maven 要吃 1G 以上；此时 PG 还在跑，
不加 swap 基本必然被内核杀掉，报错长这样：

```
Killed
ERROR: process "/bin/sh -c mvn -B -ntp -q -DskipTests package" did not complete successfully: exit code: 137
```

exit code 137 = 128 + 9，就是 SIGKILL。

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h   # 确认 Swap 那一行有 2.0Gi
```

### 3. 拉代码、配环境、起服务

仓库是私有的，服务器上直接 `git clone` 会要求凭据。两条路，选一条：

**A. 加 Deploy Key（推荐，一次配好以后 `git pull` 就能升级）**

```bash
ssh-keygen -t ed25519 -C "qc-server" -f ~/.ssh/id_ed25519 -N ''
cat ~/.ssh/id_ed25519.pub
# 复制输出，到 GitHub 仓库 → Settings → Deploy keys → Add deploy key（只勾读权限）
```

**B. 从本地直接传**（不想在服务器上配 GitHub 时用）

```bash
# 本地执行，把整个仓库（含 .git，方便以后 pull）传上去
scp -r C:/qcinspect ubuntu@<公网IP>:~/
```

然后：

```bash
git clone git@github.com:XiaoHuBuChiQinCai/qcinspect.git
cd qcinspect

cp .env.example .env
vi .env          # 至少改 DB_PASSWORD / JWT_SECRET / ADMIN_PASSWORD

docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f app   # 看到 Started QcApplication 就成了
```

首次构建约 5–15 分钟（Docker 要拉 JDK21、Node22、Nginx 基础镜像，外加 Maven 全量依赖）。
之后再改代码只需 `up -d --build`，依赖层有缓存，快得多。

访问 `http://<公网IP>`，用 `.env` 里的 `ADMIN_USER / ADMIN_PASSWORD` 登录。

> **轻量服务器的防火墙是独立的一层**，买了机器要去控制台「防火墙」页放行 TCP **80**
> （默认只开 22 和 ICMP）。宿主机 `ufw` 一般没开，别只改一边。

---

## 四、不用 swap 的另一条路：本地构建后传镜像

如果不想给服务器加 swap，就在本地把镜像打好再传上去。镜像约 300–400MB，4M 带宽
传 10 分钟左右，比在服务器上 swap 里编译快。

```bash
# 本地（有 Docker 的机器，比如你的开发机）
docker compose -f docker-compose.prod.yml build
docker save qcinspect-app qcinspect-web -o qc-images.tar
# 注意镜像名取决于目录名，先用 docker images 确认实际 tag

scp qc-images.tar ubuntu@<公网IP>:~/
```

```bash
# 服务器
docker load -i ~/qc-images.tar
docker compose -f docker-compose.prod.yml up -d   # 不加 --build
```

---

## 五、移动端 H5

H5 挂在**同一个 Nginx 容器的 `/h5/` 下**，不额外开服务：`http://<域名>/h5/`。
`manifest.json` 里 `h5.router.base` 已经是 `/h5/`，走 hash 路由。

### 服务器上构建（推荐）

服务器装了 Node 18 + npm 9，`qc-mobile/.npmrc` 已指向 npmmirror：

```bash
cd qc-mobile
npm install          # 首次约 30 秒，之后有缓存
npm run build:h5     # 产物 dist/build/h5
cd .. && docker compose -f docker-compose.prod.yml restart web
```

产物通过 compose 的只读挂载直接进 Nginx，**改 H5 不需要重建镜像**。

### 本地构建再上传（不想在服务器装 Node 时）

```bash
cd qc-mobile && npm install && npm run build:h5
rsync -avz --delete dist/build/h5/ ubuntu@<IP>:/root/qc/qcinspect/qc-mobile/dist/build/h5/
```

### 本地调试

```bash
cd qc-mobile && npm run dev:h5     # http://localhost:5174
```

`/api` 由 vite 代理到 `localhost:8080`，跟线上的 Nginx 行为一致。

### 为什么源码没挪进 src/

uni-app 的 CLI 默认只找 `src/`，但这是个 HBuilderX 版项目，`pages/`、`main.js`
都在根目录。`package.json` 里用 `cross-env UNI_INPUT_DIR=. uni build` 把输入目录指回根目录，
**源码结构一行没动**——HBuilderX 和命令行两种方式都能构建同一个项目。

注意这个变量必须由 cross-env 在进程启动前注入，写在 `vite.config.js` 里太晚：
uni 的 `initEnv` 在加载 vite 配置之前就跑完了。

### 接口地址

`common/api.js` 里用条件编译分开：

```js
// #ifdef H5
const BASE = '/api'                        // 同域反代，换服务器不用改代码
// #endif
// #ifndef H5
const BASE = 'http://localhost:8080/api'   // 小程序/APP 只能绝对地址
// #endif
```

### 微信小程序

**必须 HTTPS + 已备案域名**，IP 直连只能调试、发布过不了审核：

- 域名完成 ICP 备案（腾讯云可代办，通常 1–2 周）
- 申请免费 SSL 证书，Nginx 加 443 监听
- 小程序后台「开发管理 → 服务器域名」里把 `https://你的域名` 加进 request 合法域名
- 构建前把 `api.js` 里 `#ifndef H5` 分支的地址改成你的域名

内网自用、不上小程序的话，直接访问 `/h5/` 即可。

---

## 六、日常运维

```bash
docker compose -f docker-compose.prod.yml ps              # 看状态
docker compose -f docker-compose.prod.yml logs -f app     # 跟后端日志
docker compose -f docker-compose.prod.yml restart app     # 重启后端

# 备份（脚本已放到 scripts/backup.sh，crontab 见下一节）
/root/qc/qcinspect/scripts/backup.sh

# 照片目录备份
docker run --rm -v qcinspect_qc-files:/data -v ~:/out alpine \
  tar czf /out/files-$(date +%F).tar.gz -C /data .
```

### 数据库备份

`scripts/backup.sh` 做三件事：`pg_dump` 打包成 gzip、校验完整性、清掉超过保留期的旧文件。
为什么不用自定义格式：出事那天往往不是你在操作，接手的人只需要会 `psql`；
自定义格式要 `pg_restore`、版本对不上还得折腾。体积换恢复简单，值。

```bash
# 手动跑一次
cd /root/qc/qcinspect && ./scripts/backup.sh
# 默认落 /root/qc/backups/qcinspect-YYYYmmdd-HHMMSS.sql.gz，保留 30 天
# 可用环境变量覆盖：QC_BACKUP_DIR / QC_BACKUP_KEEP_DAYS / QC_DB_CONTAINER
```

挂 cron（每天凌晨 2 点，日志单独存一份，方便事后查哪天漏了）：

```bash
chmod +x /root/qc/qcinspect/scripts/backup.sh
crontab -e
# 追加：
0 2 * * * /root/qc/qcinspect/scripts/backup.sh >> /var/log/qc-backup.log 2>&1
```

**恢复**（先停应用，别在有人录入的时候灌数据）：

```bash
docker compose -f docker-compose.prod.yml stop app
gunzip -c /root/qc/backups/qcinspect-20261021-020000.sql.gz \
  | docker exec -i qc-db psql -U qc -d qcinspect
docker compose -f docker-compose.prod.yml start app
```

备份文件留在服务器本地卷上只能防「误删表」，防不了整机丢。真正的异地备份
需要对象存储（COS）+ `coscmd`，或者定期 `scp` 到另一台机器——这一步没做，
**别把服务器上的备份当成最终保障**。

**升级**：`git pull` 之后 `docker compose -f docker-compose.prod.yml up -d --build`。
新增的 `db/V7__*.sql` 不会自动执行——`docker-entrypoint-initdb.d` 只在数据卷为空时跑一次。
存量库升级要手动执行：

```bash
docker exec -i qc-db psql -U qc -d qcinspect < db/V7__xxx.sql
```

（`V2`~`V6` 都是幂等脚本，重复执行安全；`V1` 不是，别对已有库重跑。）

---

## 七、踩坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| 浏览器 `ERR_CONNECTION_REFUSED` | 轻量防火墙没放行 80 | 控制台防火墙加规则，别只查 ufw |
| 页面能开但接口 502 | app 容器没起来 | `logs app`，多半是 `JWT_SECRET` 短于 32 字节 |
| 构建 exit code 137 | Maven 被 OOM kill | 加 swap，见第三节 |
| 登录后所有列表空白 | 数据库脚本没跑（卷已存在导致跳过 initdb） | 手动 `psql -f db/V1__init.sql` 后续脚本 |
| 手机拍照上传失败 | Nginx 默认 1MB 限制 | `client_max_body_size` 已配 20m，检查是不是走了别的反代 |
| 发版后白屏 | 浏览器缓存了旧 `index.html` | 已配 `no-store`，强制刷新一次 |
