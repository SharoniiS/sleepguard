package com.sleepguard.poc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepguard.poc.NightRecord
import com.sleepguard.poc.SleepViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val emoji: String) {
    HOME("בית", "🏠"),
    LAST("לילה אחרון", "🌙"),
    HISTORY("היסטוריה", "🕘"),
    MORE("מידע נוסף", "ℹ️")
}

@Composable
fun SleepApp(vm: SleepViewModel, onOpenSettings: () -> Unit) {
    // In-app navigation stack so the device Back button moves between screens (and never exits from
    // an inner screen). Root = Home.
    val stack = remember { mutableStateListOf<Dest>(Dest.TabDest(Tab.HOME)) }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val current = stack.last()
    val activeTab = (stack.lastOrNull { it is Dest.TabDest } as? Dest.TabDest)?.tab ?: Tab.HOME

    fun goTab(t: Tab) {
        if (current !is Dest.TabDest || current.tab != t) stack.add(Dest.TabDest(t))
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == activeTab,
                        onClick = { goTab(t) },
                        icon = { Text(t.emoji, fontSize = 18.sp) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            when {
                !vm.hasPermission -> Padded { NeedPermission(onOpenSettings) }
                vm.nights.isEmpty() ->
                    Padded { CenteredMessage("אין עדיין נתונים. פִּתחי את האפליקציה בבוקר אחרי לילה.") }
                else -> when (val d = current) {
                    is Dest.TabDest -> when (d.tab) {
                        Tab.HOME -> Padded { HomeScreen(vm.latestComplete) }
                        Tab.HISTORY -> Padded { HistoryScreen(vm.nights) { stack.add(Dest.Report(it)) } }
                        Tab.MORE -> Padded { MoreInfoScreen() }
                        Tab.LAST -> {
                            val latest = vm.latestComplete
                            if (latest != null)
                                NightReport(latest, vm.getReport(latest.nightOf), onBack = null) {
                                    stack.add(Dest.Quest(latest))
                                }
                            else Padded { CenteredMessage("אין עדיין לילה שלם להצגה.") }
                        }
                    }
                    is Dest.Report ->
                        NightReport(d.night, vm.getReport(d.night.nightOf), onBack = { back() }) {
                            stack.add(Dest.Quest(d.night))
                        }
                    is Dest.Quest ->
                        QuestionnaireScreen(d.night.nightOf, vm.getReport(d.night.nightOf), vm) { back() }
                }
            }
        }
    }
}

private sealed interface Dest {
    data class TabDest(val tab: Tab) : Dest
    data class Report(val night: NightRecord) : Dest
    data class Quest(val night: NightRecord) : Dest
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
}

// ---------------------------------------------------------------- state screens

@Composable
private fun NeedPermission(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("כדי להתחיל, אפשרי ל-SleepGuard גישה לנתוני שימוש.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text("אפשר גישה לנתוני המערכת") }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { Text(text, textAlign = TextAlign.Center) }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(latest: NightRecord?) {
    if (latest == null) {
        CenteredMessage("אין עדיין לילה שלם להצגה.")
        return
    }
    val q = quiet(latest)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroBanner("בית", subtitle = dateWithDay(latest.nightOf))
        Spacer(Modifier.height(16.dp))

        // Main summary card
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2350), Color(0xFF0E1430))))
                .border(1.dp, Color(0x143D74FF), RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x143D74FF)),
                contentAlignment = Alignment.Center
            ) { Text("🌙", fontSize = 24.sp) }
            Spacer(Modifier.height(14.dp))
            Ltr {
                Text(
                    if (q != null) "${fmt(q.first)} – ${fmt(q.second)}" else "—",
                    fontFamily = Rubik, fontSize = 34.sp, fontWeight = FontWeight.Bold
                )
            }
            Text("חלון חוסר פעילות", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Text(if (q != null) dur(q.third) else "—", fontFamily = Rubik, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("משך חוסר הפעילות", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(availabilityHe(latest.confidence))
                Chip(patternHe(latest.restPattern))
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), "הפרעות",
                if (latest.awakenings.isEmpty()) "ללא" else latest.awakenings.size.toString())
            StatCard(Modifier.weight(1f), "שימוש לפני חוסר הפעילות",
                latest.preSleepPhoneTimeMillis?.let { "${it / 60000} דק'" } ?: "לא ידוע")
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryScreen(nights: List<NightRecord>, onOpen: (NightRecord) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HeroBanner("היסטוריה") }
        items(nights) { n -> HistoryRow(n) { onOpen(n) } }
    }
}

@Composable
private fun HistoryRow(n: NightRecord, onClick: () -> Unit) {
    val q = quiet(n)
    val color = patternColor(n.restPattern)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(color)
                    Text(dateWithDay(n.nightOf), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Ltr { Text(if (q != null) "${fmt(q.first)} – ${fmt(q.second)}" else "—", fontSize = 15.sp) }
                Text(if (q != null) dur(q.third) else "—", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Chip(patternHe(n.restPattern))
            ProgressTrack((q?.third ?: 0L).toFloat() / TEN_HOURS_MS, color)
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ProgressTrack(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(color)
        )
    }
}

@Composable
private fun patternColor(p: String): Color = when (p) {
    "CONSOLIDATED" -> Color(0xFF34C759)
    "FRAGMENTED" -> Color(0xFFFF9F0A)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------- More info

@Composable
private fun MoreInfoScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoCard(
            "SleepGuard · גרסה 1.0",
            "SleepGuard עוקבת אחר פעילות הטלפון בלילה. האפליקציה אינה מאבחנת, אינה מודדת שינה, " +
                "ואינה מהווה תחליף לייעוץ רפואי. מטרתה לאפשר מעקב תיאורי אחר דפוסי פעילות."
        )
        InfoCard(
            "מקור הנתונים",
            "נתוני הפעילות נאספים על ידי הרכיב הנייטיבי של האפליקציה, שמנטר את זמני הדלקת/כיבוי " +
                "המסך והנעילה. לא נאסף תוכן, מידע אישי, או נתוני אפליקציות."
        )
        InfoCard(
            "פרטיות",
            "• לא נאסף מידע אישי מזהה\n• הנתונים נשמרים במכשיר בלבד ואינם נשלחים לשום מקום\n" +
                "• תוכן המסך אינו נקרא או נשמר"
        )
    }
}

// ---------------------------------------------------------------- shared pieces (internal: used by NightReport.kt)

/** Forces left-to-right layout for its content (e.g. a HH:mm–HH:mm range inside the RTL UI). */
@Composable
internal fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
internal fun Chip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun StatCard(modifier: Modifier, label: String, value: String) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2142), Color(0xFF121834))))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontFamily = Rubik, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- helpers (internal: shared with NightReport.kt)

private val zone: ZoneId = ZoneId.systemDefault()
private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

internal fun fmt(ms: Long?): String = if (ms == null) "—" else hm.format(Instant.ofEpochMilli(ms))

internal fun dur(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

internal fun quiet(r: NightRecord): Triple<Long, Long, Long>? =
    r.mainRestEpisode?.let { Triple(it.startMillis, it.endMillis, it.durationMillis) }
        ?: r.primaryRest?.let { Triple(it.startMillis, it.endMillis, it.durationMillis) }

internal fun patternHe(p: String): String = when (p) {
    "CONSOLIDATED" -> "רצוף"
    "FRAGMENTED" -> "מקוטע"
    "MINIMAL_REST" -> "פעיל ברובו"
    else -> p
}

internal fun availabilityHe(c: String): String = when (c) {
    "HIGH" -> "נתונים מלאים"
    "MEDIUM" -> "נתונים חלקיים"
    "LOW" -> "נתונים מועטים"
    else -> c
}

/** Reference span for the History progress bar (10h = a "full" bar). */
private const val TEN_HOURS_MS = 36_000_000f

/** "2026-06-27" -> "יום ו', 27.6". */
internal fun dateWithDay(nightOf: String): String = runCatching {
    val d = LocalDate.parse(nightOf)
    "${hebrewDay(d.dayOfWeek)}, ${d.dayOfMonth}.${d.monthValue}"
}.getOrDefault(nightOf)

private fun hebrewDay(dow: DayOfWeek): String = when (dow) {
    DayOfWeek.SUNDAY -> "יום א'"
    DayOfWeek.MONDAY -> "יום ב'"
    DayOfWeek.TUESDAY -> "יום ג'"
    DayOfWeek.WEDNESDAY -> "יום ד'"
    DayOfWeek.THURSDAY -> "יום ה'"
    DayOfWeek.FRIDAY -> "יום ו'"
    DayOfWeek.SATURDAY -> "שבת"
}

private fun hoursHe(h: Long): String = when (h) {
    1L -> "שעה"
    2L -> "שעתיים"
    else -> "$h שעות"
}

/** Hebrew duration, e.g. "6 שעות ו-57 דקות" / "שעתיים" / "45 דקות". */
internal fun durHe(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${hoursHe(h)} ו-$m דקות"
        h > 0 -> hoursHe(h)
        else -> "$m דקות"
    }
}
