package com.sleepguard.poc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepguard.poc.NightRecord
import com.sleepguard.poc.SleepViewModel
import java.time.Instant
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
    var tab by remember { mutableStateOf(Tab.HOME) }
    // A history night opened full-screen as its Night Report (null = on the tabs).
    var reportNight by remember { mutableStateOf<NightRecord?>(null) }

    val opened = reportNight
    if (opened != null) {
        NightReportRoute(opened, vm, onBack = { reportNight = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
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
                tab == Tab.HOME -> Padded { HomeScreen(vm.latestComplete) }
                tab == Tab.HISTORY -> Padded { HistoryScreen(vm.nights) { reportNight = it } }
                tab == Tab.MORE -> Padded { MoreInfoScreen() }
                tab == Tab.LAST -> {
                    val latest = vm.latestComplete
                    if (latest != null) NightReportRoute(latest, vm, onBack = null)
                    else Padded { CenteredMessage("אין עדיין לילה שלם להצגה.") }
                }
            }
        }
    }
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
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(latest.nightOf, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Ltr {
            Text(
                if (q != null) "${fmt(q.first)} – ${fmt(q.second)}" else "—",
                fontSize = 34.sp, fontWeight = FontWeight.Bold
            )
        }
        Text("חלון חוסר פעילות", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text(if (q != null) dur(q.third) else "—", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("משך חוסר הפעילות", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(availabilityHe(latest.confidence))
            Chip(patternHe(latest.restPattern))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), "הפרעות",
                if (latest.awakenings.isEmpty()) "ללא" else latest.awakenings.size.toString())
            StatCard(Modifier.weight(1f), "שימוש לפני חוסר הפעילות",
                latest.preSleepPhoneTimeMillis?.let { "${it / 60000} דק'" } ?: "לא ידוע")
        }
    }
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryScreen(nights: List<NightRecord>, onOpen: (NightRecord) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(nights) { n ->
            val q = quiet(n)
            Card(Modifier.fillMaxWidth().clickable { onOpen(n) }) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (q != null) dur(q.third) else "—", fontWeight = FontWeight.Bold)
                    Ltr { Text(if (q != null) "${fmt(q.first)} – ${fmt(q.second)}" else "—") }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(n.nightOf, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Chip(patternHe(n.restPattern))
                    }
                }
            }
        }
    }
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
    Card(modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
