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
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.1")
}

configurations.all {
    // IntelliJ Platform bundles junit-platform-commons and junit-platform-engine at the
    // version matching its bundled junit-jupiter-engine. Maven-resolved versions of these
    // JARs load before IntelliJ's sandbox JARs on the classpath, causing NoSuchMethodError
    // when Jupiter engine calls APIs added after the Maven-resolved version.
    // Exclude them from Maven to let IntelliJ's bundled versions be the sole source.
    exclude(group = "org.junit.platform", module = "junit-platform-commons")
    exclude(group = "org.junit.platform", module = "junit-platform-engine")
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
