package com.github.catatafishen.agentbridge.compat;

import com.intellij.psi.PsiFile;

/**
 * Guards against false-pass regressions caused by missing bundledPlugin declarations.
 *
 * <p>When a language guard ({@code createInMemoryPsiFile() == null → return}) fires on an IDE
 * where that language <em>should</em> be available, the guarded test passes silently as a no-op
 * rather than failing. This test converts that silent pass into a loud failure, making it
 * impossible to accidentally remove a {@code bundledPlugin} without CI noticing.</p>
 *
 * <p>Each {@code PLATFORM_TYPE} case asserts the language(s) that are <em>required</em> for
 * the tests that run on that IDE. Rider has no assertion — ReSharper is not running in headless
 * CI, so C# PSI is intentionally absent.</p>
 */
public class PlatformSanityTest extends IdeCompatBaseTest {

    public void testExpectedLanguagesAvailable() {
        switch (PLATFORM_TYPE) {
            case "IU" -> {
                PsiFile probe = createInMemoryPsiFile("Probe.java", LANGUAGE_JAVA, "class Probe {}");
                assertNotNull(
                    "Java language (\"" + LANGUAGE_JAVA + "\") must be available on IU — " +
                        "check bundledPlugin(\"com.intellij.java\") in ide-compat-tests/build.gradle.kts",
                    probe
                );
            }
            case "CL" -> {
                PsiFile probe = createInMemoryPsiFile("probe.cpp", LANGUAGE_CPP, "class Probe {};");
                assertNotNull(
                    "C++ language (\"" + LANGUAGE_CPP + "\") must be available on CL — " +
                        "check bundledPlugin(\"com.intellij.cidr.lang\") in ide-compat-tests/build.gradle.kts",
                    probe
                );
            }
            default -> {
                // RD and any future IDE: no headless PSI language currently expected
            }
        }
    }
}
