package com.sleepguard.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepguard.poc.MorningReportEntity
import com.sleepguard.poc.NightRecord
import com.sleepguard.poc.SleepViewModel
import com.sleepguard.poc.StoredEvent

/**
 * Owns the Night Report and its Questionnaire sub-screen. Used both by the "לילה אחרון" tab
 * (onBack = null) and from a History row (onBack pops back to the list).
 */
@Composable
fun NightReportRoute(night: NightRecord, vm: SleepViewModel, onBack: (() -> Unit)?) {
    var report by remember(night.nightOf) { mutableStateOf(vm.getReport(night.nightOf)) }
    var editing by remember(night.nightOf) { mutableStateOf(false) }

    if (editing) {
        QuestionnaireScreen(
            nightOf = night.nightOf,
            initial = report,
            onSave = { saved -> vm.saveReport(saved); report = saved; editing = false },
            onCancel = { editing = false }
        )
    } else {
        NightReport(night, report, onBack = onBack, onFillQuestionnaire = { editing = true })
    }
}

@Composable
private fun NightReport(
    record: NightRecord,
    report: MorningReportEntity?,
    onBack: (() -> Unit)?,
    onFillQuestionnaire: () -> Unit
) {
    val q = quiet(record)
    var rawOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("→") }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("דו\"ח פעילות יומי", fontWeight = FontWeight.Bold)
                Text(record.nightOf, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(availabilityHe(record.confidence))
                    Chip(patternHe(record.restPattern))
                }
            }
            item { Text(summaryHe(record), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TimelineBar(record) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), "פעילות אחרונה", fmt(q?.first))
                    StatCard(Modifier.weight(1f), "פעילות ראשונה", fmt(q?.second))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), "חזרות לפעילות",
                        if (record.awakenings.isEmpty()) "ללא" else record.awakenings.size.toString())
                    StatCard(Modifier.weight(1f), "סך זמן חוסר הפעילות", if (q != null) dur(q.third) else "—")
                }
            }
            item {
                StatCard(Modifier.fillMaxWidth(), "שימוש שעתיים לפני השינה",
                    record.preSleepPhoneTimeMillis?.let { "${it / 60000} דק'" } ?: "לא ידוע")
            }
            item {
                Card(Modifier.fillMaxWidth().clickable { rawOpen = !rawOpen }) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (rawOpen) "▲" else "▼")
                        Text("יומן פעילות גולמי (${record.events.size})", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (rawOpen) {
                items(record.events.asReversed()) { e -> RawEventRow(e) }
            }
            item { InsightCard(insightHe(record)) }
            item { QuestionnaireCard(report, onFillQuestionnaire) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- timeline (read-only, v1)

@Composable
private fun TimelineBar(record: NightRecord) {
    Column {
        Text("ציר זמן", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val q = quiet(record)
        val axisStart = record.windowStartMillis
        val axisEnd = maxOf(record.collectedAtMillis, q?.second ?: record.windowEndMillis)
        val total = (axisEnd - axisStart).coerceAtLeast(1L).toFloat()
        Row(
            Modifier.fillMaxWidth().height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (q != null) {
                val preF = ((q.first - axisStart).toFloat() / total).coerceIn(0f, 1f)
                val quietF = ((q.second - q.first).toFloat() / total).coerceIn(0f, 1f)
                val postF = (1f - preF - quietF).coerceAtLeast(0f)
                if (preF > 0f) Spacer(Modifier.weight(preF))
                Spacer(
                    Modifier.weight(quietF.coerceAtLeast(0.001f)).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                if (postF > 0f) Spacer(Modifier.weight(postF))
            }
        }
        Spacer(Modifier.height(4.dp))
        Ltr {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(axisStart), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmt(axisEnd), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "המקטע המודגש = חלון חוסר הפעילות. פעילות לפני המנוחה היא הערכה גסה בלבד.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RawEventRow(e: StoredEvent) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(eventHe(e.type), fontSize = 13.sp)
        Text(fmt(e.timestampMillis), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("🦉 תובנת הינשוף", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun QuestionnaireCard(report: MorningReportEntity?, onFill: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("שאלון יומי", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (report == null) "השאלון טרם מולא ללילה זה." else "השאלון מולא.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onFill) { Text(if (report == null) "מילוי שאלון" else "עריכת שאלון") }
        }
    }
}

// ---------------------------------------------------------------- questionnaire screen

@Composable
private fun QuestionnaireScreen(
    nightOf: String,
    initial: MorningReportEntity?,
    onSave: (MorningReportEntity) -> Unit,
    onCancel: () -> Unit
) {
    var nightmares by remember { mutableStateOf(initial?.nightmares) }
    var cannabis by remember { mutableStateOf(initial?.cannabis) }
    var alcohol by remember { mutableStateOf(initial?.alcohol) }
    var meds by remember { mutableStateOf(initial?.medications ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("שאלון יומי", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("אפשר להוסיף פרטים שיעזרו לך להבין את הלילה הזה.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        YesNoRow("האם היו סיוטים?", nightmares) { nightmares = it }
        OutlinedTextField(
            value = meds, onValueChange = { meds = it },
            label = { Text("תרופות") }, modifier = Modifier.fillMaxWidth()
        )
        YesNoRow("קנאביס", cannabis) { cannabis = it }
        YesNoRow("אלכוהול", alcohol) { alcohol = it }
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("הערה חופשית") }, minLines = 3, modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                onSave(
                    MorningReportEntity(
                        nightOf = nightOf,
                        nightmares = nightmares,
                        medications = meds.ifBlank { null },
                        cannabis = cannabis,
                        alcohol = alcohol,
                        note = note.ifBlank { null },
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("שמור") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("אפשר למלא גם אחר כך")
        }
    }
}

@Composable
private fun YesNoRow(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f))
            SelectChip("לא", value == false) { onChange(false) }
            Spacer(Modifier.width(8.dp))
            SelectChip("כן", value == true) { onChange(true) }
        }
    }
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// ---------------------------------------------------------------- factual text + labels

/** Short factual summary line at the top of the report. */
internal fun summaryHe(r: NightRecord): String {
    val q = quiet(r) ?: return "לא זוהתה תקופת חוסר-פעילות ארוכה בלילה זה."
    return "פעילות הטלפון הייתה ${patternHe(r.restPattern)}. החלון הארוך ביותר ללא שימוש: ${dur(q.third)}."
}

/** Factual "owl insight" — observational, no soft judgments (per the UX-wording rules). */
internal fun insightHe(r: NightRecord): String {
    if (r.restPattern == "MINIMAL_REST" || quiet(r) == null)
        return "לא זוהתה תקופת חוסר-פעילות ארוכה בלילה זה."
    val base = when (r.restPattern) {
        "CONSOLIDATED" -> "תקופת חוסר הפעילות הייתה רצופה ברובה."
        "FRAGMENTED" -> "תקופת חוסר הפעילות הייתה מקוטעת."
        else -> ""
    }
    val n = r.awakenings.size
    val inter = if (n == 0) "לא זוהו הפרעות בשעות חוסר הפעילות."
    else "זוהו $n הפרעות בשעות חוסר הפעילות."
    return "$base $inter".trim()
}

private fun eventHe(type: String): String = when (type) {
    "SCREEN_INTERACTIVE" -> "מסך נדלק"
    "SCREEN_NON_INTERACTIVE" -> "מסך נכבה"
    "KEYGUARD_HIDDEN" -> "פתיחה"
    "KEYGUARD_SHOWN" -> "נעילה"
    else -> type
}
