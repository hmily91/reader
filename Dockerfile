# syntax=docker/dockerfile:1.7
# =============================================================================
#  reader （阅读3 服务端） 复刻镜像
#
#  上游来源：changshengyu/reader-dev @ master
#            后端 Kotlin + Spring Boot 2.1.6.RELEASE + Vert.x，前端 Vue CLI 4
#            版本：2.5.4
#
#  本文件只替换「构建与运行时基础设施」，业务代码与上游保持完全一致：
#    * 三阶段构建   前端(web) / 后端(fat-jar) / 运行时(JRE)，各层互不污染
#    * 多架构       linux/amd64 + linux/arm64；前端与 jar 固定跑在
#                   $BUILDPLATFORM（原生），只有运行时层按目标架构拉取，
#                   避免 QEMU 全量模拟 Kotlin/Spring 编译（可省下数十分钟）
#    * 非 root      容器以 root 启动仅用于修正挂载目录属主，随后降权到
#                   uid/gid 1000 执行 JVM
#    * 体积         最终镜像只含 fat-jar + JRE；源码、Gradle 缓存、
#                   npm/yarn 缓存、构建工具链全部留在中间层
# =============================================================================

# --- 可覆盖的基础镜像（网络不通时可用镜像站替换） -----------------------------
ARG NODE_IMAGE=node:16-alpine
ARG JDK_IMAGE=eclipse-temurin:8-jdk
ARG RUNTIME_IMAGE=amazoncorretto:8u442-alpine3.21-jre
ARG GRADLE_VERSION=7.6.4


# ============================ 1/3 前端 ======================================
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS web
WORKDIR /build/web
# 先只拷依赖清单，利用层缓存：代码变动不会重装依赖
COPY web/package.json ./
RUN yarn install --ignore-engines --network-timeout 600000
COPY web/ ./
RUN yarn build


# ============================ 2/3 后端 ======================================
FROM --platform=$BUILDPLATFORM ${JDK_IMAGE} AS build
# 上游 Dockerfile.source 里的原样保留：放开旧 TLS，避免老仓库握手失败
ENV JAVA_TOOL_OPTIONS="-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2,TLSv1.3"
WORKDIR /build

# 手写安装 Gradle 而不是用官方 gradle 镜像：官方 gradle 镜像只发布 amd64，
# 一旦用它，arm64 构建直接失败。Gradle 发行包是纯 Java zip，与 CPU 架构无关。
ARG GRADLE_VERSION
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends unzip ca-certificates curl; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL -o /tmp/gradle.zip \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
    unzip -q /tmp/gradle.zip -d /opt; \
    rm -f /tmp/gradle.zip; \
    ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle; \
    gradle --version

COPY cli.gradle gradle.properties settings.gradle ./
COPY gradle ./gradle
COPY src/lib ./src/lib
COPY src/main ./src/main
# 仅做「仓库地址替换」的 Gradle init 脚本（jcenter 已下线），不改业务与依赖版本
COPY docker/gradle-init.gradle /opt/gradle-init.gradle
# 前端产物打进 classpath，与上游行为一致（服务端同时承载 Web UI）
COPY --from=web /build/web/dist ./src/main/resources/web

RUN set -eux; \
    # 桌面端 UI 入口依赖 JavaFX，服务端构建必须移除 —— 上游同一步骤
    rm -f src/main/java/com/htmake/reader/ReaderUIApplication.kt; \
    gradle -b cli.gradle assemble --no-daemon --console=plain \
           --init-script /opt/gradle-init.gradle; \
    mkdir -p /out; \
    mv ./build/libs/*.jar /out/reader.jar


# ============================ 3/3 运行时 ====================================
FROM ${RUNTIME_IMAGE} AS runtime

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod \
    READER_APP_STORAGEPATH=/storage \
    LOGGING_PATH=/logs \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

RUN set -eux; \
    apk add --no-cache ca-certificates tzdata tini su-exec; \
    addgroup -g 1000 -S reader; \
    adduser -u 1000 -S -G reader reader; \
    mkdir -p /storage /logs /app; \
    chown -R reader:reader /storage /logs /app

COPY --from=build --chown=reader:reader /out/reader.jar /app/bin/reader.jar
COPY --chown=root:root docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh

WORKDIR /app
EXPOSE 8080
VOLUME ["/storage", "/logs"]

# /health 是 RestVerticle 里的免鉴权端点（返回 "ok!"），容器编排可直接探测
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${READER_SERVER_PORT:-8080}/health" || exit 1

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "-jar", "/app/bin/reader.jar"]
