# ---------- 构建阶段 ----------
# 固定 21 版本，不用 latest：Lombok 注解处理器在 JDK 23+ 的默认行为变了，
# 用浮动版本会在某天突然编译失败，排查起来毫无线索。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom，把依赖层缓存下来；改代码时不必重新下载全部依赖
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"

# 只拷可执行 jar，不带 .jar.original
COPY --from=build /build/target/qc-server-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
