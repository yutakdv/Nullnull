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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
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
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
                implementation("org.springframework.boot:spring-boot-starter-flyway-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-postgresql")
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
                implementation(libs.json.schema.validator)
                runtimeOnly("org.postgresql:postgresql")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    systemProperty(
                        "nullnull.openapi.path",
                        providers.gradleProperty("nullnull.openapi.path")
                            .orElse(layout.projectDirectory.file("../../docs/api/openapi.yaml").asFile.absolutePath)
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
            }
            targets.all {
                testTask.configure {
                    description = "Checks parity between Spring DTOs and the apps/ai internal contract"
                    shouldRunAfter(test)
                    systemProperty(
                        "nullnull.ai.contract.path",
                        providers.gradleProperty("nullnull.ai.contract.path")
                            .orElse(layout.projectDirectory.file("../ai/contracts/recommendation-internal-v1.json").asFile.absolutePath)
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

tasks.withType<Test>().configureEach {
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
