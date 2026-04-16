plugins {
    id("java")
    application
    jacoco
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation("com.code-intelligence:jazzer-api:${providers.gradleProperty("jazzerVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.github.copilot.mcp.McpStdioProxy")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.github.copilot.mcp.McpStdioProxy"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.register("printFuzzClasspath") {
    description = "Print the test runtime classpath for standalone Jazzer fuzz runs"
    group = "verification"
    dependsOn("testClasses")
    doLast {
        println(sourceSets.test.get().runtimeClasspath.asPath)
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
