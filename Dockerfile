# Multi-stage: the toolchain never ends up in the shipped image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
COPY data ./data
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/mini-search.jar /app/mini-search.jar
VOLUME /app/data
EXPOSE 9200
# A read-only-ish search service: the index snapshot and any crawled pages land in /app/data.
# --host 0.0.0.0 is required here, not a lapse: inside the container the loopback interface is
# unreachable from the host, so Docker's published port would connect to nothing. The CLI default
# is 127.0.0.1 -- see `--host` in `serve`.
ENTRYPOINT ["java","-jar","/app/mini-search.jar","serve","--port","9200","--host","0.0.0.0","--data","/app/data"]
