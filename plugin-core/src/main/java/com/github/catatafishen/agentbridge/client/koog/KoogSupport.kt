package com.github.catatafishen.agentbridge.client.koog

/**
 * Whether the Koog agent can run in this IDE.
 *
 * Koog 1.3.0 is compiled against Kotlin 2.3 and calls stdlib members that older runtimes lack
 * (it fails with `NoSuchMethodError` in `kotlin.time.Duration` on IDE 2025.3). The Kotlin stdlib
 * is always loaded from the platform, so the IDE decides: IDE 2026.1 and newer work.
 *
 * Deliberately free of any Koog import: this class is consulted on IDEs where Koog classes must
 * never be loaded.
 */
object KoogSupport {
    const val AGENT_ID = "koog"

    private const val MIN_MAJOR = 2
    private const val MIN_MINOR = 3

    /** The oldest Kotlin runtime the agent works on, for messages. */
    const val MIN_VERSION = "$MIN_MAJOR.$MIN_MINOR"

    @JvmStatic
    fun isSupported(): Boolean = isSupported(KotlinVersion.CURRENT)

    @JvmStatic
    fun isSupported(runtime: KotlinVersion): Boolean = runtime.isAtLeast(MIN_MAJOR, MIN_MINOR)

    @JvmStatic
    fun unsupportedReason(): String =
        "The Koog agent needs an IDE built on Kotlin $MIN_MAJOR.$MIN_MINOR or newer (IntelliJ 2026.1+). " +
            "This IDE runs Kotlin ${KotlinVersion.CURRENT}."
}
