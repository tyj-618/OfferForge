# Easy Offer Forge 后端：多阶段构建（JDK 17 编译 + JRE 17 运行）

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷 pom 预拉依赖，利用 Docker 层缓存
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
# 测试由 CI/本地 mvnw test 保障，镜像构建跳过以提速
RUN mvn -B clean package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/offerforge-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
