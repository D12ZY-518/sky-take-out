# syntax=docker/dockerfile:1.7
#
# Dockerfile.java —— Java 服务（sky-server 多模块）多阶段构建
# 为什么多阶段：构建层含完整 JDK+Maven+依赖（约 1GB+），运行层只要 JRE+一个 fat jar（约 250MB），
#               编译器和本地 .m2 全部留在中间层，不进最终镜像（= Java 世界的"构建产物与工具链分离"）。
# 为什么在容器里构建：可复现，不依赖本机是否装了 Maven/IDEA；配套阿里云 settings.xml 解决国内下载。
#
# 构建上下文 = 项目根（多模块 reactor 需要看到根 pom 与 sky-common/sky-pojo 两个兄弟模块）

# ==================== 阶段 1：Maven 构建 ====================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 阿里云 Maven 镜像（仅容器内构建用，不动本机 IDEA 配置）
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 先只拷三个 pom：只要依赖清单没变，下面两层就能一直命中缓存，改 Java 源码不会触发依赖重下
COPY pom.xml ./
COPY sky-common/pom.xml sky-common/
COPY sky-pojo/pom.xml sky-pojo/
COPY sky-server/pom.xml sky-server/

# 预下载依赖（go-offline 偶尔漏解析部分插件依赖，失败不致命：真正 package 时会补齐）
# --mount=type=cache：把 .m2 仓库挂成 BuildKit 缓存卷，跨构建复用，不走镜像层
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -q -s /root/.m2/settings.xml -pl sky-server -am dependency:go-offline -Dmaven.test.skip=true || true

# 再拷源码构建 fat jar（spring-boot-maven-plugin repackage 产物：sky-server/target/sky-server-1.0-SNAPSHOT.jar）
COPY sky-common/src sky-common/src
COPY sky-pojo/src sky-pojo/src
COPY sky-server/src sky-server/src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s /root/.m2/settings.xml -pl sky-server -am package -Dmaven.test.skip=true

# ==================== 阶段 2：JRE 运行 ====================
FROM eclipse-temurin:17-jre
WORKDIR /app

# 容器内时区默认 UTC，订单时间字段会差 8 小时
ENV TZ=Asia/Shanghai

COPY --from=builder /build/sky-server/target/sky-server-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
# 注意：JVM 参数写在 -jar 之前；Spring 参数（如 --spring.profiles.active=xx）要写在 -jar xxx.jar 之后
ENTRYPOINT ["java", "-jar", "app.jar"]
