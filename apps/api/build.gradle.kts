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
    implementation(libs.aws.s3)
    runtimeOnly("org.postgresql:postgresql")

    // Shared test support (Testcontainers wiring) for every suite that needs PostgreSQL.
    testFixturesImplementation("org.springframework.boot:spring-boot-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testFixturesImplementation("org.testcontainers:testcontainers-postgresql")
    testFixturesImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testFixturesImplementation("org.springframework:spring-test")
    // OpenApiDocument and JsonSchemaCheck read docs/api/openapi.yaml and evaluate a response against it.
    // They live here so openapiContractTest and integrationTest use the same reader and evaluator.
    testFixturesImplementation("org.yaml:snakeyaml")
    testFixturesImplementation("tools.jackson.core:jackson-databind")
    testFixturesImplementation(libs.json.schema.validator)
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
                    // CrowdMetricLabelTest reads the catalog by path; measured without this, an edit to
                    // §3 left the suite UP-TO-DATE on the previous PASS. EventBatchValidatorTest needs no
                    // entry: it reads the copy processResources packages, and measured, that re-runs it.
                    inputs.file(layout.projectDirectory.file("../../docs/data/SOURCE_CATALOG.md"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("sourceCatalog")
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
                // ContractResponse validates the responses the fixture ITs compare with JsonSchemaCheck,
                // whose API returns this library's types.
                implementation(libs.json.schema.validator)
                runtimeOnly("org.postgresql:postgresql")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    // Several ITs read the contract by path, and the fixture ITs read packages/contracts/
                    // fixtures (JsonShape.fixture). Measured without these, an edit to either left the
                    // suite UP-TO-DATE on the previous PASS (CandidateSourceVocabularyIT, CandidateIT) -
                    // and while only fixtures/candidates was declared, removing a key from
                    // crowd/series-stale.json still reported CrowdForecastApiIT UP-TO-DATE and green (#16).
                    inputs.file(layout.projectDirectory.file("../../docs/api/openapi.yaml"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("openapiContract")
                    inputs.dir(layout.projectDirectory.dir("../../packages/contracts/fixtures"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("contractFixtures")
                    // ContractResponse reads the contract through OpenApiDocument, which takes its path
                    // from here, as openapiContractTest's does.
                    systemProperty(
                        "nullnull.openapi.path",
                        providers.gradleProperty("nullnull.openapi.path")
                            .orElse(layout.projectDirectory.file("../../docs/api/openapi.yaml").asFile.absolutePath)
                            .get()
                    )
                    // FeedOrderFixtureParityIT reads the same apps/ai fixture the `test` suite does,
                    // and runs it through the PRODUCTION query instead of the fallback comparator.
                    // Declared as an input as well as a property for the reason the block above
                    // records: a path handed over only as a property leaves the suite UP-TO-DATE on
                    // the previous PASS when the fixture changes.
                    inputs.dir(layout.projectDirectory.dir("../ai/tests/recommendation/fixtures"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("aiOrderParityFixtures")
                    systemProperty(
                        "nullnull.ai.fixtures.path",
                        providers.gradleProperty("nullnull.ai.fixtures.path")
                            .orElse(layout.projectDirectory.dir("../ai/tests/recommendation/fixtures").asFile.absolutePath)
                            .get()
                    )
                }
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
                // application's implementation dependencies. JsonSchemaCheck (testFixtures) owns this
                // direct OpenAPI contract dependency, while ProviderResponseValidator owns the main one.
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
                    // ComparisonReasonCodeVocabularyTest reads §9 of the catalog as the definition the
                    // server's constants must equal; an edit there has to invalidate this suite too.
                    inputs.file(layout.projectDirectory.file("../../docs/data/SOURCE_CATALOG.md"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("sourceCatalog")
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

tasks.register<JavaExec>("ktoIntroProbe") {
    group = "verification"
    description = "Looks once at KTO detailIntro2 and prints a field-shape report only (A-027; no database, no snapshot)"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoIntroProbeMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoEngLinkImport") {
    group = "verification"
    description = "Imports an owner-reviewed English link plan and prints the plan hash and place ids only (BA-086)"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoEngLinkImportMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoEngTextRefresh") {
    group = "verification"
    description = "Refreshes the English text of every linked place once and prints outcomes only (BA-086)"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoEngTextRefreshMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoEngServiceProbe") {
    group = "verification"
    description = "Looks once at KTO EngService2 detailCommon2 for one Korean content id and prints a field-shape report only (BA-086)"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoEngServiceProbeMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoEngServiceMatchProbe") {
    group = "verification"
    description = "Looks once per place (max 5) at KTO EngService2 locationBasedList2 and prints candidate distances and codes only (BA-086)"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoEngServiceMatchProbeMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoCanonicalIngest") {
    group = "verification"
    description = "Maps one already-stored KTO snapshot into the canonical catalog and prints the place ID"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoCanonicalIngestMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("deriveRelations") {
    group = "verification"
    description = "Re-derives the internal rule's SIMILAR place relations (BA-026); no external calls"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.curation.CatalogRelationDeriveMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("curateHours") {
    group = "verification"
    description = "Records the curated opening hours named in NULLNULL_HOURS_PLAN (A-031/A-032); no external calls"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.curation.CuratedHoursImportMain")
    environment("NULLNULL_HOURS_PLAN", providers.environmentVariable("NULLNULL_HOURS_PLAN").getOrElse(""))
    workingDir = projectDir
}

tasks.register<JavaExec>("curatePosts") {
    group = "verification"
    description = "Publishes the curated feed posts named in NULLNULL_CURATION_PLAN (A-031); no external calls"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.social.infrastructure.curation.CuratedPostImportMain")
    environment("NULLNULL_CURATION_PLAN", providers.environmentVariable("NULLNULL_CURATION_PLAN").getOrElse(""))
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoCallInventory") {
    group = "verification"
    description = "Lists the KTO operations one release actually used, from the call-audit (CMP-KTO-006); reads the call-audit only, though starting the application re-applies retained deletions"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.crowd.infrastructure.audit.KtoCallInventoryMain")
    environment("NULLNULL_INVENTORY_RELEASE", providers.environmentVariable("NULLNULL_INVENTORY_RELEASE").getOrElse(""))
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoDemoDetailRefresh") {
    group = "verification"
    description = "Renews the approved KTO detail snapshots of the demo places (NULLNULL_DEMO_PLACES) that lapse within two days"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoDemoDetailRefreshMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("ktoDemoForecastRefresh") {
    group = "verification"
    description = "Renews the approved KTO forecasts of the demo places (NULLNULL_DEMO_PLACES) that lapse within twelve hours"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.catalog.infrastructure.kto.KtoDemoForecastRefreshMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("seoulLiveCollect") {
    group = "verification"
    description = "Collects one Seoul live area named by NULLNULL_SEOUL_AREA_NAME"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.live.infrastructure.SeoulLiveCollectMain")
    workingDir = projectDir
}

tasks.register<JavaExec>("curateLiveMaps") {
    group = "verification"
    description = "Imports a reviewed Live area mapping plan; no provider call"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.nullnull.live.infrastructure.curation.LiveMappingImportMain")
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
