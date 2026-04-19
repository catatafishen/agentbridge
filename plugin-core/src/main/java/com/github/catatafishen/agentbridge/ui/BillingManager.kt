package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.swing.JComponent

/**
 * Orchestrates Copilot billing/usage display in the tool window toolbar.
 *
 * Delegates data fetching to [CopilotBillingClient] and graph rendering to
 * [UsageGraphPanel].  Maintains local session counters that are overlaid on
 * the last-polled API data so the UI stays responsive between API calls.
 */
class BillingManager {

    val usageLabel: JBLabel = JBLabel("").apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "Click to toggle session/monthly view"
    }
    val costLabel: JBLabel = JBLabel("")

    var billingCycleStartUsed = -1
    var lastBillingUsed = 0

    /** Local request counter — incremented on each turn completion. */
    var localSessionRequests = 0
        private set

    /** Weighted premium request count — accounts for model multipliers (e.g., 3x for Opus). */
    var localSessionPremiumRequests = 0.0
        private set

    /** Reset the local session counter (called on session reset). */
    fun resetLocalCounter() {
        localSessionRequests = 0
        localSessionPremiumRequests = 0.0
    }

    private var previousUsedCount = -1
    private var usageAnimationTimer: javax.swing.Timer? = null

    private enum class UsageDisplayMode { MONTHLY, SESSION }

    private var usageDisplayMode = UsageDisplayMode.MONTHLY
    lateinit var usageGraphPanel: UsageGraphPanel
        internal set
    private var lastBillingEntitlement = 0
    private var lastBillingUnlimited = false
    private var lastBillingRemaining = 0
    private var lastBillingOveragePermitted = false
    private var lastBillingResetDate = ""
    private var lastPolledAt: LocalTime? = null
    private val polledTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    internal val client = CopilotBillingClient()

    companion object {
        private val LOG = Logger.getInstance(BillingManager::class.java)
        private val ERROR_COLOR: Color
            get() = JBUI.CurrentTheme.Label.errorForeground()

        private fun errorHex(): String {
            val c = ERROR_COLOR
            return "#%02X%02X%02X".format(c.red, c.green, c.blue)
        }

        private const val OVERAGE_COST_PER_REQ = 0.04

        /**
         * Formats token counts and cost for display in usage chips and toolbar stats.
         * Returns an empty string if no usage data is available.
         * Examples: "1.2k tok · $0.004", "850 tok · $0.001"
         */
        fun formatUsageChip(inputTokens: Int, outputTokens: Int, costUsd: Double?): String =
            BillingCalculator.formatUsageChip(inputTokens, outputTokens, costUsd)
    }

    /**
     * Records a turn completion — increments the local request counter
     * and updates the UI immediately (no API call needed).
     * @param multiplier the model's cost multiplier string (e.g., "1x", "3x", "0.33x"),
     *   or {@code null} if unknown (falls back to 1x for internal accounting).
     */
    fun recordTurnCompleted(multiplier: String? = null) {
        localSessionRequests++
        localSessionPremiumRequests += parseMultiplier(multiplier ?: "1x")
        LOG.info("recordTurnCompleted: localSessionRequests=$localSessionRequests, premium=$localSessionPremiumRequests (mult=$multiplier)")
        val estimated = estimatedUsed()
        val shouldAnimate = previousUsedCount in 0..<estimated
        previousUsedCount = estimated
        ApplicationManager.getApplication().invokeLater {
            refreshUsageDisplay()
            updateUsageGraph(estimated, lastBillingEntitlement, lastBillingUnlimited, lastBillingResetDate)
            if (shouldAnimate) animateUsageChange()
        }
    }

    /** Estimated total used = initial API value + weighted premium requests. */
    private fun estimatedUsed(): Int = lastBillingUsed + localSessionPremiumRequests.toInt()

    /** Parses "3x" → 3.0, "0.33x" → 0.33, defaults to 1.0. */
    private fun parseMultiplier(multiplier: String): Double = BillingCalculator.parseMultiplier(multiplier)

    /** Formats premium count: shows integer if whole, one decimal otherwise. */
    private fun formatPremium(value: Double): String = BillingCalculator.formatPremium(value)

    /**
     * Fetches billing data from the GitHub API on a pooled thread and updates the UI.
     */
    fun loadBillingData() {
        LOG.info("loadBillingData called")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val snapshot = client.fetchBillingData()
                if (snapshot == null) {
                    updateUsageUi(
                        "Usage info unavailable",
                        "Ensure 'gh' CLI is installed and authenticated (gh auth login)"
                    )
                    return@executeOnPooledThread
                }
                LOG.info(
                    "Billing API response: entitlement=${snapshot.entitlement}, " +
                        "remaining=${snapshot.remaining}, unlimited=${snapshot.unlimited}, " +
                        "resetDate=${snapshot.resetDate}"
                )
                displayBillingQuota(snapshot)
            } catch (e: Exception) {
                LOG.warn("Billing data fetch failed", e)
                updateUsageUi(
                    "Usage info unavailable",
                    "Error: ${e.message}. Ensure 'gh auth login' has been run."
                )
            }
        }
    }

    private fun updateUsageUi(text: String, tooltip: String, cost: String = "") {
        ApplicationManager.getApplication().invokeLater {
            usageLabel.text = text
            usageLabel.toolTipText = tooltip
            costLabel.text = cost
        }
    }

    private fun displayBillingQuota(snapshot: BillingSnapshot) {
        lastBillingUsed = snapshot.used
        lastBillingEntitlement = snapshot.entitlement
        lastBillingUnlimited = snapshot.unlimited
        lastBillingRemaining = snapshot.remaining
        lastBillingOveragePermitted = snapshot.overagePermitted
        lastBillingResetDate = snapshot.resetDate
        lastPolledAt = LocalTime.now()

        if (billingCycleStartUsed < 0) billingCycleStartUsed = snapshot.used

        val estimated = estimatedUsed()
        val shouldAnimate = previousUsedCount in 0..<estimated
        previousUsedCount = estimated

        ApplicationManager.getApplication().invokeLater {
            refreshUsageDisplay()
            updateUsageGraph(estimated, snapshot.entitlement, snapshot.unlimited, snapshot.resetDate)
            if (shouldAnimate) animateUsageChange()
        }
    }

    /** Refreshes usage label and cost label based on current display mode. */
    fun refreshUsageDisplay() {
        val polledSuffix = lastPolledAt?.let { " \u2022 Initial ${it.format(polledTimeFormat)}" } ?: ""
        val estimated = estimatedUsed()
        when (usageDisplayMode) {
            UsageDisplayMode.MONTHLY -> {
                if (lastBillingUnlimited) {
                    usageLabel.text = "Unlimited"
                    usageLabel.toolTipText = "Click to show session usage$polledSuffix"
                    costLabel.text = ""
                } else {
                    usageLabel.text = "$estimated / $lastBillingEntitlement"
                    val premiumDisplay = if (localSessionPremiumRequests != localSessionRequests.toDouble())
                        "$localSessionRequests turns ≈ ${formatPremium(localSessionPremiumRequests)} premium"
                    else "$localSessionRequests this session"
                    usageLabel.toolTipText =
                        "Premium requests this cycle ($premiumDisplay) \u2022 Click to show session usage$polledSuffix"
                    val estimatedRemaining = lastBillingRemaining - localSessionPremiumRequests.toInt()
                    updateCostLabel(estimatedRemaining, lastBillingOveragePermitted)
                }
            }

            UsageDisplayMode.SESSION -> {
                val premiumSuffix = if (localSessionPremiumRequests != localSessionRequests.toDouble())
                    " (≈${formatPremium(localSessionPremiumRequests)} premium)" else ""
                usageLabel.text = "$localSessionRequests session$premiumSuffix"
                usageLabel.toolTipText = "Premium requests this session \u2022 Click to show monthly usage$polledSuffix"
                costLabel.text = ""
            }
        }
    }

    private fun updateUsageGraph(used: Int, entitlement: Int, unlimited: Boolean, resetDate: String) {
        if (!::usageGraphPanel.isInitialized) return
        if (unlimited || entitlement <= 0) {
            usageGraphPanel.graphData = null
            usageGraphPanel.repaint()
            return
        }

        try {
            val resetLocalDate = LocalDate.parse(resetDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val today = LocalDate.now()
            val cycleStart = resetLocalDate.minusMonths(1)
            val totalDays = ChronoUnit.DAYS.between(cycleStart, resetLocalDate).toInt().coerceAtLeast(1)
            val currentDay = ChronoUnit.DAYS.between(cycleStart, today).toInt().coerceIn(1, totalDays)

            usageGraphPanel.graphData = UsageGraphData(currentDay, totalDays, used, entitlement)
            usageGraphPanel.toolTipText =
                buildGraphTooltip(used, entitlement, currentDay, totalDays, resetLocalDate)
            usageGraphPanel.repaint()
        } catch (_: Exception) {
            usageGraphPanel.graphData = null
            usageGraphPanel.repaint()
        }
    }

    private fun buildGraphTooltip(
        used: Int, entitlement: Int, currentDay: Int, totalDays: Int, resetDate: LocalDate,
    ): String = BillingCalculator.buildGraphTooltip(used, entitlement, currentDay, totalDays, resetDate, errorHex())

    private fun updateCostLabel(remaining: Int, overagePermitted: Boolean) {
        if (remaining < 0) {
            val overageCost = -remaining * OVERAGE_COST_PER_REQ
            costLabel.text = if (overagePermitted) {
                "Est. overage: $${String.format("%.2f", overageCost)}"
            } else {
                "Quota exceeded - overages not permitted"
            }
            costLabel.foreground = ERROR_COLOR
        } else {
            costLabel.text = ""
        }
    }

    /** Briefly pulses [usageLabel] foreground from a green accent back to normal. */
    private fun animateUsageChange() {
        usageAnimationTimer?.stop()
        val normalColor = usageLabel.foreground
        val highlightColor = JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
        val totalSteps = 20
        var step = 0

        usageLabel.foreground = highlightColor
        usageAnimationTimer = javax.swing.Timer(50) {
            step++
            if (step >= totalSteps) {
                usageLabel.foreground = normalColor
                usageAnimationTimer?.stop()
            } else {
                val ratio = step.toFloat() / totalSteps
                usageLabel.foreground = interpolateColor(highlightColor, normalColor, ratio)
            }
        }
        usageAnimationTimer!!.start()
    }

    private fun interpolateColor(from: Color, to: Color, ratio: Float): Color =
        BillingCalculator.interpolateColor(from, to, ratio)

    fun showUsagePopup(owner: JComponent) {
        val data = (if (::usageGraphPanel.isInitialized) usageGraphPanel.graphData else null) ?: return

        val popupGraph = UsageGraphPanel()
        popupGraph.graphData = data
        val pw = JBUI.scale(320)
        val ph = JBUI.scale(180)
        popupGraph.preferredSize = Dimension(pw, ph)
        popupGraph.minimumSize = popupGraph.preferredSize
        popupGraph.maximumSize = popupGraph.preferredSize

        val rate = if (data.currentDay > 0) data.usedSoFar.toFloat() / data.currentDay else 0f
        val projected = (rate * data.totalDays).toInt()
        val overQuota = data.usedSoFar > data.entitlement

        val infoHtml = buildString {
            append("<html>")
            append("Used: <b>${data.usedSoFar}</b> / ${data.entitlement}")
            append(" &nbsp;\u00B7&nbsp; Day ${data.currentDay + 1} of ${data.totalDays}")
            append(" &nbsp;\u00B7&nbsp; Projected: ~$projected")
            if (overQuota) {
                val overage = data.usedSoFar - data.entitlement
                append("<br><font color='${errorHex()}'>Over quota by $overage requests</font>")
            }
            if (lastBillingResetDate.isNotEmpty()) {
                try {
                    val resetDate = LocalDate.parse(lastBillingResetDate, DateTimeFormatter.ISO_LOCAL_DATE)
                    append("<br>Resets: ${resetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}")
                } catch (_: Exception) { /* Date parse failed — skip reset date display */
                }
            }
            append("</html>")
        }

        val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel("Premium Request Usage").apply {
                font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 1)
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            add(popupGraph, BorderLayout.CENTER)
            add(JBLabel(infoHtml).apply {
                border = JBUI.Borders.emptyTop(8)
            }, BorderLayout.SOUTH)
        }

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(false)
            .createPopup()
            .showUnderneathOf(owner)
    }

    /**
     * Creates the toolbar action for the usage sparkline graph.
     *
     * The graph is only visible when the active agent profile is a GitHub Copilot
     * profile (clientCssClass == "copilot") AND the user has enabled the Copilot
     * usage display in settings.
     */
    fun createUsageGraphAction(project: com.intellij.openapi.project.Project): UsageGraphAction {
        return UsageGraphAction(
            onGraphClicked = { owner -> showUsagePopup(owner) },
            graphPanelSetter = { panel -> usageGraphPanel = panel },
            visibleWhen = {
                com.github.catatafishen.agentbridge.settings.BillingSettings.getInstance().isShowCopilotUsage
                    && com.github.catatafishen.agentbridge.services.ActiveAgentManager.getInstance(project)
                    .activeProfile.clientCssClass == "copilot"
            },
        )
    }
}
