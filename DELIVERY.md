# reader 复刻部署交付说明

> 全文已脱敏。所有真实账号、域名、IP、凭据、绝对路径一律用占位符表示，对照表见文末。

---

## 一、已完成的动作

| # | 动作 | 结果 |
| --- | --- | --- |
| 1 | 定位可复刻的上游 | 官方 `hectorqin/reader` 的 Java/Kotlin 版本已被作者清空（`master` 仅剩 README，`main` 换成了全新的 Node.js 版）。改用仍在维护的 fork：`changshengyu/reader-dev`（Kotlin + Spring Boot 2.1.6 + Vert.x，GPL-3.0） |
| 2 | 镜像到 GitHub | 已 fork 为 `${GITHUB_USER}/reader`，保留全部提交历史、`LICENSE` 与上游署名 |
| 3 | 编写 Dockerfile | 三阶段构建、amd64/arm64、非 root、体积优化（见下） |
| 4 | 配置 CI | GitHub Actions 已跑通，构建 + 推送全部成功 |
| 5 | 推送镜像 | 已推送到 `${DOCKERHUB_USER}/reader`，标签：`latest` / `2.5.4` / `2.5` / `2` / `sha-d386f5a` |
| 6 | 优化 compose | 13 处改动，逐条说明见第二节 |

应用版本 **2.5.4**（取自 `cli.gradle` 与 `web/package.json`），多架构 manifest 摘要
`sha256:fcfb4c44…`，平台 `linux/amd64` + `linux/arm64`。

---

## 二、docker-compose.yml 优化逐条说明

基线是上游仓库自带的 `docker-compose.yml`（你此前实际使用的那份本次未随附，若端口/卷路径不同，把对应值改回即可，其余优化项可原样套用）。

| # | 优化项 | 上游基线 | 改后 | 原因 |
| --- | --- | --- | --- | --- |
| 1 | 镜像来源 | `hectorqin/reader:openj9-latest` | `${DOCKERHUB_USER}/reader:2.5.4` | 官方镜像已删库、拉取必失败；自建镜像并把版本钉死，保证可重复升级与回滚 |
| 2 | 重启策略 | `restart: always` | `unless-stopped` | `always` 在你手动 `docker stop` 后仍会被守护进程拉起，维护时反直觉；`unless-stopped` 保留崩溃自愈但尊重人工停止 |
| 3 | 停机宽限 | 无 | `stop_grace_period: 30s` | JVM 写书库/索引时被 SIGKILL 打断会损坏数据，留时间给 Spring 优雅停机 |
| 4 | 时区 | 无 | `TZ=Asia/Shanghai`、`LANG=C.UTF-8` | JVM 默认按 UTC 取时间，日志与阅读进度时间戳会差 8 小时 |
| 5 | 数据路径 | 依赖「工作目录为 /」的隐式约定 | 显式 `READER_APP_STORAGEPATH=/storage`、`LOGGING_PATH=/logs` | 相对路径 `storage`/`logs` 靠工作目录恰好落在挂载点，换工作目录就丢数据 |
| 6 | JVM 参数 | 无 | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` | 容器内 JVM 默认读宿主机内存上限，极易 OOM 或被 cgroup 杀掉；75% 留给 Vert.x/Netty 堆外与元空间 |
| 7 | 内存兜底 | 无 | 注释形式给出 `-Xmx` 版本 | 无 cgroup limit 的环境下按机器情况设硬上限 |
| 8 | **鉴权（安全）** | `secure` 默认 `false`、`secureKey` 默认 `""`、`inviteCode` 默认 `""` | `READER_APP_SECURE=true` + `${READER_SECURE_KEY:?…}` + `${READER_INVITE_CODE:?…}` | **上游默认是完全无鉴权**：任何人访问到端口即可读写全部书架与书源。现强制开启，且缺值时 compose 直接拒绝启动 |
| 9 | 卷挂载 | 写死 `/home/reader/logs`、`/home/reader/storage` | 相对 `./logs`、`./storage` | 整套配置可整体搬家 / Git 化；两份数据必须 `rw`（SQLite 与索引要写） |
| 10 | 健康检查 | 无 | `wget /health`，含 `start_period: 60s` | `/health` 是应用内免鉴权端点（返回 `ok!`）；Spring Boot 2.1 冷启动约 20~40s，没有 `start_period` 会在启动期被误判 unhealthy 并触发重启 |
| 11 | 日志轮转 | 无 | `json-file` + `max-size: 10m` / `max-file: 3` | prod profile 下应用日志会落盘滚动，但 stdout 仍被 json-file driver 无限累积 |
| 12 | 安全加固 | 无 | `no-new-privileges:true`、`cap_drop: [NET_RAW, MKNOD]` | 阻断 setuid 提权路径、去掉用不到的能力。不写 `user:` —— 挂载目录属主需要 entrypoint 以 root 修一次再降权 |
| 13 | watchtower | 默认启用，且挂载 `/var/run/docker.sock` | 注释掉，需要时手动开启 | 把 docker.sock 挂进容器等价于交出宿主机 root；且自动拉 `latest` 会覆盖你钉死的版本 |

另外：**webview 书源容器默认关闭**（体积大），需要时取消注释并同步打开 `READER_APP_REMOTEWEBVIEWAPI`。

### 上游其余安全相关默认值（未覆盖，按需自查）

`application.yml` 中这些项默认都是「放空」状态，如果你的场景涉及，请一并显式配置：

```yaml
reader.app.secure: false        # 已在本 compose 覆盖为 true
reader.app.secureKey: ""        # 已覆盖，从 .env 注入
reader.app.inviteCode: ""       # 已覆盖，从 .env 注入
reader.app.proxy: false         # 代理开关，默认关闭
reader.app.debug: false         # 调试模式，保持 false
reader.app.debugLog: false      # 调试日志，保持 false
reader.app.autoClearInactiveUser: 0   # 0 = 不清长期未登录账号
reader.app.userLimit: 50        # 代码内上限即 50
```

---

## 三、构建与推送命令清单

### 方式 A：GitHub Actions 自动（已配置好，推荐）

推送到 `master`、打 `v*` 标签，或手动触发都会运行：

```bash
# 手动触发（Actions → Build & Push Multi-Arch Image → Run workflow）
#   version    : 留空则自动从 cli.gradle 读取
#   push_image : false 表示只构建不推送，用来验证 Dockerfile
```

### 方式 B：本地 / NAS 手动构建

```bash
# 1) 登录（PAT 不要写进命令行历史，用 --password-stdin）
echo "${DOCKERHUB_TOKEN}" | docker login -u "${DOCKERHUB_USER}" --password-stdin

# 2) 单架构快速验证
docker build -t "${DOCKERHUB_USER}/reader:2.5.4" -f Dockerfile .

# 3) 多架构构建并推送
docker buildx create --use --name reader-builder
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t "${DOCKERHUB_USER}/reader:2.5.4" \
  -t "${DOCKERHUB_USER}/reader:2.5" \
  -t "${DOCKERHUB_USER}/reader:2" \
  -t "${DOCKERHUB_USER}/reader:latest" \
  -f Dockerfile . --push

# 4) 核对多架构 manifest
docker buildx imagetools inspect "${DOCKERHUB_USER}/reader:2.5.4"
```

不改动业务功能的前提下可覆盖的基础镜像（网络不通时换镜像站）：

```bash
docker build --build-arg NODE_IMAGE=<mirror>/node:16-alpine \
             --build-arg JDK_IMAGE=<mirror>/eclipse-temurin:8-jdk \
             --build-arg RUNTIME_IMAGE=<mirror>/amazoncorretto:8u442-alpine3.21-jre \
             -t "${DOCKERHUB_USER}/reader:2.5.4" -f Dockerfile .
```

---

## 四、需要手动配置的 Secrets

### GitHub（仓库 `${GITHUB_USER}/reader` → Settings → Secrets and variables → Actions）

| 名称 | 用途 | 状态 |
| --- | --- | --- |
| `DOCKERHUB_USERNAME` | Docker Hub 命名空间，用于拼镜像名与登录 | **已配置** |
| `DOCKERHUB_TOKEN` | Docker Hub 个人访问令牌（PAT），用于登录推送 | **已配置** |

> 这两个已由本次流程写入，无需再手动添加。若你之后轮换令牌，在此处更新即可。

### Docker Hub（Account Settings → Personal access tokens）

| 项 | 说明 |
| --- | --- |
| `DOCKERHUB_TOKEN` | 读写权限的 PAT，仅供 GitHub Actions 使用；建议只给目标仓库的读写范围 |

### 本地部署（`.env`，不要提交进 git）

| 变量 | 说明 |
| --- | --- |
| `DOCKERHUB_USER` | 拉取镜像用的命名空间 |
| `READER_SECURE_KEY` | 管理员密码 |
| `READER_INVITE_CODE` | 注册邀请码 |
| `READER_PORT` | 宿主机端口，默认 4396 |

---

## 五、首次启动后的验证步骤

```bash
# 1) 校验 compose 与变量展开是否正确（会报错在缺 .env 变量时）
docker compose config

# 2) 启动
docker compose up -d

# 3) 等健康状态（冷启动约 20~40s，最多等 60s+）
docker compose ps          # STATUS 应为 Up (healthy)

# 4) 免鉴权健康端点
curl -sS http://<your-host>:4396/health        # 期望返回 ok!

# 5) 确认非 root 运行
docker exec reader id      # 期望 uid=1000(reader) gid=1000(reader)

# 6) 确认时区生效
docker exec reader date    # 应为 CST / +0800

# 7) 确认数据落盘
ls -la ./storage ./logs    # 两目录应已生成，属主 1000:1000

# 8) 打开 Web UI
#    http://<your-host>:4396
#    - 用 .env 里的邀请码注册第一个账号
#    - 用 .env 里的管理员密码（secureKey）登录管理端

# 9) 非健康时看日志（prod profile 下应用日志在 ./logs，stdout 只有启动信息）
docker compose logs --tail 100 reader
tail -f ./logs/reader-$(date +%F).0.log
```

### 常见失败点

| 现象 | 排查 |
| --- | --- |
| compose 直接拒绝启动，提示 `READER_SECURE_KEY` | 未创建 `.env` 或变量为空，这是有意设计的强制项 |
| 一直 `unhealthy` | 冷启动慢，`start_period` 已给 60s；超过则看 `./logs` 里是否有端口占用或存储目录权限问题 |
| `Permission denied` 写 `/storage` | entrypoint 的 chown 只在目录层生效；若宿主机目录属主异常，手动 `chown 1000:1000 ./storage ./logs` |
| 镜像拉取失败 | Docker Hub 上首次推送自动创建的仓库可能默认为**私有**，请到 Docker Hub 把 `${DOCKERHUB_USER}/reader` 设为 Public |

---

## 六、占位符对照表

| 占位符 | 含义 | 类别 |
| --- | --- | --- |
| `${DOCKERHUB_USER}` | Docker Hub 用户名 / 命名空间 | 账号与仓库名 |
| `${DOCKERHUB_TOKEN}` | Docker Hub 个人访问令牌 | 镜像仓库凭据 |
| `${GITHUB_USER}` | GitHub 用户名 | 账号与仓库名 |
| `${GITHUB_TOKEN}` | GitHub 个人访问令牌 | 凭据 |
| `${READER_SECURE_KEY}` | reader 管理员密码 | 密码 |
| `${READER_INVITE_CODE}` | reader 注册邀请码 | 密码/token |
| `<your-host>` / `<your-domain>` | 部署机器的域名或主机名 | 域名 |
| `<nas-ip>` | 部署机器的内网 IP | IP |
| `<project-dir>` | 本地工程目录绝对路径 | 绝对路径 |
| `<mirror>` | 镜像站地址 | 域名 |
| `${EMAIL}` | 邮箱地址 | 邮箱 |
| `sha-d386f5a` | 构建对应的源码 commit 短哈希 | —（非敏感，保留用于溯源） |

---

## 七、安全提醒

你提供的 `API.txt` 以明文存放了 GitHub PAT、Docker Hub PAT 以及另外两套服务的凭据。本次已经用到了前两者，建议：

1. 立即轮换 GitHub 与 Docker Hub 的 PAT（旧令牌一旦泄露即可读写你名下所有仓库）；
2. 把凭据移到密码管理器或环境变量，不要以明文文件放在桌面；
3. 轮换后到 GitHub 仓库 Settings → Secrets 更新 `DOCKERHUB_TOKEN`。
