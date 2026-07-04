plugins {
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.epages.restdocs-api-spec") version "0.20.1"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.embabel.com/artifactory/libs-release") }
    maven { url = uri("https://repo.embabel.com/artifactory/libs-snapshot") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Spring Boot 4 / Spring AI 2.0 line
    val embabelVersion = "2.0.0-SNAPSHOT"
    implementation("com.embabel.agent:embabel-agent-starter:$embabelVersion")
    implementation("com.embabel.agent:embabel-agent-openai-custom-autoconfigure:$embabelVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("com.embabel.agent:embabel-agent-test:$embabelVersion")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.springframework.restdocs:spring-restdocs-webtestclient")
    testImplementation("com.epages:restdocs-api-spec:0.20.1")
    testImplementation("com.epages:restdocs-api-spec-webtestclient:0.20.1")
}

openapi3 {
    setServer("http://localhost:8080")
    title = "Embabel Test API"
    description = "Research summarization endpoints powered by Embabel agents"
    version = project.version.toString()
    format = "yaml"
    outputDirectory = "build/api-spec"
}

val copyOpenApiSpec = tasks.register<Copy>("copyOpenApiSpec") {
    dependsOn("openapi3")
    from(layout.buildDirectory.file("api-spec/openapi3.yaml"))
    into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("bootRun") { dependsOn(copyOpenApiSpec) }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { dependsOn(copyOpenApiSpec) }
tasks.named("resolveMainClassName") { mustRunAfter(copyOpenApiSpec) }
tasks.named("resolveTestMainClassName") { mustRunAfter(copyOpenApiSpec) }

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
