plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.intellij.platform") version "2.14.0" apply false
    idea
}

idea {
    module {
        excludeDirs.addAll(
            listOf(
                file(".sandbox-config"),
                file(".intellijPlatform"),
            )
        )
    }
}

val baseVersion = "0.0.0"
val buildTimestamp = providers.exec {
    commandLine("date", "+%Y%m%d-%H%M")
}.standardOutput.asText.get().trim()
val ciVersion = providers.environmentVariable("PLUGIN_VERSION").orNull
val gitHash: String = try {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
} catch (_: Exception) {
    "unknown"
}

allprojects {
    group = "com.github.catatafishen.agentbridge"
    version = ciVersion
        ?: if (providers.gradleProperty("release").isPresent) baseVersion else "$baseVersion-dev-$buildTimestamp-$gitHash"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
