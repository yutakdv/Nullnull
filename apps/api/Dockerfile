# syntax=docker/dockerfile:1
# Build context is the repository root (compose.integration.yml uses context: . with this file).
# Stages required by scripts/verify_target_stack.py: test, runtime. External FROM lines are digest-pinned.

FROM eclipse-temurin:21-jdk@sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b AS build
WORKDIR /workspace/apps/api
COPY apps/api/gradlew apps/api/settings.gradle.kts apps/api/build.gradle.kts ./
COPY apps/api/gradle ./gradle
RUN ./gradlew --no-daemon --version
COPY apps/api/src ./src
COPY docs/api/openapi.yaml /workspace/docs/api/openapi.yaml
COPY apps/ai/contracts /workspace/apps/ai/contracts
COPY apps/ai/tests/recommendation/fixtures /workspace/apps/ai/tests/recommendation/fixtures
RUN ./gradlew --no-daemon bootJar -x test resolveTestClasspaths

FROM build AS test
# compose.integration.yml runs this stage on an internal-only network; every dependency was resolved above.
# The compose command must list the same four suites and pass --offline.
CMD ["./gradlew", "--no-daemon", "--offline", "test", "integrationTest", "openapiContractTest", "recommendationTest"]

FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS runtime
RUN groupadd --system nullnull && useradd --system --gid nullnull --no-create-home nullnull
WORKDIR /app
COPY --from=build /workspace/apps/api/build/libs/nullnull-api.jar /app/nullnull-api.jar
USER nullnull
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/nullnull-api.jar"]
