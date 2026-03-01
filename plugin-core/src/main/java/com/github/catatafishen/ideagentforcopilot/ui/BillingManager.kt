package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.Path2D
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Manages all billing/usage display logic for the Copilot tool window.
 */
internal class BillingManager {

    // Public labels — the main class embeds these in toolbar
    val usageLabel: JBLabel = JBLabel("")
    val costLabel: JBLabel = JBLabel("")

    // Public state — accessed from main class for session management
    var billingCycleStartUsed = -1
    var lastBillingUsed = 0

    // Animation state for usage indicator
    private var previousUsedCount = -1
    private var usageAnimationTimer: javax.swing.Timer? = null

    // Post-turn polling: re-fetch billing every 60s until usage changes
    @Volatile
    private var billingPollFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val pollExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-billing-poll").apply { isDaemon = true }
    }

    // Usage display toggle and graph
    private enum class UsageDisplayMode { MONTHLY, SESSION }

    private var usageDisplayMode = UsageDisplayMode.MONTHLY
    lateinit var usageGraphPanel: UsageGraphPanel
        private set
    private var lastBillingEntitlement = 0
    private var lastBillingUnlimited = false
    private var lastBillingRemaining = 0
    private var lastBillingOveragePermitted = false
    private var lastBillingResetDate = ""

    private companion object {
        private const val OS_NAME_PROPERTY = "os.name"
        private val ERROR_COLOR: JBColor
            get() = UIManager.getColor("Label.errorForeground") as? JBColor
                ?: JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x60, 0x60))

        private fun errorHex(): String {
            val c = ERROR_COLOR
            return "#%02X%02X%02X".format(c.red, c.green, c.blue)
        }
    }

    fun loadBillingData(startPolling: Boolean = false) {
        if (startPolling) startPostTurnPolling()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val ghCli = findGhCli() ?: run {
                    updateUsageUi(
                        "Usage info unavailable (gh CLI not found)",
                        "Install GitHub CLI: https://cli.github.com  then run 'gh auth login'"
                    )
                    return@executeOnPooledThread
                }

                if (!isGhAuthenticated(ghCli)) {
                    updateUsageUi(
                        "Usage info unavailable (not authenticated)",
                        "Run 'gh auth login' in a terminal to authenticate with GitHub"
                    )
                    return@executeOnPooledThread
                }

                val obj = fetchCopilotUserData(ghCli) ?: return@executeOnPooledThread
                val snapshots = obj.getAsJsonObject("quota_snapshots") ?: return@executeOnPooledThread
                val premium = snapshots.getAsJsonObject("premium_interactions") ?: return@executeOnPooledThread

                displayBillingQuota(premium, obj)
            } catch (e: Exception) {
                updateUsageUi(
                    "Usage info unavailable",
                    "Error: ${e.message}. Ensure 'gh auth login' has been run."
                )
            }
        }
    }

    /**
     * Polls billing API every 60s until the usage value changes from [lastBillingUsed].
     * Cancels any previous poller first.
     */
    private fun startPostTurnPolling() {
        billingPollFuture?.cancel(false)
        val usageAtTurnEnd = lastBillingUsed
        billingPollFuture = pollExecutor.scheduleAtFixedRate({
            try {
                val ghCli = findGhCli() ?: return@scheduleAtFixedRate
                if (!isGhAuthenticated(ghCli)) return@scheduleAtFixedRate
                val obj = fetchCopilotUserData(ghCli) ?: return@scheduleAtFixedRate
                val snapshots = obj.getAsJsonObject("quota_snapshots") ?: return@scheduleAtFixedRate
                val premium = snapshots.getAsJsonObject("premium_interactions") ?: return@scheduleAtFixedRate

                displayBillingQuota(premium, obj)

                val entitlement = premium["entitlement"]?.asInt ?: 0
                val remaining = premium["remaining"]?.asInt ?: 0
                val currentUsed = entitlement - remaining
                if (currentUsed != usageAtTurnEnd) {
                    billingPollFuture?.cancel(false)
                }
            } catch (_: Exception) {
                // Silently retry on next poll
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun updateUsageUi(text: String, tooltip: String, cost: String = "") {
        SwingUtilities.invokeLater {
            usageLabel.text = text
            usageLabel.toolTipText = tooltip
            costLabel.text = cost
        }
    }

    internal fun isGhAuthenticated(ghCli: String): Boolean {
        val process = ProcessBuilder(ghCli, "auth", "status").redirectErrorStream(true).start()
        val authOutput = process.inputStream.bufferedReader().readText()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        return process.exitValue() == 0 && "not logged in" !in authOutput.lowercase() && "gh auth login" !in authOutput
    }

    private fun fetchCopilotUserData(ghCli: String): com.google.gson.JsonObject? {
        val apiProcess = ProcessBuilder(ghCli, "api", "/copilot_internal/user").redirectErrorStream(true).start()
        val json = apiProcess.inputStream.bufferedReader().readText()
        apiProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        return com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
    }

    private fun displayBillingQuota(premium: com.google.gson.JsonObject, obj: com.google.gson.JsonObject) {
        val entitlement = premium["entitlement"]?.asInt ?: 0
        val remaining = premium["remaining"]?.asInt ?: 0
        val unlimited = premium["unlimited"]?.asBoolean ?: false
        val overagePermitted = premium["overage_permitted"]?.asBoolean ?: false
        val resetDate = obj["quota_reset_date"]?.asString ?: ""
        val used = entitlement - remaining
        val shouldAnimate = previousUsedCount >= 0 && used > previousUsedCount
        previousUsedCount = used

        // Track session start baseline
        if (billingCycleStartUsed < 0) billingCycleStartUsed = used

        // Store latest billing data for toggle
        lastBillingUsed = used
        lastBillingEntitlement = entitlement
        lastBillingUnlimited = unlimited
        lastBillingRemaining = remaining
        lastBillingOveragePermitted = overagePermitted
        lastBillingResetDate = resetDate

        SwingUtilities.invokeLater {
            refreshUsageDisplay()
            updateUsageGraph(used, entitlement, unlimited, resetDate)
            if (shouldAnimate) animateUsageChange()
        }
    }

    /** Refreshes usage label and cost label based on current display mode. */
    fun refreshUsageDisplay() {
        when (usageDisplayMode) {
            UsageDisplayMode.MONTHLY -> {
                if (lastBillingUnlimited) {
                    usageLabel.text = "Unlimited"
                    usageLabel.toolTipText = "Click to show session usage"
                    costLabel.text = ""
                } else {
                    usageLabel.text = "$lastBillingUsed / $lastBillingEntitlement"
                    usageLabel.toolTipText = "Premium requests this cycle \u2022 Click to show session usage"
                    updateCostLabel(lastBillingRemaining, lastBillingOveragePermitted)
                }
            }

            UsageDisplayMode.SESSION -> {
                val sessionUsed = lastBillingUsed - billingCycleStartUsed.coerceAtLeast(0)
                usageLabel.text = "$sessionUsed session"
                usageLabel.toolTipText = "Premium requests this session \u2022 Click to show monthly usage"
                costLabel.text = ""
            }
        }
    }

    /** Updates the mini usage graph with current billing cycle data. */
    private fun updateUsageGraph(
        used: Int,
        entitlement: Int,
        unlimited: Boolean,
        resetDate: String
    ) {
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
        used: Int,
        entitlement: Int,
        currentDay: Int,
        totalDays: Int,
        resetDate: LocalDate,
    ): String {
        val rate = if (currentDay > 0) used.toFloat() / currentDay else 0f
        val projected = (rate * totalDays).toInt()
        val overage = (used - entitlement).coerceAtLeast(0)
        val projectedOverage = (projected - entitlement).coerceAtLeast(0)
        val overageCostPerReq = 0.04
        val resetFormatted = resetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

        val sb = StringBuilder("<html>")
        sb.append("Day ${currentDay + 1} / $totalDays<br>")
        sb.append("Usage: $used / $entitlement<br>")
        if (overage > 0) {
            val cost = overage * overageCostPerReq
            sb.append(
                "<font color='${errorHex()}'>Overage: $overage reqs (\$${
                    String.format(
                        "%.2f",
                        cost
                    )
                })</font><br>"
            )
        }
        sb.append("Projected: ~$projected by cycle end<br>")
        if (projectedOverage > 0) {
            val projCost = projectedOverage * overageCostPerReq
            sb.append(
                "<font color='${errorHex()}'>Projected overage: ~$projectedOverage (\$${
                    String.format(
                        "%.2f",
                        projCost
                    )
                })</font><br>"
            )
        }
        sb.append("Resets: $resetFormatted")
        sb.append("</html>")
        return sb.toString()
    }

    private fun updateCostLabel(remaining: Int, overagePermitted: Boolean) {
        if (remaining < 0) {
            val overageCost = -remaining * 0.04
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

    private fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        val r = (from.red + (to.red - from.red) * ratio).toInt()
        val g = (from.green + (to.green - from.green) * ratio).toInt()
        val b = (from.blue + (to.blue - from.blue) * ratio).toInt()
        return Color(r, g, b)
    }

    /**
     * Finds the gh CLI executable, checking PATH and known install locations.
     */
    internal fun findGhCli(): String? {
        // Check PATH using platform-appropriate command
        try {
            val cmd = if (System.getProperty(OS_NAME_PROPERTY).lowercase().contains("win")) "where" else "which"
            val check = ProcessBuilder(cmd, "gh").start()
            if (check.waitFor() == 0) return "gh"
        } catch (_: Exception) {
            // gh CLI detection is best-effort
        }

        // Check known install locations
        val isWindows = System.getProperty(OS_NAME_PROPERTY).lowercase().contains("win")
        val knownPaths = if (isWindows) {
            listOf(
                "C:\\Program Files\\GitHub CLI\\gh.exe",
                "C:\\Program Files (x86)\\GitHub CLI\\gh.exe",
                "C:\\Tools\\gh\\bin\\gh.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\GitHub CLI\\gh.exe"
            )
        } else {
            listOf(
                "/usr/bin/gh",
                "/usr/local/bin/gh",
                System.getProperty("user.home") + "/.local/bin/gh",
                "/snap/bin/gh",
                "/home/linuxbrew/.linuxbrew/bin/gh"
            )
        }
        return knownPaths.firstOrNull { java.io.File(it).exists() }
    }

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

        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(false)
            .createPopup()
            .showUnderneathOf(owner)
    }

    /** Toolbar action showing the usage sparkline graph as a clickable custom component */
    inner class UsageGraphAction : AnAction("Usage Graph"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            showUsagePopup(usageGraphPanel)
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            usageGraphPanel = UsageGraphPanel()
            usageGraphPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            usageGraphPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    showUsagePopup(usageGraphPanel)
                }
            })
            return usageGraphPanel
        }
    }

    data class UsageGraphData(
        val currentDay: Int,
        val totalDays: Int,
        val usedSoFar: Int,
        val entitlement: Int
    )

    /**
     * Tiny sparkline panel showing cumulative usage over the billing cycle,
     * a linear projection to end-of-month, and a horizontal entitlement bar.
     * Shows red fill for usage above the entitlement threshold.
     */
    class UsageGraphPanel : JBPanel<UsageGraphPanel>() {
        var graphData: UsageGraphData? = null

        init {
            isOpaque = false
            val h = JBUI.scale(28)
            preferredSize = Dimension(JBUI.scale(120), h)
            minimumSize = preferredSize
            maximumSize = Dimension(JBUI.scale(120), h)
            border = JBUI.Borders.empty(1, JBUI.scale(2))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val data = graphData ?: return
            if (data.entitlement <= 0) return

            val g2 = (g as Graphics2D).also {
                it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            }
            val pad = 1
            val w = width - 2 * pad
            val h = height - 2 * pad
            if (w <= 0 || h <= 0) return

            val arc = JBUI.scale(6)

            // Border — light gray, matching ComboBoxAction style
            val borderShape = java.awt.geom.RoundRectangle2D.Float(
                0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc.toFloat(), arc.toFloat()
            )
            g2.color = UIManager.getColor("Component.borderColor") ?: JBColor(0xC4C4C4, 0x5E6060)
            g2.stroke = BasicStroke(1f)
            g2.draw(borderShape)

            val clipShape = java.awt.geom.RoundRectangle2D.Float(
                pad.toFloat(), pad.toFloat(), w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat()
            )

            // Clip all content to the rounded rect
            val oldClip = g2.clip
            g2.clip(clipShape)

            val rate = if (data.currentDay > 0) data.usedSoFar.toFloat() / data.currentDay else 0f
            val projected = (rate * data.totalDays).toInt()
            val maxY = maxOf(data.entitlement, projected, data.usedSoFar) * 1.15f
            val overQuota = data.usedSoFar > data.entitlement

            fun dx(day: Float) = pad + (day / data.totalDays * w)
            fun dy(v: Float) = pad + h - (v / maxY * h)

            // Entitlement line (dashed)
            val entY = dy(data.entitlement.toFloat())
            g2.color = if (overQuota)
                JBColor(Color(0xE0, 0x40, 0x40, 0x70), Color(0xE0, 0x60, 0x60, 0x70))
            else
                JBColor(Color(0x80, 0x80, 0x80, 0x40), Color(0xA0, 0xA0, 0xA0, 0x40))
            g2.stroke = BasicStroke(
                1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                floatArrayOf(3f, 3f), 0f
            )
            g2.drawLine(pad, entY.toInt(), pad + w, entY.toInt())

            val baseY = dy(0f)
            val curX = dx(data.currentDay.toFloat())
            val curY = dy(data.usedSoFar.toFloat())

            if (overQuota) {
                val quotaY = dy(data.entitlement.toFloat())
                // Intersection of usage line with entitlement line
                val t = (quotaY - baseY) / (curY - baseY)
                val intersectX = pad + t * (curX - pad)

                // Below-quota area (green quadrilateral)
                val belowPath = Path2D.Float().apply {
                    moveTo(pad.toFloat(), baseY)
                    lineTo(intersectX, quotaY)
                    lineTo(curX, quotaY)
                    lineTo(curX, baseY)
                    closePath()
                }
                g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x30), Color(0x6A, 0xAB, 0x73, 0x30))
                g2.fill(belowPath)

                // Over-quota area (red triangle)
                val overPath = Path2D.Float().apply {
                    moveTo(intersectX, quotaY)
                    lineTo(curX, curY)
                    lineTo(curX, quotaY)
                    closePath()
                }
                g2.color = JBColor(Color(0xE0, 0x40, 0x40, 0x40), Color(0xE0, 0x60, 0x60, 0x40))
                g2.fill(overPath)

                // Usage line (red)
                g2.color = JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
            } else {
                // Normal: green filled area
                val areaPath = Path2D.Float().apply {
                    moveTo(pad.toFloat(), baseY)
                    lineTo(curX, curY)
                    lineTo(curX, baseY)
                    closePath()
                }
                g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x40), Color(0x6A, 0xAB, 0x73, 0x40))
                g2.fill(areaPath)

                // Usage line (green)
                g2.color = JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
            }

            // Projection line (dashed gray)
            if (data.currentDay < data.totalDays) {
                val projX = dx(data.totalDays.toFloat())
                val projY = dy(projected.toFloat())
                g2.color = JBColor(Color(0x80, 0x80, 0x80, 0x80), Color(0xA0, 0xA0, 0xA0, 0x80))
                g2.stroke = BasicStroke(
                    1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    floatArrayOf(3f, 3f), 0f
                )
                g2.drawLine(curX.toInt(), curY.toInt(), projX.toInt(), projY.toInt())
            }

            // Current day dot
            val dotColor = if (overQuota)
                JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
            else
                JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
            g2.color = dotColor
            g2.fillOval(
                curX.toInt() - JBUI.scale(2), curY.toInt() - JBUI.scale(2),
                JBUI.scale(4), JBUI.scale(4)
            )

            // Restore clip
            g2.clip = oldClip
        }
    }
}
