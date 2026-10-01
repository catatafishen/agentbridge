package com.github.catatafishen.agentbridge.client.koog

/**
 * The one-time setup a user has to do before "Sign in with GitHub" can work when the build bundles no OAuth
 * app. Kept in one place so the settings page, the sign-in dialog and the start error all say the same
 * thing.
 */
object CopilotSetupGuide {

    /** Where the user registers the OAuth app. */
    const val NEW_APP_URL = "https://github.com/settings/applications/new"

    /** Where the agent's settings live, for use in messages. */
    const val SETTINGS_PATH = "Settings → Tools → AgentBridge → Agents → Built-in Agent (Koog)"

    // Required by GitHub's form but never used by the device flow, so any value works.
    private const val EXAMPLE_NAME = "AgentBridge"
    private const val EXAMPLE_HOMEPAGE = "https://github.com/catatafishen/agentbridge"
    private const val EXAMPLE_CALLBACK = "http://localhost"

    /** The numbered steps, without numbers, in the order the user does them. */
    @JvmStatic
    fun steps(): List<String> = listOf(
        "Open $NEW_APP_URL (the button opens it for you). You need to be signed in to GitHub.",
        "Fill in the form. GitHub requires all three fields, but the sign-in never uses them, so any values work: " +
            "Application name \"$EXAMPLE_NAME\", Homepage URL \"$EXAMPLE_HOMEPAGE\", " +
            "Authorization callback URL \"$EXAMPLE_CALLBACK\".",
        "Tick \"Enable Device Flow\" (on the form, or on the app's page after you register it), then click " +
            "\"Register application\".",
        "Copy the Client ID shown on the app's page. It is public. You do not need to generate a client secret " +
            "and must not paste one.",
        "Paste the Client ID into \"OAuth client id\" in $SETTINGS_PATH, then press \"Sign in with GitHub…\".",
    )

    /** [steps] as plain numbered lines, for dialogs and messages. */
    @JvmStatic
    fun stepsAsText(): String = steps().mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")

    /** [steps] as numbered HTML lines, for the settings page. */
    @JvmStatic
    fun stepsAsHtml(): String =
        "<html>" + steps().mapIndexed { i, step -> "${i + 1}. ${escape(step)}" }.joinToString("<br>") + "</html>"

    /** Answers the "what do I do now" question in one sentence, for places too small for the full steps. */
    @JvmStatic
    fun shortHint(): String =
        "Register a free GitHub OAuth app once and paste its Client ID into $SETTINGS_PATH. " +
            "Or use an OpenAI-compatible API key instead."

    private val SECRET_SHAPE = Regex("^[0-9a-fA-F]{40}$")

    /**
     * Why [input] cannot be a client id, or null when it looks plausible. Blank is allowed (it means "use the
     * bundled one"). The common slip is copying the 40-character client secret instead of the id.
     */
    @JvmStatic
    fun clientIdProblem(input: String?): String? {
        val text = input?.trim().orEmpty()
        return when {
            text.isEmpty() -> null
            text.any { it.isWhitespace() } -> "A Client ID has no spaces. Copy only the value, not the label."
            SECRET_SHAPE.matches(text) ->
                "This looks like a client secret (40 hex characters), not a Client ID. " +
                    "Use the \"Client ID\" at the top of the app's page, and keep the secret private."

            text.length < 10 -> "A Client ID is longer than this, for example 20 characters. Check that you copied all of it."
            else -> null
        }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
