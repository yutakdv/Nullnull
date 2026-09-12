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
# ProblemFixtureContractTest pins packages/contracts/fixtures/problems to ProblemCode, so the
# fixtures have to be inside the image: the test reads them and fails loudly when they are
# absent rather than skipping, which is how the native api-quality run passed while this one
# could not even find the directory.
COPY packages/contracts/fixtures /workspace/packages/contracts/fixtures
COPY apps/ai/contracts /workspace/apps/ai/contracts
COPY apps/ai/tests/recommendation/fixtures /workspace/apps/ai/tests/recommendation/fixtures
# The ITEM fixture corpus is read through its manifest (checksums, kinds), and PolicyPinsParityTest
# hashes the policy file itself; both suites fail closed without these, so they are part of the image.
COPY apps/ai/tests/recommendation/manifest.json /workspace/apps/ai/tests/recommendation/manifest.json
COPY apps/ai/src/nullnull_ai/policy/policy-v1.yaml /workspace/apps/ai/src/nullnull_ai/policy/policy-v1.yaml
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
