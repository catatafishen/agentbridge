package com.github.catatafishen.agentbridge.ui;

import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;

/**
 * Version-compatibility wrappers for JetBrains JCEF ({@code com.intellij.ui.jcef}) APIs.
 *
 * <p><b>Why this is separate from {@link com.github.catatafishen.agentbridge.psi.PlatformApiCompat}:</b>
 * JCEF ships in a separately class-loaded bundled plugin ({@code com.intellij.modules.jcef}) as of
 * IDE build 262. Any class that references {@code com.intellij.ui.jcef.*} or {@code org.cef.*} in a
 * method signature forces the JVM verifier to load those types when the class itself is loaded. If
 * such references lived in {@code PlatformApiCompat} — which is touched on the startup path — the
 * whole plugin would fail to load with {@code NoClassDefFoundError} in environments where JCEF is
 * absent (alternative JDK, thin client, remote-dev backend).
 *
 * <p>Keeping all JCEF-typed helpers here means this class is only loaded once JCEF UI is actually
 * used (guarded by {@code JBCefApp.isSupported()}), so the headless code paths stay JCEF-free.
 */
public final class JcefCompat {

    private JcefCompat() {
    }

    /**
     * Creates a {@link JBCefJSQuery} for the given JCEF browser.
     *
     * <p><b>Why extracted:</b> {@code JBCefJSQuery.create(JBCefBrowser)} is scheduled for
     * removal in favour of {@code create(JBCefBrowserBase)}. {@code JBCefBrowser} extends
     * {@code JBCefBrowserBase} in all supported IDE SDK versions (verified in 2024.3–2026.2),
     * so passing a {@code JBCefBrowserBase} reference here is safe. This wrapper keeps the
     * call site clean and confines any future API change to one place.
     */
    public static @NotNull JBCefJSQuery createJSQuery(@NotNull JBCefBrowserBase browser) {
        return JBCefJSQuery.create(browser);
    }
}
