import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

val testPlatformType: String = providers.gradleProperty("testPlatformType").getOrElse("IU")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        when (testPlatformType) {
            "CL" -> clion(providers.gradleProperty("clionPlatformVersion").get())
            else -> intellijIdeaUltimate(providers.gradleProperty("intellijPlatformVersion").get())
        }
        testFramework(TestFrameworkType.Platform)
        if (testPlatformType == "IU") {
            bundledPlugin("com.intellij.java")
        }
    }
    testImplementation(project(":plugin-core"))
    testImplementation("junit:junit:${providers.gradleProperty("junit4Version").get()}")
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${providers.gradleProperty("junitVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.catatafishen.agentbridge.ide-compat-tests"
        name = "AgentBridge IDE Compat Tests"
        version = "0.0.1"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    systemProperty("testPlatformType", testPlatformType)
    // Exclude the Jupiter engine: our tests are JUnit 3/4 (BasePlatformTestCase).
    // IntelliJ Platform 2026.1 bundles junit-jupiter-engine at a version that requires
    // junit-platform-engine APIs newer than what junit-vintage-engine's transitive chain provides,
    // causing NoSuchMethodError at discovery time.
    useJUnitPlatform {
        excludeEngines("junit-jupiter")
    }
}
