import org.gradle.api.tasks.PathSensitivity
plugins {
    java
    `java-test-fixtures`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "io.nullnull"
version = "0.1.0-SNAPSHOT"
description = "Nullnull public web API (Java 21, Spring Boot modular monolith)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// docs/contracts/events.schema.json is the event canon (AGENTS.md). The runtime validates
// batches against it directly, so it is packaged rather than re-declared: a copy checked into
// src/main/resources would be a second definition, and the route allowlist has already been split
// in two once (PM-016) with only one half enforcing anything.
val canonicalEventSchema = layout.projectDirectory.file("../../docs/contracts/events.schema.json")

tasks.named<ProcessResources>("processResources") {
    from(canonicalEventSchema) {
        into("contracts")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.json.schema.validator)
    runtimeOnly("org.postgresql:postgresql")

    // Shared test support (Testcontainers wiring) for every suite that needs PostgreSQL.
    testFixturesImplementation("org.springframework.boot:spring-boot-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testFixturesImplementation("org.testcontainers:testcontainers-postgresql")
    testFixturesImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testFixturesImplementation("org.springframework:spring-test")
}

// Four suites with one meaning each (docs/engineering/TEST_STRATEGY.md):
//   test                 unit, architecture, pure domain
//   integrationTest      real PostgreSQL (Testcontainers or the compose service)
//   openapiContractTest  controller responses vs docs/api/openapi.yaml
//   recommendationTest   Spring DTO parity with the apps/ai internal contract (REC corpus runs in apps/ai)
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation(libs.archunit)
            }
            targets.all {
                testTask.configure {
                    // Declared as an INPUT as well as a property: see the openapiContractTest block
                    // below for what a path handed over only as a property costs.
                    inputs.dir(layout.projectDirectory.dir("../ai/tests/recommendation/fixtures"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("aiOrderParityFixtures")
                    // Order parity fixtures are owned by apps/ai and read by both languages.
                    systemProperty(
                        "nullnull.ai.fixtures.path",
                        providers.gradleProperty("nullnull.ai.fixtures.path")
                            .orElse(layout.projectDirectory.dir("../ai/tests/recommendation/fixtures").asFile.absolutePath)
                            .get()
                    )
                }
            }
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                // Bean Validation constraints on the test-only @Validated service that HttpPolicyIT
                // uses to raise a real ConstraintViolationException; implementation(project()) does not
                // expose the application's own implementation dependencies.
                implementation("org.springframework.boot:spring-boot-starter-validation")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
                implementation("org.springframework.boot:spring-boot-starter-flyway-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation("tools.jackson.core:jackson-databind")
                runtimeOnly("org.postgresql:postgresql")
            }
            targets.all {
                testTask.configure { shouldRunAfter(test) }
            }
        }

        register<JvmTestSuite>("openapiContractTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
                implementation("org.springframework.boot:spring-boot-starter-flyway-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation("org.yaml:snakeyaml")
                implementation("tools.jackson.core:jackson-databind")
                // JvmTestSuite's implementation(project()) deliberately does not expose the
                // application's implementation dependencies. JsonSchemaCheck owns this direct
                // OpenAPI contract dependency, while ProviderResponseValidator owns the main one.
                implementation(libs.json.schema.validator)
                runtimeOnly("org.postgresql:postgresql")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    // Declared as INPUTS, not only as system properties. A path handed over as a
                    // property is invisible to the up-to-date check, so editing the contract and
                    // re-running this suite reported the previous run's result - a stale PASS,
                    // exactly where a contract test is supposed to be the thing that notices.
                    // Measured: a mutation that pointed listFeed at the wrong 503 response stayed
                    // green until --rerun-tasks. CI is a fresh checkout and always runs, which is
                    // why this hid in the local loop rather than in the gate.
                    inputs.file(layout.projectDirectory.file("../../docs/api/openapi.yaml"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("openapiContract")
                    inputs.dir(layout.projectDirectory.dir("../../packages/contracts/fixtures"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("contractFixtures")
                    inputs.file(layout.projectDirectory.file("../../docs/contracts/events.schema.json"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("eventSchema")
                    systemProperty(
                        "nullnull.openapi.path",
                        providers.gradleProperty("nullnull.openapi.path")
                            .orElse(layout.projectDirectory.file("../../docs/api/openapi.yaml").asFile.absolutePath)
                            .get()
                    )
                    // The Problem fixtures FE mocks against are the same artifact as the contract:
                    // ProblemFixtureContractTest pins them to ProblemCode so a fixture cannot claim a
                    // status or a retryable the server does not emit.
                    systemProperty(
                        "nullnull.fixtures.path",
                        providers.gradleProperty("nullnull.fixtures.path")
                            .orElse(layout.projectDirectory.dir("../../packages/contracts/fixtures").asFile.absolutePath)
                            .get()
                    )
                }
            }
        }

        // The recommendation algorithms and their REC safety corpus live in apps/ai (Python). This suite
        // guards the Spring side of that boundary: DTO parity with the frozen internal contract.
        register<JvmTestSuite>("recommendationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("tools.jackson.core:jackson-databind")
                implementation("org.yaml:snakeyaml")
            }
            targets.all {
                testTask.configure {
                    description = "Checks parity between Spring DTOs and the apps/ai internal contract"
                    shouldRunAfter(test)
                    // This suite is the ADR-0006 boundary guard - the only thing that catches a
                    // Spring DTO drifting from the apps/ai internal contract, and the policy pin
                    // that keeps both sides on the same numbers. Handing those paths over as
                    // properties alone made it the WORST case of the stale-pass defect: the
                    // documented procedure is to regenerate the contract JSON with
                    // `uv run python -m nullnull_ai.contracts export` and then check parity, and
                    // the check step reported the previous run. Measured: changing a pinned policy
                    // number left this suite green until --rerun-tasks.
                    inputs.file(layout.projectDirectory.file("../ai/contracts/recommendation-internal-v1.json"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("aiInternalContract")
                    inputs.file(layout.projectDirectory.file("../ai/src/nullnull_ai/policy/policy-v1.yaml"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("aiPolicy")
                    inputs.file(layout.projectDirectory.file("../ai/tests/recommendation/manifest.json"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("aiRecommendationManifest")
                    systemProperty(
                        "nullnull.ai.contract.path",
                        providers.gradleProperty("nullnull.ai.contract.path")
                            .orElse(layout.projectDirectory.file("../ai/contracts/recommendation-internal-v1.json").asFile.absolutePath)
                            .get()
                    )
                    // The policy numbers Spring pins are read back from the file apps/ai hashes.
                    systemProperty(
                        "nullnull.ai.policy.path",
                        providers.gradleProperty("nullnull.ai.policy.path")
                            .orElse(layout.projectDirectory.file("../ai/src/nullnull_ai/policy/policy-v1.yaml").asFile.absolutePath)
                            .get()
                    )
                    // DX-004: the manifest's gradle:* rows name files under apps/api, which the apps/ai
                    // container cannot see. This suite resolves that half; apps/api/Dockerfile copies
                    // the manifest to the same place relative to apps/api that the checkout has.
                    systemProperty(
                        "nullnull.ai.manifest.path",
                        providers.gradleProperty("nullnull.ai.manifest.path")
                            .orElse(layout.projectDirectory.file("../ai/tests/recommendation/manifest.json").asFile.absolutePath)
                            .get()
                    )
                }
            }
        }
    }
}

// Resolves every test runtime classpath so the Docker test stage can run with --offline inside the
// internal-only integration network (scripts/integration-test.sh, egress-denied).
tasks.register("resolveTestClasspaths") {
    description = "Downloads all test suite runtime dependencies"
    group = "verification"
    doLast {
        // compile classpaths carry compileOnly-style artifacts (e.g. apiguardian) that runtime resolution skips
        listOf(
            "testCompileClasspath", "testRuntimeClasspath",
            "testFixturesCompileClasspath", "testFixturesRuntimeClasspath",
            "integrationTestCompileClasspath", "integrationTestRuntimeClasspath",
            "openapiContractTestCompileClasspath", "openapiContractTestRuntimeClasspath",
            "recommendationTestCompileClasspath", "recommendationTestRuntimeClasspath"
        ).forEach { configurations.getByName(it).resolve() }
    }
}

tasks.register<JavaExec>("ktoSmoke") {
    group = "verification"
    description = "Runs one explicitly approved KTO detailCommon2 smoke request and prints redacted evidence only"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoSmokeMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoForecastSmoke") {
    group = "verification"
    description = "Runs one approved KTO forecast call from a verified canonical KTO mapping and prints redacted evidence only"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoForecastSmokeMain")
    workingDir = projectDir
}

tasks.named<Test>("integrationTest") {
    useJUnitPlatform {
        excludeTags("actual-kto")
    }
}

tasks.register<Test>("actualKtoSmoke") {
    group = "verification"
    description = "Runs the opt-in actual KTO smoke against Testcontainers PostgreSQL with redacted audit assertions"
    dependsOn(tasks.named("integrationTestClasses"))
    val integrationTestSourceSet = sourceSets.named("integrationTest")
    testClassesDirs = integrationTestSourceSet.map { it.output.classesDirs }.get()
    classpath = integrationTestSourceSet.map { it.runtimeClasspath }.get()
    useJUnitPlatform {
        includeTags("actual-kto")
    }
}

tasks.withType<Test>().configureEach {
    // Compose shares one PostgreSQL across test contexts. Keeping every context's Hikari pool
    // cached exhausted that server (SQLSTATE 53300). Retain only the current context; Spring
    // closes the evicted context and its pool. This does not change the application's pool budget.
    systemProperty("spring.test.context.cache.maxSize", "1")
    // The job worker is off in every suite by default: a running poll loop would race the test that
    // seeds a job and claim it before the assertion. The worker's own tests turn it back on with
    // @SpringBootTest(properties = "nullnull.jobs.enabled=true"), which outranks a system property.
    systemProperty("nullnull.jobs.enabled", "false")
    // Failures are never ignored, and an empty suite is a configuration error, not a pass.
    ignoreFailures = false
    failOnNoDiscoveredTests = true
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("user.timezone", "UTC")
}

tasks.named("check") {
    dependsOn(
        testing.suites.named("integrationTest"),
        testing.suites.named("openapiContractTest"),
        testing.suites.named("recommendationTest")
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "nullnull-api.jar"
}
