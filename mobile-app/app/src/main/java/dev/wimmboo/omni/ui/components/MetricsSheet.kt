package dev.wimmboo.omni.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.MessageMetrics
import dev.wimmboo.omni.domain.MetricPhases
import dev.wimmboo.omni.domain.MetricsCalculator
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.JetBrainsMonoFontFamily
import dev.wimmboo.omni.ui.theme.OmniColorPalette
import dev.wimmboo.omni.ui.theme.OmniTheme
import java.util.Locale

@Composable
fun MetricsSummaryBar(
    metrics: MessageMetrics?,
    isOpen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = OmniIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(if (isOpen) 180f else 0f),
            )
            Text(
                text = "Metrics",
                color = colors.textSecondary,
                fontFamily = InterFontFamily,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.width(12.dp))

        val summaryAnnotated = buildMetricsSummaryText(metrics, colors)
        Text(
            text = summaryAnnotated,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildMetricsSummaryText(m: MessageMetrics?, colors: OmniColorPalette) = buildAnnotatedString {
    if (m == null) {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(MetricsCalculator.DASH) }
        return@buildAnnotatedString
    }
    val legacyEst = m.tokensEstimated && m.inputEstimated == null && m.outputEstimated == null
    val estOut = (m.outputEstimated == true) || legacyEst
    val parts = mutableListOf<String>()
    val genTs = m.generationTokensPerSecond
    val genTier = genTs?.let { MetricsCalculator.pickSpeedTier(it) }
    if (genTs != null) {
        parts += "${if (estOut) "~" else ""}${MetricsCalculator.formatRate(genTs)} t/s"
    }
    if (m.outputTokens != null) {
        parts += "${if (estOut) "~" else ""}${MetricsCalculator.fmtInt(m.outputTokens)} tok"
    }
    if (m.ttftMs != null) {
        parts += "${MetricsCalculator.formatMs(m.ttftMs)} ttft"
    }
    if (m.totalDurationMs != null) {
        parts += MetricsCalculator.formatMs(m.totalDurationMs)
    }
    if (parts.isEmpty()) {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(MetricsCalculator.DASH) }
        return@buildAnnotatedString
    }
    if (genTier != null) {
        val first = parts.removeAt(0)
        withStyle(SpanStyle(color = speedColor(genTier.tone, colors))) { append(first) }
        if (parts.isNotEmpty()) {
            withStyle(SpanStyle(color = colors.textSecondary)) { append(" \u00b7 ") }
        }
    }
    if (parts.isNotEmpty()) {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(parts.joinToString(" \u00b7 ")) }
    }
    if (m.partial || m.stopReason == "max_tokens") {
        withStyle(SpanStyle(color = colors.danger)) {
            append(" \u00b7 ")
            append(if (m.partial) "incomplete" else "truncated")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MetricsBottomSheet(
    metrics: MessageMetrics?,
    activeChat: Chat?,
    contextWindowFallback: Int,
    onDismiss: () -> Unit,
) {
    val colors = OmniTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
        scrimColor = Color(0x80000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            // Sticky sheet header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Metrics",
                    color = colors.textSecondary,
                    fontFamily = InterFontFamily,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OmniIconButton(
                    icon = OmniIcons.Close,
                    contentDescription = "Close metrics",
                    tint = colors.textMuted,
                    onClick = onDismiss,
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)

            if (metrics == null) {
                Text(
                    text = "No metrics yet.",
                    color = colors.textMuted,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                val m = metrics
                val legacyEst = m.tokensEstimated && m.inputEstimated == null && m.outputEstimated == null
                val estIn = (m.inputEstimated == true) || legacyEst
                val estOut = (m.outputEstimated == true) || legacyEst
                val ctxEst = m.contextEstimated ?: (estIn || estOut)
                val phases = MetricsCalculator.metricPhases(m)
                val fromServer = m.rateSource == "server"
                val promptFromServer = m.promptRateSource == "server" || (m.promptRateSource == null && fromServer)
                val rateNote = if (fromServer) "from ${m.rateEngine ?: "the server"}" else "measured on-device"
                val promptRateNote = if (promptFromServer) "from ${m.rateEngine ?: "the server"}" else "upper bound"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Row 1: Speed (Gen t/s, Prefill t/s)
                    val speedCells = buildList<@Composable (Modifier) -> Unit> {
                        val genTs = m.generationTokensPerSecond
                        if (genTs != null) {
                            val tier = MetricsCalculator.pickSpeedTier(genTs)
                            val sub = buildList {
                                add(tier.word)
                                if (m.genWindowMs != null) add("over ${MetricsCalculator.formatMs(m.genWindowMs)}")
                                else if (fromServer) add(rateNote)
                                else if (phases != null) add("over ${MetricsCalculator.formatMs(phases.think + phases.gen)}")
                                else add(rateNote)
                            }.joinToString(" \u00b7 ")
                            add { mod ->
                                MetricCell(
                                    label = "GEN T/S",
                                    value = "${if (estOut) "~" else ""}${MetricsCalculator.formatRate(genTs)} t/s",
                                    valueColor = if (estOut) colors.textSecondary else speedColor(tier.tone, colors),
                                    estimated = estOut,
                                    sub = sub,
                                    modifier = mod,
                                )
                            }
                        }
                        val promptTs = m.promptTokensPerSecond
                        if (promptTs != null) {
                            add { mod ->
                                MetricCell(
                                    label = "PREFILL T/S",
                                    value = "${if (estIn) "~" else ""}${MetricsCalculator.formatRate(promptTs)} t/s",
                                    valueColor = if (estIn) colors.textSecondary else colors.text,
                                    estimated = estIn,
                                    sub = promptRateNote,
                                    modifier = mod,
                                )
                            }
                        }
                    }
                    MetricPairRow(speedCells)

                    // Row 2: Latency (TTFT, Total)
                    val latencyCells = buildList<@Composable (Modifier) -> Unit> {
                        if (m.ttftMs != null) {
                            val sub = if ((m.thinkMs ?: 0.0) > 0.0) {
                                "+${MetricsCalculator.formatMs(m.thinkMs)} to answer"
                            } else ""
                            add { mod ->
                                MetricCell(
                                    label = "TTFT",
                                    value = MetricsCalculator.formatMs(m.ttftMs),
                                    valueColor = colors.text,
                                    estimated = false,
                                    sub = sub,
                                    modifier = mod,
                                )
                            }
                        }
                        if (m.totalDurationMs != null) {
                            val durSub = buildList {
                                if (m.turns > 1) add("${m.turns} turns")
                                if (m.toolCalls > 0) add("${m.toolCalls} tool call${if (m.toolCalls != 1) "s" else ""}")
                            }.joinToString(" \u00b7 ")
                            add { mod ->
                                MetricCell(
                                    label = "TOTAL",
                                    value = MetricsCalculator.formatMs(m.totalDurationMs),
                                    valueColor = colors.text,
                                    estimated = false,
                                    sub = durSub,
                                    modifier = mod,
                                )
                            }
                        }
                    }
                    MetricPairRow(latencyCells)

                    // Wide: Time breakdown bar + legend
                    if (phases != null) {
                        TimeBreakdownCell(phases = phases)
                    }

                    // Row 3: Tokens (Input, Output)
                    val tokenCells = buildList<@Composable (Modifier) -> Unit> {
                        if (m.inputTokens != null) {
                            val inSub = buildList {
                                if ((m.cachedTokens ?: 0L) > 0L) add("${MetricsCalculator.fmtInt(m.cachedTokens!!)} cached")
                                if ((m.cacheWriteTokens ?: 0L) > 0L) add("${MetricsCalculator.fmtInt(m.cacheWriteTokens!!)} written")
                                if (m.turns > 1) add("over ${m.turns} turns")
                            }.joinToString(" \u00b7 ")
                            add { mod ->
                                MetricCell(
                                    label = "INPUT",
                                    value = "${if (estIn) "~" else ""}${MetricsCalculator.fmtInt(m.inputTokens)}",
                                    valueColor = if (estIn) colors.textSecondary else colors.text,
                                    estimated = estIn,
                                    sub = inSub,
                                    modifier = mod,
                                )
                            }
                        }
                        if (m.outputTokens != null) {
                            val outSub = buildList {
                                val rTok = m.reasoningTokens ?: 0L
                                if (rTok > 0L && rTok <= m.outputTokens) {
                                    add("${MetricsCalculator.fmtInt(rTok)} reasoning")
                                    add("${MetricsCalculator.fmtInt(m.outputTokens - rTok)} visible")
                                }
                            }.joinToString(" \u00b7 ")
                            add { mod ->
                                MetricCell(
                                    label = "OUTPUT",
                                    value = "${if (estOut) "~" else ""}${MetricsCalculator.fmtInt(m.outputTokens)}",
                                    valueColor = if (estOut) colors.textSecondary else colors.text,
                                    estimated = estOut,
                                    sub = outSub,
                                    modifier = mod,
                                )
                            }
                        }
                    }
                    MetricPairRow(tokenCells)

                    // Wide: Context usage
                    val ctxMax = if (m.contextWindow > 0) m.contextWindow else contextWindowFallback
                    if (m.contextUsed != null && ctxMax > 0) {
                        val ctxPct = MetricsCalculator.contextPercent(m.contextUsed, ctxMax)
                        val ctxTier = MetricsCalculator.pickContextTier(ctxPct)
                        val valColor = when (ctxTier.tone) {
                            "warning" -> colors.statusWarning
                            "serious" -> colors.statusSerious
                            "critical" -> colors.statusCritical
                            else -> if (ctxEst) colors.textSecondary else colors.text
                        }
                        val barColor = when (ctxTier.barTone) {
                            "good" -> colors.statusGood
                            "warning" -> colors.statusWarning
                            "serious" -> colors.statusSerious
                            "critical" -> colors.statusCritical
                            else -> colors.accent
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MetricCell(
                                label = "CONTEXT USAGE",
                                value = "${if (ctxEst) "~" else ""}${MetricsCalculator.fmtInt(m.contextUsed)} / ${MetricsCalculator.fmtInt(ctxMax.toLong())} (${String.format(Locale.US, "%.1f", ctxPct)}%)",
                                valueColor = valColor,
                                estimated = ctxEst,
                                sub = "",
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0x1AFFFFFF)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((ctxPct / 100.0).toFloat().coerceIn(0.01f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(barColor),
                                )
                            }
                            Text(
                                text = "${ctxTier.word} \u00b7 ${MetricsCalculator.fmtInt((ctxMax - m.contextUsed).coerceAtLeast(0L))} left",
                                color = colors.textSecondary,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.5.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    // Wide: Billed this chat
                    val totals = MetricsCalculator.chatTokenTotals(activeChat)
                    if (totals.replies > 0) {
                        val totalSub = buildString {
                            append(MetricsCalculator.fmtInt(totals.input + totals.output))
                            append(" across ${totals.replies} repl${if (totals.replies != 1) "ies" else "y"}")
                            if (totals.cached > 0L) append(" \u00b7 ${MetricsCalculator.fmtInt(totals.cached)} cached")
                        }
                        MetricCell(
                            label = "BILLED THIS CHAT",
                            value = "${if (totals.inputEstimated) "~" else ""}${MetricsCalculator.fmtInt(totals.input)} in / ${if (totals.outputEstimated) "~" else ""}${MetricsCalculator.fmtInt(totals.output)} out",
                            valueColor = if (totals.inputEstimated && totals.outputEstimated) colors.textSecondary else colors.text,
                            estimated = totals.inputEstimated && totals.outputEstimated,
                            sub = totalSub,
                        )
                    }

                    // Provenance footer
                    val notes = buildList<Pair<String, Boolean>> {
                        if (m.partial) add("partial \u2014 the reply did not finish" to true)
                        MetricsCalculator.STOP_REASON_TEXT[m.stopReason]?.let { text ->
                            add(text to (m.stopReason != "stop_sequence"))
                        }
                        if (m.rateUnmeasurable) add("arrived too fast to time a generation rate" to false)
                        when {
                            estIn && estOut -> add("counts estimated from text length" to false)
                            estIn -> add("input estimated from text length" to false)
                            estOut -> add("output estimated from text length" to false)
                            else -> add("counts reported by the provider" to false)
                        }
                        if (!m.rateEngine.isNullOrEmpty()) add("timings from ${m.rateEngine}" to false)
                    }
                    if (notes.isNotEmpty()) {
                        HorizontalDivider(color = colors.border, thickness = 1.dp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            notes.forEach { (text, isWarn) ->
                                Text(
                                    text = text,
                                    color = if (isWarn) colors.danger else colors.textSecondary,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun MetricPairRow(cells: List<@Composable (Modifier) -> Unit>) {
    if (cells.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cells.forEach { cell ->
            cell(Modifier.weight(1f))
        }
        if (cells.size == 1) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    valueColor: Color,
    estimated: Boolean,
    sub: String,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    Column(modifier = modifier) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = valueColor)) { append(value) }
                if (estimated) {
                    withStyle(
                        SpanStyle(
                            color = valueColor.copy(alpha = 0.65f),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            baselineShift = BaselineShift.Superscript,
                        ),
                    ) {
                        append(" est")
                    }
                }
            },
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
        )
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                color = colors.textSecondary,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeBreakdownCell(phases: MetricPhases) {
    val colors = OmniTheme.colors
    val items = listOf(
        Triple("prefill", phases.wait, colors.speed1),
        Triple("reasoning", phases.think, colors.speed2),
        Triple("answer", phases.gen, colors.speed3),
        Triple("tools", phases.tool, colors.speed4),
    ).filter { it.second > 0.0 }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "TIME BREAKDOWN",
            color = colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x0FFFFFFF)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { (_, ms, color) ->
                val weight = (ms / phases.total).toFloat().coerceAtLeast(0.01f)
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items.forEach { (name, ms, color) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color),
                    )
                    Text(
                        text = "$name ${MetricsCalculator.formatMs(ms)}",
                        color = colors.textSecondary,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.5.sp,
                    )
                }
            }
        }
    }
}

private fun speedColor(tier: Int, colors: OmniColorPalette): Color = when (tier) {
    1 -> colors.speed1
    2 -> colors.speed2
    3 -> colors.speed3
    else -> colors.speed4
}
