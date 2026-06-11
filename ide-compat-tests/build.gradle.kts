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
    useJUnitPlatform()
}
