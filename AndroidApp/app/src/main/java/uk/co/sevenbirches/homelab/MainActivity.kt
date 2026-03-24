// @file:Suppress handles spell-check warnings for technical terms
@file:Suppress("SpellCheckingInspection", "UNUSED_VALUE", "UNUSED_VARIABLE", "ReplaceGetOrDefault", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE", "NAME_SHADOWING", "LocalVariableName", "ktlint")
package uk.co.sevenbirches.homelab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uk.co.sevenbirches.homelab.ui.theme.HomelabDashboardTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll

// Colours
val DarkBg      = Color(0xFF0A0E1A)
val CardBg      = Color(0xFF111827)
val NavBg       = Color(0xFF0D1220)
val AccentGreen = Color(0xFF00FF88)
val AccentRed   = Color(0xFFFF4757)
val AccentAmber = Color(0xFFFFB830)
val AccentBlue  = Color(0xFF00B4D8)
val TextPrimary = Color(0xFFE2E8F0)
val TextMuted   = Color(0xFF64748B)

// Data classes
data class AgentSummary(
    val totalContainers: Int, val runningContainers: Int, val openAlerts: Int,
    val actionsTaken: Int, val monthlyCostUsd: Double, val timestamp: String
)

data class ContainerStatus(
    val id: String, val name: String, val running: Boolean,
    val cpuPercent: Double, val memPercent: Double, val diskPercent: Double, val timestamp: String
)

data class OpenAlert(
    val containerName: String, val vmid: String, val alertType: String, val severity: String,
    val firstSeen: String, val lastSeen: String, val acknowledged: Boolean,
    val snoozedUntil: String?, val resolved: Boolean
)

data class VpsContainer(val name: String, val running: Boolean)

data class VpsStatus(
    val reachable: Boolean, val diskPercent: Int, val memoryPercent: Double,
    val runningContainers: Int, val totalContainers: Int,
    val containers: List<VpsContainer>, val timestamp: String
)


data class CostEntry(
    val date: String, val modelId: String, val calls: Int,
    val totalInputTokens: Int, val totalOutputTokens: Int, val costUsd: Double
)

data class PbsStatus(
    val reachable: Boolean, val lastRun: String, val lastRunDate: String,
    val containersBackedUp: Int, val totalContainers: Int, val staleContainers: List<String>
)

data class MetricDeviation(val metric: String, val current: Double, val baselineAvg: Double, val ratio: Double)

data class ContainerDeviation(
    val vmid: String, val name: String, val hour: Int,
    val baselineSamples: Int, val deviations: List<MetricDeviation>
)

data class BaselineSummary(
    val status: String, val containersBaselined: Int, val totalEntries: Int,
    val newestComputed: String, val avgSamplesPerEntry: Double,
    val deviationCount: Int, val currentDeviations: List<ContainerDeviation>
)

data class AskResponse(
    val question: String, val answer: String, val model: String,
    val inputTokens: Int, val outputTokens: Int, val deviationsIncluded: Int
)

data class ServiceItem(
    val name: String, val url: String, val iconRes: Int?,
    val iconLetter: String = "", val iconColor: Color = AccentBlue,
    val containerName: String? = null
)

// API
val httpClient = OkHttpClient()
const val BASE_URL = "https://metrics.sevenbirches.co.uk"

suspend fun fetchSummary(): AgentSummary? = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/summary").build()).execute().body?.string() ?: return@withContext null
        val j = JSONObject(body)
        AgentSummary(j.getInt("total_containers"), j.getInt("running_containers"), j.getInt("open_alerts"), j.getInt("actions_taken"), j.getDouble("monthly_cost_usd"), j.getString("timestamp"))
    } catch (_: Exception) { null }
}

suspend fun fetchContainers(): List<ContainerStatus> = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/metrics/latest").build()).execute().body?.string() ?: return@withContext emptyList()
        val json = JSONArray(body)
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            ContainerStatus("CT${o.getString("vmid")}", o.getString("name"), o.getString("status") == "running", o.getDouble("cpu_percent"), o.getDouble("memory_percent"), o.getDouble("disk_percent"), o.getString("timestamp"))
        }.sortedBy { it.id.removePrefix("CT").toIntOrNull() ?: 0 }
    } catch (_: Exception) { emptyList() }
}

fun parseAlertArray(body: String): List<OpenAlert> {
    val json = JSONArray(body)
    return (0 until json.length()).map { i ->
        val o = json.getJSONObject(i)
        OpenAlert(o.getString("container_name"), o.optString("vmid", ""), o.getString("alert_type"), o.getString("severity"), o.optString("first_seen", ""), o.optString("last_seen", ""), o.optBoolean("acknowledged", false), o.optString("snoozed_until", "").takeIf { it.isNotEmpty() }, o.optInt("resolved", 0) == 1)
    }
}

suspend fun fetchAlertHistory(): List<OpenAlert> = withContext(Dispatchers.IO) {
    try { val body = httpClient.newCall(Request.Builder().url("$BASE_URL/alerts/history?limit=50").build()).execute().body?.string() ?: return@withContext emptyList(); parseAlertArray(body) } catch (_: Exception) { emptyList() }
}

suspend fun fetchVpsStatus(): VpsStatus? = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/vps/status").build()).execute().body?.string() ?: return@withContext null
        val j = JSONObject(body)
        if (!j.optBoolean("reachable", false)) return@withContext null
        val ca = j.getJSONArray("containers")
        VpsStatus(true, j.getInt("disk_percent"), j.getDouble("memory_percent"), j.getInt("running_containers"), j.getInt("total_containers"), (0 until ca.length()).map { i -> val o = ca.getJSONObject(i); VpsContainer(o.getString("name"), o.getBoolean("running")) }, j.getString("timestamp"))
    } catch (_: Exception) { null }
}

suspend fun fetchCosts(): List<CostEntry> = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/costs/summary").build()).execute().body?.string() ?: return@withContext emptyList()
        val json = JSONArray(body)
        (0 until json.length()).map { i -> val o = json.getJSONObject(i); CostEntry(o.getString("date"), o.getString("model_id"), o.getInt("calls"), o.getInt("total_input_tokens"), o.getInt("total_output_tokens"), o.getDouble("cost_usd")) }
    } catch (_: Exception) { emptyList() }
}

suspend fun fetchPbsStatus(): PbsStatus? = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/pbs/status").build()).execute().body?.string() ?: return@withContext null
        val j = JSONObject(body)
        if (!j.optBoolean("reachable", false)) return@withContext null
        val backups = j.getJSONArray("backups"); val lrd = j.getString("last_run_date")
        val stale = mutableListOf<String>()
        for (i in 0 until backups.length()) { val b = backups.getJSONObject(i); if (!b.getString("last_backup").startsWith(lrd)) stale.add("CT${b.getString("vmid")}") }
        PbsStatus(true, j.getString("last_run"), lrd, j.getInt("containers_backed_up"), j.getInt("total_containers"), stale)
    } catch (_: Exception) { null }
}

suspend fun fetchBaselines(): BaselineSummary? = withContext(Dispatchers.IO) {
    try {
        val body = httpClient.newCall(Request.Builder().url("$BASE_URL/baselines/summary").build()).execute().body?.string() ?: return@withContext null
        val j = JSONObject(body)
        val s = j.getJSONObject("baseline_status")
        if (s.getString("status") != "ok") return@withContext null
        val da = j.getJSONArray("current_deviations")
        val devs = (0 until da.length()).map { i ->
            val d = da.getJSONObject(i); val ma = d.getJSONArray("deviations")
            ContainerDeviation(d.getString("vmid"), d.getString("name"), d.getInt("hour"), d.getInt("baseline_samples"), (0 until ma.length()).map { j2 -> val m = ma.getJSONObject(j2); MetricDeviation(m.getString("metric"), m.getDouble("current"), m.getDouble("baseline_avg"), m.getDouble("ratio")) })
        }
        BaselineSummary(s.getString("status"), s.getInt("containers_baselined"), s.getInt("total_entries"), s.getString("newest_computed"), s.getDouble("avg_samples_per_entry"), j.getInt("deviation_count"), devs)
    } catch (_: Exception) { null }
}

suspend fun postAsk(question: String): AskResponse? = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject().apply { put("question", question) }.toString().toRequestBody("application/json".toMediaType())
        val rs = httpClient.newCall(Request.Builder().url("$BASE_URL/ask").post(body).build()).execute().body?.string() ?: return@withContext null
        val j = JSONObject(rs)
        if (j.has("error")) return@withContext null
        AskResponse(j.getString("question"), j.getString("answer"), j.getString("model"), j.getInt("input_tokens"), j.getInt("output_tokens"), j.getInt("deviations_included"))
    } catch (_: Exception) { null }
}

suspend fun containerAction(vmid: String, action: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val vn = vmid.removePrefix("CT")
        val eb = "{}".toRequestBody("application/json".toMediaType())
        val r = httpClient.newCall(Request.Builder().url("$BASE_URL/containers/$vn/$action").post(eb).build()).execute()
        val bs = r.body?.string() ?: "{}"; val j = JSONObject(bs)
        val msg = j.optString("message", j.optString("error", "No response")); val sn = j.optString("snapshot_note", "")
        Pair(r.isSuccessful, if (sn.isNotEmpty()) "$msg\n$sn" else msg)
    } catch (e: Exception) { Pair(false, e.message ?: "Request failed") }
}

suspend fun acknowledgeAlert(containerName: String, alertType: String): Boolean = withContext(Dispatchers.IO) {
    try { val b = JSONObject().apply { put("container_name", containerName); put("alert_type", alertType) }.toString().toRequestBody("application/json".toMediaType()); httpClient.newCall(Request.Builder().url("$BASE_URL/alerts/acknowledge").post(b).build()).execute().isSuccessful } catch (_: Exception) { false }
}

suspend fun snoozeAlert(containerName: String, alertType: String): Boolean = withContext(Dispatchers.IO) {
    try { val b = JSONObject().apply { put("container_name", containerName); put("alert_type", alertType); put("hours", 1) }.toString().toRequestBody("application/json".toMediaType()); httpClient.newCall(Request.Builder().url("$BASE_URL/alerts/snooze").post(b).build()).execute().isSuccessful } catch (_: Exception) { false }
}

// Navigation - 5 tabs
enum class NavTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    COSTS    ("Costs",     Icons.Filled.AttachMoney),
    SERVICES ("Services",  Icons.Filled.Apps),
    ASK      ("Ask",       Icons.Filled.Search),
    ALERTS   ("Alerts",    Icons.Filled.Notifications)
}

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { HomelabDashboardTheme { AppShell() } }
    }
}

@Composable
fun AppShell() {
    var selectedTab by remember { mutableStateOf(NavTab.DASHBOARD) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = NavBg, tonalElevation = 0.dp) {
                NavTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    NavigationBarItem(selected = selected, onClick = { selectedTab = tab }, icon = { Icon(tab.icon, tab.label, tint = if (selected) AccentBlue else TextMuted) }, label = { Text(tab.label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (selected) AccentBlue else TextMuted) }, colors = NavigationBarItemDefaults.colors(indicatorColor = AccentBlue.copy(alpha = 0.12f)))
                }
            }
        },
        containerColor = DarkBg
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                NavTab.DASHBOARD -> DashboardScreen()
                NavTab.COSTS     -> CostsScreen()
                NavTab.SERVICES  -> ServicesScreen()
                NavTab.ASK       -> AskScreen()
                NavTab.ALERTS    -> AlertsScreen()
            }
        }
    }
}

// Dashboard: Agent > Containers > VPS > Costs > PBS
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    var summary          by remember { mutableStateOf<AgentSummary?>(null) }
    var containers       by remember { mutableStateOf<List<ContainerStatus>>(emptyList()) }
    var vpsStatus        by remember { mutableStateOf<VpsStatus?>(null) }
    var pbsStatus        by remember { mutableStateOf<PbsStatus?>(null) }
    var isRefreshing     by remember { mutableStateOf(false) }
    val scope            = rememberCoroutineScope()
    val runningOverrides = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun loadAll() {
        val sd = scope.async { fetchSummary() }
        val cd = scope.async { fetchContainers() }
        val vd = scope.async { fetchVpsStatus() }
        val pd = scope.async { fetchPbsStatus() }
        summary = sd.await(); containers = cd.await(); vpsStatus = vd.await(); pbsStatus = pd.await()
        runningOverrides.clear()
    }

    LaunchedEffect(Unit) { loadAll() }
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { scope.launch { isRefreshing = true; loadAll(); isRefreshing = false } }, state = pullState, modifier = Modifier.fillMaxSize().background(DarkBg)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).statusBarsPadding()) {
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("HOMELAB", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AccentBlue, letterSpacing = 4.sp)
                Text("sevenbirches.co.uk", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
            }
            Spacer(Modifier.height(20.dp))
            AgentStatusCard(summary); Spacer(Modifier.height(12.dp))
            ContainersCard(running = summary?.runningContainers ?: 0, total = summary?.totalContainers ?: 0, containers = containers, runningOverrides = runningOverrides, onOptimisticUpdate = { vmid, nowRunning ->
                runningOverrides[vmid] = nowRunning
                summary = summary?.let { s -> val d = if (nowRunning) 1 else -1; s.copy(runningContainers = (s.runningContainers + d).coerceIn(0, s.totalContainers)) }
            })
            Spacer(Modifier.height(12.dp)); VpsStatusCard(vpsStatus)
            Spacer(Modifier.height(12.dp)); PbsStatusCard(pbsStatus)
            Spacer(Modifier.height(16.dp))
        }
    }
}

// Alerts Screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen() {
    var alerts       by remember { mutableStateOf<List<OpenAlert>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()
    suspend fun reload() { alerts = fetchAlertHistory() }
    LaunchedEffect(Unit) { reload() }
    val pullState = rememberPullToRefreshState()
    val openCount = alerts.count { !it.resolved }

    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { scope.launch { isRefreshing = true; reload(); isRefreshing = false } }, state = pullState, modifier = Modifier.fillMaxSize().background(DarkBg)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).statusBarsPadding()) {
            Spacer(Modifier.height(16.dp)); ScreenHeader("ALERTS", if (openCount > 0) "$openCount open" else "all clear"); Spacer(Modifier.height(20.dp))
            if (alerts.isEmpty()) { DashCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { PulseDot(AccentGreen); Text("No alerts on record", color = AccentGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp) } } }
            else { alerts.forEach { alert -> AlertRow(alert = alert, onAction = { scope.launch { reload() } }); Spacer(Modifier.height(8.dp)) } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AlertRow(alert: OpenAlert, onAction: () -> Unit) {
    val isResolved = alert.resolved; val isSnoozed = alert.snoozedUntil != null && !isResolved
    val sc = when { isResolved -> TextMuted; alert.severity.lowercase() == "critical" -> AccentRed; alert.severity.lowercase() == "warning" -> AccentAmber; else -> AccentBlue }
    var localAck     by remember(alert.containerName, alert.alertType) { mutableStateOf(alert.acknowledged) }
    var localSnoozed by remember(alert.containerName, alert.alertType) { mutableStateOf(isSnoozed) }
    var isBusy       by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()
    DashCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(alert.containerName, color = if (isResolved) TextMuted else TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp)); Text(alert.alertType.replace("_", " ").uppercase(), color = sc, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
                if (alert.firstSeen.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text("Since: ${alert.firstSeen.take(16).replace("T", " ")}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                if (isResolved && alert.lastSeen.isNotEmpty()) { Spacer(Modifier.height(2.dp)); Text("Resolved: ${alert.lastSeen.take(16).replace("T", " ")}", color = TextMuted.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isResolved) StatChip("RESOLVED", TextMuted) else StatChip(alert.severity.uppercase(), sc)
                if (localAck)     StatChip("ACK", AccentGreen)
                if (localSnoozed) StatChip("SNOOZED", AccentAmber)
            }
        }
        if (!isResolved && (!localAck || !localSnoozed)) {
            Spacer(Modifier.height(12.dp)); HorizontalDivider(color = TextMuted.copy(alpha = 0.1f), thickness = 0.5.dp); Spacer(Modifier.height(10.dp))
            if (isBusy) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentBlue, strokeWidth = 2.dp); Text("Updating...", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
            else { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!localAck) AlertActionButton("ACK", AccentGreen, Modifier.weight(1f)) { isBusy = true; scope.launch { if (acknowledgeAlert(alert.containerName, alert.alertType)) localAck = true; isBusy = false; onAction() } }
                if (!localSnoozed) AlertActionButton("SNOOZE 1H", AccentAmber, Modifier.weight(1f)) { isBusy = true; scope.launch { if (snoozeAlert(alert.containerName, alert.alertType)) localSnoozed = true; isBusy = false; onAction() } }
            } }
        }
    }
}

@Composable
fun AlertActionButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.10f)).clickable { onClick() }.padding(vertical = 8.dp)) { Text(label, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp) }
}

// Ask + Simulate screen
@Composable
fun AskScreen() {
    var mode by remember { mutableStateOf("ask") }
    Column(modifier = Modifier.fillMaxSize().background(DarkBg).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ask" to "ASK", "simulate" to "SIMULATE").forEach { (m, label) ->
                val sel = mode == m
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) AccentBlue.copy(alpha = 0.15f) else CardBg).clickable { mode = m }.padding(vertical = 10.dp)) {
                    Text(label, color = if (sel) AccentBlue else TextMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
                }
            }
        }
        if (mode == "ask") AskContentPane() else SimulateContentPane()
    }
}

@Composable
fun AskContentPane() {
    var question   by remember { mutableStateOf("") }
    var isLoading  by remember { mutableStateOf(false) }
    var response   by remember { mutableStateOf<AskResponse?>(null) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    var baselines  by remember { mutableStateOf<BaselineSummary?>(null) }
    var blExpanded by remember { mutableStateOf(false) }
    val scope      = rememberCoroutineScope()
    val suggestions = listOf("What is the current state of my system?", "Why is n8n using so much memory?", "Are there any containers I should be concerned about?", "What has changed since yesterday?")

    LaunchedEffect(Unit) { baselines = fetchBaselines() }

    Column(modifier = Modifier.fillMaxSize().background(DarkBg).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(16.dp)); ScreenHeader("ASK", "Baseline-aware"); Spacer(Modifier.height(20.dp))

        // Baselines summary card - collapsible
        DashCard {
            Row(modifier = Modifier.fillMaxWidth().clickable { blExpanded = !blExpanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulseDot(if (baselines != null) AccentGreen else AccentAmber)
                    Column {
                        SectionLabel("BASELINES")
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (baselines == null) "Loading..." else "${baselines!!.containersBaselined} containers · ${baselines!!.deviationCount} above normal",
                            color = if ((baselines?.deviationCount ?: 0) > 0) AccentAmber else AccentGreen,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatChip("30 DAY", AccentBlue)
                    Icon(if (blExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                }
            }
            if (blExpanded && baselines != null) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = TextMuted.copy(alpha = 0.1f), thickness = 0.5.dp); Spacer(Modifier.height(12.dp))
                if (baselines!!.currentDeviations.isEmpty()) {
                    Text("All containers within normal range", color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    Text("Last computed: ${baselines!!.newestComputed.take(16).replace("T", " ")}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    baselines!!.currentDeviations.forEach { container ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(container.name, color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                container.deviations.forEach { dev ->
                                    val rc = when { dev.ratio >= 4.0 -> AccentRed; dev.ratio >= 2.5 -> AccentAmber; else -> AccentBlue }
                                    StatChip("${dev.metric.uppercase()} ${String.format(Locale.US, "%.1f", dev.ratio)}x", rc)
                                }
                            }
                        }
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.06f), thickness = 0.5.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Question input
        DashCard {
            SectionLabel("QUESTION"); Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = question, onValueChange = { question = it }, placeholder = { Text("Ask about your infrastructure...", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, unfocusedBorderColor = TextMuted.copy(alpha = 0.3f), focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentBlue), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), minLines = 2, maxLines = 4)
            Spacer(Modifier.height(12.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (question.isNotBlank() && !isLoading) AccentBlue.copy(alpha = 0.15f) else TextMuted.copy(alpha = 0.08f)).clickable(enabled = question.isNotBlank() && !isLoading) { scope.launch { isLoading = true; errorMsg = null; response = null; val r = postAsk(question.trim()); if (r != null) response = r else errorMsg = "Failed to get a response -- check agent connectivity"; isLoading = false } }.padding(vertical = 14.dp)) {
                if (isLoading) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentBlue, strokeWidth = 2.dp); Text("Asking Nova...", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 13.sp) } }
                else Text("ASK", color = if (question.isNotBlank()) AccentBlue else TextMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Suggestions
        if (response == null && !isLoading) {
            SectionLabel("SUGGESTIONS"); Spacer(Modifier.height(8.dp))
            suggestions.forEach { s -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(CardBg).clickable { question = s }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text(s, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) } }
        }

        // Response
        response?.let { r ->
            Spacer(Modifier.height(12.dp))
            DashCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("ANSWER")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { StatChip("NOVA MICRO", AccentGreen); if (r.deviationsIncluded > 0) StatChip("${r.deviationsIncluded} baselines", AccentAmber) }
                }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), thickness = 0.5.dp); Spacer(Modifier.height(12.dp))
                Text(r.answer, color = TextPrimary.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = TextMuted.copy(alpha = 0.1f), thickness = 0.5.dp); Spacer(Modifier.height(8.dp))
                Text("${r.inputTokens + r.outputTokens} tokens · ${"$"}${String.format(Locale.US, "%.6f", (r.inputTokens * 0.000000035 + r.outputTokens * 0.000000140))}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TextMuted.copy(alpha = 0.08f)).clickable { response = null; question = "" }.padding(vertical = 12.dp)) { Text("ASK ANOTHER", color = TextMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
        }

        errorMsg?.let { msg -> Spacer(Modifier.height(12.dp)); DashCard { Text(msg, color = AccentRed, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
        Spacer(Modifier.height(32.dp))
    }
}

// Services Screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen() {
    var containers   by remember { mutableStateOf<List<ContainerStatus>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()
    val context      = LocalContext.current

    LaunchedEffect(Unit) { containers = fetchContainers() }
    val pullState = rememberPullToRefreshState()

    val services = listOf(
        ServiceItem("Proxmox",      "https://proxmox.sevenbirches.co.uk",       R.drawable.ic_proxmox,       containerName = null),
        ServiceItem("QNAP",         "https://qnap.sevenbirches.co.uk",          R.drawable.ic_qnap,          containerName = null),
        ServiceItem("Home Assist",  "https://home.sevenbirches.co.uk",          R.drawable.ic_homeassistant, containerName = "haos16.2"),
        ServiceItem("Plex",         "https://plex.sevenbirches.co.uk",          R.drawable.ic_plex,          containerName = "plex"),
        ServiceItem("Sonarr",       "https://sonarr.sevenbirches.co.uk",        R.drawable.ic_sonarr,        containerName = "sonarr"),
        ServiceItem("Radarr",       "https://radarr.sevenbirches.co.uk",        R.drawable.ic_radarr,        containerName = "radarr"),
        ServiceItem("Pi-hole",      "https://pihole.sevenbirches.co.uk",        R.drawable.ic_pihole,        containerName = "pihole"),
        ServiceItem("Terminal",     "https://terminal.sevenbirches.co.uk",      R.drawable.ic_ttyd,          containerName = "ai-lab"),
        ServiceItem("Prowlarr",     "https://prowlarr.sevenbirches.co.uk",      R.drawable.ic_prowlarr,      containerName = "prowlarr"),
        ServiceItem("PBS",          "https://pbs.sevenbirches.co.uk",           R.drawable.ic_pbs,           containerName = "proxmox-backup-server"),
        ServiceItem("SABnzbd",      "https://sabnzbd.sevenbirches.co.uk",       R.drawable.ic_sabnzbd,       containerName = "sabnzbd"),
        ServiceItem("n8n",          "https://n8n.sevenbirches.co.uk",           R.drawable.ic_n8n,           containerName = "n8n"),
        ServiceItem("Portainer",    "https://portainer.sevenbirches.co.uk",     R.drawable.ic_portainer,     containerName = "docker"),
        ServiceItem("Docuseal",     "https://docuseal.sevenbirches.co.uk",      R.drawable.ic_docuseal,      containerName = "docker"),
        ServiceItem("Kapowarr",     "https://kapowarr.sevenbirches.co.uk",      R.drawable.ic_kapowarr,      containerName = "docker"),
        ServiceItem("Suwayomi",     "https://suwa.sevenbirches.co.uk",          R.drawable.ic_suwayomi,      containerName = "docker"),
        ServiceItem("Vert",         "https://vert.sevenbirches.co.uk",          R.drawable.ic_vert,          containerName = "docker"),
        ServiceItem("Stirling PDF", "https://stirling.sevenbirches.co.uk",      R.drawable.ic_stirling,      containerName = "docker"),
        ServiceItem("Vaultwarden",  "https://bitwarden.sevenbirches.co.uk",     R.drawable.ic_vaultwarden,   containerName = "vaultwarden"),
        ServiceItem("Jellyfin",     "https://jellyfin.anthonyapierre.com",      R.drawable.ic_jellyfin,      containerName = null),
        ServiceItem("Jellyseerr",   "https://jellyseer.anthonyapierre.com",     R.drawable.ic_jellyseerr,    containerName = null),
        ServiceItem("OSINT Lab",    "https://osint.sevenbirches.co.uk",         R.drawable.ic_osint,         containerName = "osint-lab")
    )

    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { scope.launch { isRefreshing = true; containers = fetchContainers(); isRefreshing = false } }, state = pullState, modifier = Modifier.fillMaxSize().background(DarkBg)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).statusBarsPadding()) {
            Spacer(Modifier.height(16.dp)); ScreenHeader("SERVICES", "${services.size} apps"); Spacer(Modifier.height(20.dp))
            services.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { service ->
                        val isRunning = when (service.containerName) {
                            null -> true
                            else -> containers.find { it.name == service.containerName }?.running ?: true
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            ServiceCard(service = service, isRunning = isRunning) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.url)))
                            }
                        }
                    }
                    if (row.size == 1) Box(modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ServiceCard(service: ServiceItem, isRunning: Boolean, onClick: () -> Unit) {
    val statusColor = if (isRunning) AccentGreen else AccentRed
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBg), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor).align(Alignment.TopEnd))
            }
            Spacer(Modifier.height(4.dp))
            // Icon - 60dp in 72dp container
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(CardBg)) {
                if (service.iconRes != null) {
                    Image(painter = painterResource(id = service.iconRes), contentDescription = service.name, modifier = Modifier.size(60.dp), contentScale = ContentScale.Fit)
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(service.iconColor.copy(alpha = 0.2f))) {
                        Text(service.iconLetter, color = service.iconColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(service.name, color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(if (isRunning) "ONLINE" else "OFFLINE", color = statusColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        }
    }
}

// Dashboard Cards
@Composable
fun AgentStatusCard(summary: AgentSummary?) {
    DashCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { SectionLabel("AGENT"); Spacer(Modifier.height(4.dp)); Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(if (summary != null) AccentGreen else AccentAmber); Spacer(Modifier.width(8.dp)); Text(if (summary != null) "Online" else "Connecting...", color = if (summary != null) AccentGreen else AccentAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
            Column(horizontalAlignment = Alignment.End) { StatChip("NOVA MICRO", AccentBlue); Spacer(Modifier.height(4.dp)); Text("Alerts: ${summary?.openAlerts ?: "-"}", color = if ((summary?.openAlerts ?: 0) > 0) AccentAmber else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

@Composable
fun PbsStatusCard(pbs: PbsStatus?) {
    val healthy = pbs != null && pbs.staleContainers.isEmpty()
    val sc = if (pbs == null) AccentAmber else if (healthy) AccentGreen else AccentAmber
    var showPbsSheet by remember { mutableStateOf(false) }
    DashCard {
        Box(modifier = Modifier.fillMaxWidth().clickable(enabled = pbs != null) { showPbsSheet = true }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    SectionLabel("PBS BACKUP"); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(sc); Spacer(Modifier.width(8.dp)); Text(if (pbs == null) "Connecting..." else "${pbs.containersBackedUp} / ${pbs.totalContainers} backed up", color = sc, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    if (pbs != null && pbs.staleContainers.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text("Stale: ${pbs.staleContainers.joinToString(", ")}", color = AccentAmber, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
                Column(horizontalAlignment = Alignment.End) { StatChip(pbs?.lastRun?.take(16)?.replace("T", " ") ?: "--:--", sc); Spacer(Modifier.height(4.dp)); Text("Daily 04:00", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
    if (showPbsSheet && pbs != null) PbsDetailSheet(pbs = pbs, onDismiss = { showPbsSheet = false })
}

@Composable
fun ContainersCard(running: Int, total: Int, containers: List<ContainerStatus>, runningOverrides: Map<String, Boolean>, onOptimisticUpdate: (vmid: String, nowRunning: Boolean) -> Unit) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    DashCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { SectionLabel("CONTAINERS"); Text("$running / $total", color = if (running == total && total > 0) AccentGreen else AccentAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
        if (containers.isEmpty()) { Text("Loading...", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        else { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { containers.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { row.forEach { ct -> Box(modifier = Modifier.weight(1f)) { ContainerChip(ct = ct, effectiveRunning = runningOverrides[ct.id] ?: ct.running) { selectedId = ct.id } } }; repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) } } } } }
    }
    selectedId?.let { id -> containers.find { it.id == id }?.let { ct -> val er = runningOverrides[ct.id] ?: ct.running; ContainerDetailSheet(ct = if (er != ct.running) ct.copy(running = er) else ct, onDismiss = { selectedId = null }, onActionSuccess = { vmid, nowRunning -> onOptimisticUpdate(vmid, nowRunning) }) } }
}

@Composable
fun ContainerChip(ct: ContainerStatus, effectiveRunning: Boolean, onClick: () -> Unit) {
    val color = if (effectiveRunning) AccentGreen else AccentRed
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.08f)).clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.height(4.dp))
        Text(ct.id, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(ct.name, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun VpsStatusCard(vps: VpsStatus?) {
    var showSheet by remember { mutableStateOf(false) }
    val sc = when { vps == null -> AccentAmber; vps.runningContainers == vps.totalContainers -> AccentGreen; else -> AccentRed }
    val dc = when { vps == null -> TextMuted; vps.diskPercent >= 90 -> AccentRed; vps.diskPercent >= 75 -> AccentAmber; else -> AccentGreen }
    DashCard {
        Box(modifier = Modifier.fillMaxWidth().clickable(enabled = vps != null) { showSheet = true }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { SectionLabel("ROMANIAN VPS"); Spacer(Modifier.height(4.dp)); Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(sc); Spacer(Modifier.width(8.dp)); Text(if (vps == null) "Connecting..." else "${vps.runningContainers} / ${vps.totalContainers} containers", color = sc, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
                Column(horizontalAlignment = Alignment.End) { vps?.let { StatChip("DISK ${it.diskPercent}%", dc); Spacer(Modifier.height(4.dp)); Text("MEM ${it.memoryPercent}%", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } ?: StatChip("---", TextMuted) }
            }
        }
    }
    if (showSheet && vps != null) VpsDetailSheet(vps = vps, onDismiss = { showSheet = false })
}

// Bottom Sheets
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpsDetailSheet(vps: VpsStatus, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CardBg) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("ROMANIAN VPS", color = AccentBlue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp); Text("94.156.152.232", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                StatChip("ONLINE", AccentGreen)
            }
            Spacer(Modifier.height(20.dp)); MetricRow("DISK", vps.diskPercent.toDouble()); Spacer(Modifier.height(12.dp)); MetricRow("MEMORY", vps.memoryPercent)
            Spacer(Modifier.height(20.dp)); SectionLabel("DOCKER CONTAINERS"); Spacer(Modifier.height(12.dp))
            vps.containers.forEach { ct ->
                val color = if (ct.running) AccentGreen else AccentRed
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(10.dp)); Text(ct.name, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) }
                    StatChip(if (ct.running) "UP" else "DOWN", color)
                }
                HorizontalDivider(color = TextMuted.copy(alpha = 0.1f), thickness = 0.5.dp)
            }
            Spacer(Modifier.height(12.dp)); Text("Last updated: ${vps.timestamp.take(19).replace("T", " ")}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp); Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailSheet(ct: ContainerStatus, onDismiss: () -> Unit, onActionSuccess: (vmid: String, nowRunning: Boolean) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope      = rememberCoroutineScope()
    var pendingAction   by remember { mutableStateOf("") }
    var showConfirm     by remember { mutableStateOf(false) }
    var isActioning     by remember { mutableStateOf(false) }
    var actionResultMsg by remember { mutableStateOf<String?>(null) }
    var actionSuccess   by remember { mutableStateOf(false) }

    if (showConfirm) {
        val ac = when (pendingAction) { "start" -> AccentGreen; "stop" -> AccentRed; "reboot" -> AccentAmber; else -> AccentBlue }
        AlertDialog(onDismissRequest = { showConfirm = false }, containerColor = CardBg,
            title = { Text("${pendingAction.uppercase()} ${ct.id}?", color = ac, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Column { Text(ct.name, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp); if (pendingAction in listOf("stop", "reboot")) { Spacer(Modifier.height(8.dp)); Text("A snapshot will be attempted before this action.", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) } } },
            confirmButton = { TextButton(onClick = { showConfirm = false; isActioning = true; actionResultMsg = null; scope.launch { val (success, msg) = containerAction(ct.id, pendingAction); actionSuccess = success; actionResultMsg = msg; isActioning = false; if (success) { val nr = when (pendingAction) { "start" -> true; "stop" -> false; else -> true }; onActionSuccess(ct.id, nr) } } }) { Text("Confirm", color = ac, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", color = TextMuted, fontFamily = FontFamily.Monospace) } })
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CardBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text(ct.id, color = if (ct.running) AccentGreen else AccentRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text(ct.name, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                StatChip(if (ct.running) "RUNNING" else "STOPPED", if (ct.running) AccentGreen else AccentRed)
            }
            Spacer(Modifier.height(24.dp)); MetricRow("CPU", ct.cpuPercent); Spacer(Modifier.height(12.dp)); MetricRow("MEMORY", ct.memPercent); Spacer(Modifier.height(12.dp)); MetricRow("DISK", ct.diskPercent)
            Spacer(Modifier.height(24.dp)); HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), thickness = 0.5.dp); Spacer(Modifier.height(16.dp)); SectionLabel("ACTIONS"); Spacer(Modifier.height(12.dp))
            if (isActioning) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentBlue, strokeWidth = 2.dp); Text("Sending $pendingAction command...", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
            else { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) { if (!ct.running) { ActionButton("START", AccentGreen, Modifier.weight(1f)) { pendingAction = "start"; showConfirm = true } } else { ActionButton("STOP", AccentRed, Modifier.weight(1f)) { pendingAction = "stop"; showConfirm = true }; ActionButton("REBOOT", AccentAmber, Modifier.weight(1f)) { pendingAction = "reboot"; showConfirm = true } } } }
            actionResultMsg?.let { msg -> Spacer(Modifier.height(12.dp)); Text(msg, color = if (actionSuccess) AccentGreen else AccentRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
            Spacer(Modifier.height(16.dp)); Text("Last updated: ${ct.timestamp.take(19).replace("T", " ")}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp); Spacer(Modifier.height(32.dp))
        }
    }
}

// Shared components
@Composable
fun ActionButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).clickable { onClick() }.padding(vertical = 12.dp)) { Text(label, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp) }
}

@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AccentBlue, letterSpacing = 4.sp)
        Text(subtitle, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
    }
}

@Composable
fun MetricRow(label: String, value: Double) {
    val color = when { value >= 90.0 -> AccentRed; value >= 70.0 -> AccentAmber; else -> AccentGreen }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp); Text("${String.format(Locale.US, "%.1f", value)}%", color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.15f))) { Box(modifier = Modifier.fillMaxWidth(fraction = (value / 100.0).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color)) }
    }
}

@Composable
fun DashCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBg), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) { Column(modifier = Modifier.padding(16.dp), content = content) }
}

@Composable
fun SectionLabel(text: String) { Text(text, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp) }

@Composable
fun StatChip(text: String, color: Color) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun PulseDot(color: Color) {
    val it = rememberInfiniteTransition(label = "pulse")
    val os by it.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1200, easing = EaseOut), RepeatMode.Restart), label = "os")
    val oa by it.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1200, easing = EaseOut), RepeatMode.Restart), label = "oa")
    val ins by it.animateFloat(0.85f, 1f, infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse), label = "ins")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        Box(modifier = Modifier.size(12.dp).scale(os).clip(CircleShape).background(color.copy(alpha = oa)))
        Box(modifier = Modifier.size(10.dp).scale(ins).clip(CircleShape).background(color))
    }
}
// =============================================================================
// APPEND THIS ENTIRE FILE to the bottom of MainActivity.kt
// In Android Studio: Ctrl+End to go to end of file, then paste
// =============================================================================

// Simulation data classes
data class SimSummaryData(
    val containersRunning: Int, val containersStopped: Int,
    val alertsCritical: Int, val alertsWarning: Int, val actionsProposed: Int
)
data class SimAlertData(val container: String, val type: String, val severity: String, val message: String)
data class SimActionData(val container: String, val action: String, val gate: String, val reason: String, val wouldSnapshot: Boolean)
data class SimResult(val summary: SimSummaryData, val alerts: List<SimAlertData>, val actions: List<SimActionData>, val aiNarrative: String?)

suspend fun fetchSimulation(query: String, aiEnabled: Boolean): SimResult = withContext(Dispatchers.IO) {
    val body = JSONObject().apply { put("query", query); put("ai", aiEnabled) }
        .toString().toRequestBody("application/json".toMediaType())
    val rs = httpClient.newCall(Request.Builder().url("$BASE_URL/simulate").post(body).build())
        .execute().body?.string() ?: throw Exception("No response from server")
    val j = JSONObject(rs)
    if (j.has("error")) throw Exception(j.getString("error"))
    val sum = j.getJSONObject("summary")
    val alerts = j.getJSONArray("alerts").let { arr ->
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { a ->
            SimAlertData(a.optString("container"), a.optString("type"), a.optString("severity"), a.optString("message"))
        }}
    }
    val actions = j.getJSONArray("actions").let { arr ->
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { a ->
            SimActionData(a.optString("container"), a.optString("action"), a.optString("gate"), a.optString("reason"), a.optBoolean("would_snapshot"))
        }}
    }
    SimResult(
        SimSummaryData(sum.optInt("containers_running"), sum.optInt("containers_stopped"),
            sum.optInt("alerts_critical"), sum.optInt("alerts_warning"), sum.optInt("actions_proposed")),
        alerts, actions,
        j.optString("ai_narrative").takeIf { it.isNotBlank() && it != "null" }
    )
}

// Costs Screen — full-screen breakdown replacing the dashboard card
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostsScreen() {
    var costs        by remember { mutableStateOf<List<CostEntry>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()
    LaunchedEffect(Unit) { costs = fetchCosts() }
    val pullState    = rememberPullToRefreshState()
    val total        = costs.sumOf { it.costUsd }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh    = { scope.launch { isRefreshing = true; costs = fetchCosts(); isRefreshing = false } },
        state        = pullState,
        modifier     = Modifier.fillMaxSize().background(DarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).statusBarsPadding()) {
            Spacer(Modifier.height(16.dp))
            ScreenHeader("COSTS", "AWS Bedrock")
            Spacer(Modifier.height(20.dp))
            // Monthly rollup chips
            if (costs.isNotEmpty()) {
                val byMonth = costs.groupBy { it.date.take(7) }.entries.sortedByDescending { it.key }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    byMonth.forEach { (month, entries) ->
                        val mc = entries.sumOf { it.costUsd }
                        Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CardBg).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(month, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            Text("${"$"}${String.format(Locale.US, "%.4f", mc)}", color = AccentBlue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${entries.sumOf { it.calls }} calls", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            DashCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        SectionLabel("MONTH TO DATE")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (costs.isEmpty()) "Loading..." else "${"$"}${String.format(Locale.US, "%.4f", total)}",
                            color = AccentBlue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${costs.sumOf { it.calls }} calls", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("${costs.sumOf { it.totalInputTokens + it.totalOutputTokens }} tokens", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (costs.isEmpty()) {
                DashCard { Text("Loading...", color = TextMuted, fontFamily = FontFamily.Monospace) }
            } else {
                costs.groupBy { it.date }.entries.sortedByDescending { it.key }.forEach { (date, entries) ->
                    val dt = entries.sumOf { it.costUsd }
                    DashCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(date, color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${"$"}${String.format(Locale.US, "%.6f", dt)}", color = AccentGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        entries.forEach { entry ->
                            val ms = when {
                                entry.modelId.contains("nova-pro")   -> "NOVA PRO"
                                entry.modelId.contains("nova-lite")  -> "NOVA LITE"
                                entry.modelId.contains("nova-micro") -> "NOVA MICRO"
                                else -> "UNKNOWN"
                            }
                            val mc = when {
                                entry.modelId.contains("nova-pro")  -> AccentRed
                                entry.modelId.contains("nova-lite") -> AccentAmber
                                else -> AccentGreen
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatChip(ms, mc)
                                    Text("${entry.calls} calls · ${entry.totalInputTokens + entry.totalOutputTokens} tok", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                                Text("${"$"}${String.format(Locale.US, "%.6f", entry.costUsd)}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// Simulate content pane — shown when mode == "simulate" inside AskScreen
@Composable
fun SimulateContentPane() {
    val scope     = rememberCoroutineScope()
    var query     by remember { mutableStateOf("") }
    var aiEnabled by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var result    by remember { mutableStateOf<SimResult?>(null) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    val presets = listOf(
        "CT110 OOM"      to "What happens if sabnzbd memory hits 96%?",
        "VPS down"       to "The Romanian VPS becomes unreachable",
        "NAS power loss" to "All NFS mounts go down simultaneously",
        "Cascade stress" to "CT110 OOM and docker host thrashing at the same time",
        "Monitoring gap" to "Both cloudflared and the agent container stop",
        "Disk pressure"  to "Docker host disk reaches 88%"
    )

    fun runSim(q: String) {
        if (q.isBlank() || isLoading) return
        isLoading = true; errorMsg = null; result = null
        scope.launch {
            try { result = fetchSimulation(q.trim(), aiEnabled) }
            catch (e: Exception) { errorMsg = e.message ?: "Request failed" }
            finally { isLoading = false }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("PRESETS")
        Spacer(Modifier.height(8.dp))
        presets.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, q) ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentBlue.copy(alpha = 0.08f))
                            .clickable { query = q; runSim(q) }
                            .padding(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text(label, color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        DashCard {
            SectionLabel("WHAT-IF QUERY")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Ask a what-if question...", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue, unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentBlue
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                minLines = 1, maxLines = 3
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Switch(
                        checked = aiEnabled, onCheckedChange = { aiEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AccentBlue, checkedTrackColor = AccentBlue.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted, uncheckedTrackColor = CardBg
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                    Text("AI analysis", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (query.isNotBlank() && !isLoading) AccentBlue.copy(alpha = 0.15f) else TextMuted.copy(alpha = 0.08f))
                        .clickable(enabled = query.isNotBlank() && !isLoading) { runSim(query) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    if (isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentBlue, strokeWidth = 2.dp)
                            Text("Running...", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    } else {
                        Text("RUN", color = if (query.isNotBlank()) AccentBlue else TextMuted,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
                    }
                }
            }
        }
        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            DashCard { Text(it, color = AccentRed, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        result?.let { res ->
            Spacer(Modifier.height(12.dp))
            // Summary strip
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Running",  res.summary.containersRunning,  AccentGreen),
                    Triple("Stopped",  res.summary.containersStopped,  if (res.summary.containersStopped  > 0) AccentAmber else TextMuted),
                    Triple("Critical", res.summary.alertsCritical,     if (res.summary.alertsCritical     > 0) AccentRed   else TextMuted),
                    Triple("Actions",  res.summary.actionsProposed,    if (res.summary.actionsProposed    > 0) AccentBlue  else TextMuted)
                ).forEach { (label, value, color) ->
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(CardBg).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(value.toString(), color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(label, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Alerts
            if (res.alerts.isEmpty()) {
                DashCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseDot(AccentGreen)
                        Text("No alerts would fire", color = AccentGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                res.alerts.forEach { alert ->
                    val sc = if (alert.severity == "critical") AccentRed else AccentAmber
                    DashCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Text(alert.message, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatChip(alert.severity.uppercase(), sc)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // Actions
            if (res.actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("PROPOSED ACTIONS")
                Spacer(Modifier.height(8.dp))
                res.actions.forEach { action ->
                    val gc = when (action.gate) {
                        "circuit_breaker" -> AccentRed
                        "approval"        -> AccentAmber
                        "auto"            -> AccentGreen
                        else              -> TextMuted
                    }
                    DashCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(action.action.replace("_", " "), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("→ ${action.container}", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Text(action.reason, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatChip(action.gate.replace("_", " "), gc)
                                if (action.wouldSnapshot) Text("📸 snapshot", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // AI narrative
            res.aiNarrative?.let { narrative ->
                Spacer(Modifier.height(8.dp))
                DashCard {
                    SectionLabel("AI ANALYSIS")
                    Spacer(Modifier.height(8.dp))
                    Text(narrative, color = TextPrimary.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
// =============================================================================
// APPEND THIS to the bottom of MainActivity.kt (after the previous additions)
// =============================================================================

// PBS Detail Sheet — shows ALL containers with their last backup date
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PbsDetailSheet(pbs: PbsStatus, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val healthy    = pbs.staleContainers.isEmpty()
    val sc         = if (healthy) AccentGreen else AccentAmber

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = CardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "PBS BACKUP",
                        color      = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 18.sp,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Last run: ${pbs.lastRun.take(16).replace("T", " ")}",
                        color    = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                StatChip(
                    if (healthy) "ALL CURRENT" else "${pbs.staleContainers.size} STALE",
                    sc
                )
            }
            Spacer(Modifier.height(20.dp))

            // Summary row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentGreen.copy(alpha = 0.08f))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        pbs.containersBackedUp.toString(),
                        color      = AccentGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 24.sp
                    )
                    Text("backed up", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pbs.staleContainers.isNotEmpty()) AccentAmber.copy(alpha = 0.08f) else CardBg)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        pbs.staleContainers.size.toString(),
                        color      = if (pbs.staleContainers.isNotEmpty()) AccentAmber else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 24.sp
                    )
                    Text("stale", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardBg)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        pbs.totalContainers.toString(),
                        color      = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 24.sp
                    )
                    Text("total", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f), thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))
            SectionLabel("ALL CONTAINERS")
            Spacer(Modifier.height(12.dp))

            // All containers — we reconstruct from what PbsStatus carries.
            // Stale ones are highlighted, the rest shown as current.
            // Show stale containers first, then current count
            if (pbs.staleContainers.isNotEmpty()) {
                pbs.staleContainers.forEach { name ->
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentAmber))
                            Text(name, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        StatChip("STALE", AccentAmber)
                    }
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.08f), thickness = 0.5.dp)
                }
            }

            // Current containers count
            val currentCount = pbs.containersBackedUp - pbs.staleContainers.size
            if (currentCount > 0) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentGreen))
                        Text(
                            "$currentCount containers",
                            color      = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp
                        )
                    }
                    StatChip("CURRENT", AccentGreen)
                }
                Text(
                    "Backed up on ${pbs.lastRunDate}",
                    color    = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}