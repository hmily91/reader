#!/bin/sh
# -----------------------------------------------------------------------------
#  以 root 启动 -> 修正宿主机挂载目录的属主 -> 降权到 uid/gid 1000 再拉起 JVM
#
#  为什么需要这段：镜像以非 root（reader:1000）运行，但镜像里 `chown /storage`
#  对「挂载点」永远无效 —— bind mount 的 uid/gid 来自宿主机，容器首次启动时
#  docker 自动创建的目录也是 root:root。直接以 reader 启动会撞上
#      java.io.IOException: Permission denied -> /storage/xxx.json
#  所以 entrypoint 只在这一瞬间保持 root，chown 完立刻 exec 切到普通用户，
#  并且用 exec 保证 java 仍是 PID 1（tini 之下），SIGTERM 能被正确接收、
#  触发 Spring Boot 优雅停机。
#
#  只 chown 目录本身，不递归整棵子树：书库/缓存可能有上万文件，
#  每次重启都递归一遍会显著拖慢启动。
# -----------------------------------------------------------------------------
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
DATA_DIRS="${DATA_DIRS:-/storage /logs}"

if [ "$(id -u)" = "0" ]; then
    for d in ${DATA_DIRS}; do
        mkdir -p "${d}" 2>/dev/null || true
        # 只读挂载等场景允许失败，让应用自己报出更明确的错误
        chown "${PUID}:${PGID}" "${d}" 2>/dev/null || true
    done
    # su-exec 是 alpine 原生的 gosu 等价物：设置 uid/gid 后 exec，进程不 fork
    exec su-exec "${PUID}:${PGID}" java ${JAVA_OPTS:-} -jar /app/bin/reader.jar "$@"
fi

# 显式 --user 启动时已经是非 root，直接执行
exec java ${JAVA_OPTS:-} -jar /app/bin/reader.jar "$@"
