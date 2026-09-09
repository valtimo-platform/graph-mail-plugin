/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

dockerCompose {
    setProjectName("graph-mail-plugin")
    isRequiredBy(project.tasks.test)

    tasks.test {
        useComposeFiles.addAll("$rootDir/docker-resources/docker-compose-base-test.yml", "docker-compose-override.yml")
    }
}

val kotlinLoggingVersion: String by project
val mockitoKotlinVersion: String by project
val valtimoVersion: String by project
val operatonVersion: String by project
val jsoupVersion: String by project
val wiremockVersion: String by project

configurations.testRuntimeClasspath {
    resolutionStrategy.force("org.wiremock:wiremock:$wiremockVersion")
    exclude(group = "com.github.tomakehurst")
}

dependencies {
    compileOnly("com.ritense.valtimo:plugin-valtimo")
    compileOnly("com.ritense.valtimo:process-document")
    compileOnly("com.ritense.valtimo:contract")
    compileOnly("org.operaton.bpm:operaton-engine:$operatonVersion")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
    compileOnly("com.ritense.valtimo:temporary-resource-storage")
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    // AllowedSendersChangeGuard is an @Aspect that reads the stored configuration through
    // PluginConfigurationRepository, which extends JpaRepository. compileOnly like the rest of the
    // Spring/Valtimo dependencies here — the host application supplies these at runtime.
    //
    // The repository rather than PluginService on purpose: the aspect advises PluginService, so
    // injecting it would create a circular dependency between an advisor and its own target.
    compileOnly("org.springframework.boot:spring-boot-starter-aop")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa")
    // jsoupVersion is 1.23.1, which fixes CVE-2026-71497 (Cleaner XSS bypass via a malformed tag
    // name ending in a control character, for custom Safelists that permit raw-text elements).
    // EMAIL_HTML_SAFELIST in GraphMailPlugin.kt adds no raw-text elements, so this plugin's usage
    // was not exploitable — pinned to the patched version regardless, since this is the library the
    // HTML sanitization relies on.
    implementation("org.jsoup:jsoup:$jsoupVersion")
    // Metrics are optional: compileOnly plus @ConditionalOnClass, so the plugin still starts in an
    // application that has no Micrometer on the classpath. GZAC ships Actuator, so in practice the
    // meters are there — but the plugin must not be the reason a leaner host application fails.
    compileOnly("io.micrometer:micrometer-core")

    // Testing
    testImplementation("com.ritense.valtimo:plugin-valtimo")
    testImplementation("com.ritense.valtimo:process-document")
    testImplementation("com.ritense.valtimo:building-block")
    testImplementation("com.ritense.valtimo:local-resource")
    testImplementation("com.ritense.valtimo:test-utils-common")
    testImplementation("com.ritense.valtimo:temporary-resource-storage")
    testImplementation("org.operaton.bpm:operaton-engine:$operatonVersion")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-aop")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // ActivityInstanceIdContractTest runs a real Operaton engine in-process on H2 to verify the
    // assumption the duplicate guard is built on. No Postgres, no docker, no Spring.
    testImplementation("com.h2database:h2")
    testImplementation("io.micrometer:micrometer-core")
}

apply(from = "gradle/publishing.gradle")
