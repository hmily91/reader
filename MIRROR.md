# reader 复刻镜像

自用镜像仓库，用于在上游官方镜像消失后继续可复现地部署「阅读3」服务端。

## 来源与署名

| 项 | 值 |
| --- | --- |
| 上游项目 | [changshengyu/reader-dev](https://github.com/changshengyu/reader-dev) @ `master` |
| 上游血缘 | fork of [hectorqin/reader](https://github.com/hectorqin/reader)（原作者仓库的 Java/Kotlin 版本已被清空，仅能从 fork 追溯） |
| 技术栈 | 后端 Kotlin + Spring Boot 2.1.6.RELEASE + Vert.x；前端 Vue CLI 4 |
| 应用版本 | 2.5.4（取自 `cli.gradle` / `web/package.json`） |
| 许可证 | GPL-3.0（`LICENSE` 原样保留，未做任何修改） |
| 镜像 | `${DOCKERHUB_USER}/reader`，标签 `latest` / `2.5.4` / `2.5` / `2` / `sha-<commit>` |

**业务代码零改动。** 本仓库相对上游新增/修改的文件只有构建与编排相关部分：

```
Dockerfile                      # 替换原 Dockerfile（原版仅为 FROM hectorqin/reader，上游已删库）
docker-entrypoint.sh            # 新增：挂载目录属主修正 + 降权到 uid/gid 1000
docker/gradle-init.gradle       # 新增：摘除已下线的 jcenter 仓库，不改业务代码
docker-compose.yml              # 替换：原版指向已失效的 hectorqin/reader:openj9-latest
.env.example                    # 新增：鉴权等敏感项外置
.github/workflows/build-mirror.yml  # 新增：amd64 + arm64 自动构建推送
.dockerignore                   # 修改：压缩构建上下文
```

## 镜像设计

- **三阶段构建**：`web`（yarn build）→ `build`（Gradle → fat-jar）→ `runtime`（仅 JRE + jar）。
- **多架构**：`linux/amd64` + `linux/arm64`。前端与 jar 固定在 `$BUILDPLATFORM` 原生编译，
  仅运行时层按目标架构拉取，避免 QEMU 全量模拟 Kotlin/Spring 编译。
- **非 root**：entrypoint 以 root 启动只做一件事——`chown` 宿主机挂载目录，随后
  `su-exec` 降权到 `uid/gid=1000`，`exec` 保证 java 仍是 PID 1，SIGTERM 可触发优雅停机。
- **体积**：源码、Gradle 发行包与缓存、npm/yarn 缓存全部留在中间层，最终镜像只有
  `amazoncorretto:8u442-alpine3.21-jre` + 一个 fat-jar。

## 手动构建

```bash
# 单架构（本机）
docker build -t ${DOCKERHUB_USER}/reader:2.5.4 -f Dockerfile .

# 多架构（需 buildx + QEMU）
docker buildx create --use --name reader-builder
docker buildx build --platform linux/amd64,linux/arm64 \
  -t ${DOCKERHUB_USER}/reader:2.5.4 \
  -t ${DOCKERHUB_USER}/reader:2.5 \
  -t ${DOCKERHUB_USER}/reader:2 \
  -t ${DOCKERHUB_USER}/reader:latest \
  -f Dockerfile . --push
```

## 部署

```bash
cp .env.example .env     # 填写管理员密码与邀请码
docker compose up -d
docker compose ps        # 等待状态变为 healthy
```

默认访问 `http://<your-host>:4396`。容器内固定端口为 8080（`/health` 探测也走 8080）。

## 注意事项

- `SPRING_PROFILES_ACTIVE=prod` 时日志只写入 `/logs`（按天 + 100MB 滚动），
  `docker compose logs` 看不到应用日志；需要 stdout 日志改成 `default`。
- 上游 `reader.app.secure` 默认为 `false`（完全无鉴权），`secureKey` / `inviteCode`
  默认为空字符串。本 compose 已强制 `true` 并要求从 `.env` 提供，请勿改回。
