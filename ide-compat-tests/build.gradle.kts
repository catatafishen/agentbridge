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
            "RD" -> rider(providers.gradleProperty("riderPlatformVersion").get())
            else -> intellijIdeaUltimate(providers.gradleProperty("intellijPlatformVersion").get())
        }
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("Git4Idea")
        if (testPlatformType == "IU") {
            bundledPlugin("com.intellij.java")
        }
        if (testPlatformType == "CL") {
            // Load the classic C++ engine JAR so Language.findLanguageByID("ObjectiveC") returns
            // non-null. This allows createInMemoryPsiFile() to create C++ PSI via PsiFileFactory
            // without relying on FileTypeManager extension registration (which does not fire in the
            // headless test JVM even with bundledPlugin). File-type extension registration (mapping
            // *.cpp to CppLanguage) is NOT needed for this: PsiFileFactory.createFileFromText()
            // takes the Language object directly.
            bundledPlugin("com.intellij.cidr.lang")
        }
    }
    testImplementation(project(":plugin-core"))
    testImplementation("junit:junit:${providers.gradleProperty("junit4Version").get()}")
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    // 5.13.4 matches the junit-platform-commons version bundled by IntelliJ 2026.1.
    // IntelliJ's bundled Jupiter engine calls isNestedClassPresent(Class, Predicate, CycleErrorHandling)
    // which was added in 1.13.4; versions below that cause NoSuchMethodError at test discovery.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
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
