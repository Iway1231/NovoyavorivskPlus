package com.iway1231.novoyavorivskplus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iway1231.novoyavorivskplus.api.ApiClient
import com.iway1231.novoyavorivskplus.ui.theme.НовояворівськTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        ApiClient.initialize(this)
        BackgroundRefreshReceiver.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        requestBatteryOptimizationExemption()
        setContent {
            var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
            НовояворівськTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(darkTheme = darkTheme, onToggleTheme = {
                        darkTheme = !darkTheme
                        preferences.edit().putBoolean("dark_theme", darkTheme).apply()
                    })
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName) ||
            preferences.getBoolean("battery_optimization_prompted", false)
        ) return
        preferences.edit().putBoolean("battery_optimization_prompted", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}

private data class SavedLocation(val groupId: String, val address: String, val nickname: String = address, val groupLabel: String = groupId)

private fun loadSavedLocations(preferences: android.content.SharedPreferences): List<SavedLocation> {
    val stored = preferences.getString("saved_locations", "").orEmpty()
    val parsed = stored.split("||").mapNotNull { item ->
        val parts = item.split("::", limit = 4)
        if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) SavedLocation(parts[0], parts[1], parts.getOrNull(2).orEmpty().ifBlank { parts[1] }, parts.getOrNull(3).orEmpty().ifBlank { parts[0] }) else null
    }
    if (parsed.isNotEmpty()) return parsed
    val oldAddress = preferences.getString("selected_address", "").orEmpty()
    val oldGroup = preferences.getString("naftogaz_group", "").orEmpty()
    return if (oldAddress.isNotBlank() && oldGroup.isNotBlank()) listOf(SavedLocation(oldGroup, oldAddress, oldAddress, oldGroup)) else emptyList()
}

private fun saveLocations(preferences: android.content.SharedPreferences, locations: List<SavedLocation>) {
    preferences.edit().putString("saved_locations", locations.joinToString("||") { "${it.groupId}::${it.address}::${it.nickname}" }).apply()
}

private fun scheduleSummary(section: JsonObject?, now: LocalDateTime = LocalDateTime.now()): String {
    if (section == null) return ""
    val date = section.get("date")?.takeUnless { it.isJsonNull }?.asString?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return "Графік ще не опубліковано"
    val intervals = section.get("intervals")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val item = element.asJsonObject
        val start = intervalMinute(item.get("from_time")?.takeUnless { it.isJsonNull }?.asString) ?: return@mapNotNull null
        val end = intervalMinute(item.get("to_time")?.takeUnless { it.isJsonNull }?.asString) ?: return@mapNotNull null
        val status = item.get("status")?.takeUnless { it.isJsonNull }?.asString
        if (status == "ON" || end <= start) null else start to end
    }?.sortedBy { it.first }.orEmpty()
    if (intervals.isEmpty()) return "Відключень сьогодні немає"
    if (date.isAfter(now.toLocalDate())) {
        val next = intervals.first()
        val nextDateTime = date.atStartOfDay().plusMinutes(next.first.toLong())
        val minutesUntil = Duration.between(now, nextDateTime).toMinutes().toInt()
        return "До відключення: ${formatCountdown(minutesUntil)}"
    }
    if (date.isBefore(now.toLocalDate())) return "Графік за цю дату вже минув"
    val minuteNow = now.hour * 60 + now.minute
    val current = intervals.firstOrNull { minuteNow in it.first until it.second }
    val next = current ?: intervals.firstOrNull { it.first > minuteNow }
    if (current != null) return "До ввімкнення: ${formatCountdown(current.second - minuteNow)}"
    if (next != null) return "До відключення: ${formatCountdown(next.first - minuteNow)}"
    return "Відключень до кінця дня немає"
}

private fun formatCountdown(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val mins = safe % 60
    return when {
        hours > 0 && mins > 0 -> "$hours год $mins хв"
        hours > 0 -> "$hours год"
        else -> "$mins хв"
    }
}
private const val CACHE_REFRESH_MS = 10 * 60 * 1000L
private const val LINKEDIN_URL = "https://www.linkedin.com/in/ivan-demian-tuchapets-633083158/?isSelfProfile=true"

private fun cachedJson(preferences: android.content.SharedPreferences, key: String): JsonObject? =
    runCatching { preferences.getString(key, null)?.let { JsonParser.parseString(it).asJsonObject } }.getOrNull()

@Composable
private fun HomeScreen(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var savedLocations by remember { mutableStateOf(loadSavedLocations(preferences)) }
    var selectedGroup by remember { mutableStateOf(savedLocations.firstOrNull()?.groupId ?: preferences.getString("naftogaz_group", "2.1") ?: "2.1") }
    var groups by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var home by remember { mutableStateOf<JsonObject?>(null) }
    var powerStatus by remember { mutableStateOf<JsonObject?>(null) }
    var loadingGroups by remember { mutableStateOf(true) }
    var loadingHome by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var addressDialogOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var loeDialogOpen by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf(savedLocations.firstOrNull()?.address ?: preferences.getString("selected_address", "") ?: "") }
    var showDetails by remember { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<SavedLocation?>(null) }
    var nicknameDraft by remember { mutableStateOf("") }
    var detailLocation by remember { mutableStateOf<SavedLocation?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    suspend fun loadHome(group: String, forceRefresh: Boolean = false, allowNetwork: Boolean = true) {
        val isLoe = group.startsWith("loe:")
        val apiGroup = group.removePrefix("loe:")
        val cacheId = (if (isLoe) "loe_" else "naftogaz_") + apiGroup.replace(".", "_")
        cachedJson(preferences, "cache_home_$cacheId")?.let { cachedHome ->
            val cachedTime = preferences.getLong("cache_home_time_$cacheId", 0L)
            cachedHome.getAsJsonArray("sections")?.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
            }?.asJsonObject?.let { section ->
                if (cachedTime > 0L) {
                    section.addProperty("_cache_updated_at", cachedTime)
                }
            }
            home = cachedHome
        }
        cachedJson(preferences, "cache_power")?.let { powerStatus = it }
        loadingHome = home == null
        error = null
        val cachedHomeTime = preferences.getLong("cache_home_time_$cacheId", 0L)
        val cacheIsFresh = !forceRefresh && cachedHomeTime > 0L &&
            System.currentTimeMillis() - cachedHomeTime < CACHE_REFRESH_MS
        loadingHome = home == null
        error = null
        if (cacheIsFresh) {
            loadingHome = false
        } else if (allowNetwork) try {
            val freshHome = ApiClient.api.getMobileHome(operator = if (isLoe) "loe" else "naftogaz", group = apiGroup)
            val updatedAt = System.currentTimeMillis()
            freshHome.getAsJsonArray("sections")?.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
            }?.asJsonObject?.addProperty("_cache_updated_at", updatedAt)
            home = freshHome
            preferences.edit().remove("cache_home_old_$cacheId")
                .putString("cache_home_$cacheId", freshHome.toString())
                .putLong("cache_home_time_$cacheId", updatedAt)
                .apply()
        } catch (e: Exception) {
            if (home == null) error = e.message ?: "Не вдалося завантажити дані"
        }
        val cachedPowerTime = preferences.getLong("cache_power_time", 0L)
        val powerCacheIsFresh = !forceRefresh && cachedPowerTime > 0L &&
            System.currentTimeMillis() - cachedPowerTime < CACHE_REFRESH_MS
        if (allowNetwork && !powerCacheIsFresh) {
            powerStatus = try {
                val freshPower = ApiClient.api.getPowerStatus()
                preferences.edit().putString("cache_power", freshPower.toString()).putLong("cache_power_time", System.currentTimeMillis()).apply()
                freshPower
            } catch (_: Exception) {
                powerStatus
            }
        }
        val electricitySection = home?.getAsJsonArray("sections")?.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
        }?.asJsonObject
        if (electricitySection != null) {
            val preparedSection = addressSpecificElectricity(electricitySection, powerStatus, selectedAddress, group)
            OutageNotificationScheduler.schedule(context, preparedSection, selectedAddress)
        }
        loadingHome = false
    }

    suspend fun refreshAllGroups(forceRefresh: Boolean = false) {
        val groupIds = (savedLocations.map { it.groupId } + selectedGroup).filter { it.isNotBlank() }.distinct()
        groupIds.forEach { groupId -> loadHome(groupId, forceRefresh = forceRefresh) }
        if (selectedGroup.isNotBlank()) loadHome(selectedGroup, allowNetwork = false)
    }
    LaunchedEffect(Unit) {
        runCatching { preferences.getString("cache_groups", null)?.let { JsonParser.parseString(it).asJsonArray.map { item -> item.asJsonObject } } }
            .getOrNull()?.let { groups = it }
        try {
            groups = ApiClient.api.getNaftogazGroups()
            preferences.edit().putString("cache_groups", groups.joinToString(prefix = "[", postfix = "]") { it.toString() }).putLong("cache_groups_time", System.currentTimeMillis()).apply()
            if (!selectedGroup.startsWith("loe:") && groups.none { it.get("id")?.asString == selectedGroup } && groups.isNotEmpty()) {
                selectedGroup = groups.first().get("id").asString
                preferences.edit().putString("naftogaz_group", selectedGroup).apply()
            }
        } catch (e: Exception) {
            error = "Не вдалося завантажити список груп: ${e.message ?: "перевірте з’єднання"}"
        } finally {
            loadingGroups = false
        }
        loadHome(selectedGroup)
    }

    LaunchedEffect(savedLocations.map { it.groupId }.distinct().joinToString("|")) {
        while (true) {
            delay(CACHE_REFRESH_MS)
            loadHome(selectedGroup)
        }
    }

    detailLocation?.let { location ->
        LocationDetailsScreen(
            location = location,
            home = home,
            powerStatus = powerStatus,
            loading = loadingHome,
            darkTheme = darkTheme,
            cacheUpdatedAt = detailLocation?.let { item ->
                val cacheId = (if (item.groupId.startsWith("loe:")) "loe_" else "naftogaz_") + item.groupId.removePrefix("loe:").replace(".", "_")
                preferences.getLong("cache_home_time_$cacheId", 0L).takeIf { it > 0L }
            },
            onBack = { detailLocation = null; showDetails = false }
        )
        return
    }

        if (menuOpen) {
            MenuScreen(
                darkTheme = darkTheme,
                onBack = { menuOpen = false },
                onSupport = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://send.monobank.ua/jar/8bNZg1fE7A"))) } },
                onAbout = { menuOpen = false; aboutOpen = true },
                onContact = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:wanna007pro@gmail.com"))) } },
                onToggleTheme = onToggleTheme,
                onTestNotification = { OutageNotificationReceiver.showTest(context) },
                onRefresh = { scope.launch { refreshAllGroups(forceRefresh = true) } }
            )
            return
        }
        if (aboutOpen) {
            AboutScreen(onBack = { aboutOpen = false; menuOpen = true })
            return
        }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Новояворівськ+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Сервіси міста поруч", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Box(Modifier.size(56.dp).clickable { addressDialogOpen = true }, contentAlignment = Alignment.Center) {
                    Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.size(56.dp).clickable { menuOpen = true }, contentAlignment = Alignment.Center) {
                    Text("☰", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {        if (savedLocations.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("МОЇ АДРЕСИ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Text("+ Додати", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { addressDialogOpen = true })
                    }
                    Spacer(Modifier.height(10.dp))
                    savedLocations.forEach { location ->                        val locationCacheId = (if (location.groupId.startsWith("loe:")) "loe_" else "naftogaz_") + location.groupId.removePrefix("loe:").replace(".", "_")
                        val locationHome = cachedJson(preferences, "cache_home_$locationCacheId")
                        val locationSection = locationHome?.getAsJsonArray("sections")?.firstOrNull {
                            it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
                        }?.asJsonObject?.let {
                            addressSpecificElectricity(it, powerStatus, location.address, location.groupId)
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            selectedGroup = location.groupId
                            selectedAddress = location.address
                            showDetails = true
                            detailLocation = location
                            scope.launch { loadHome(location.groupId, allowNetwork = false) }
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (location.address == selectedAddress) "●" else "○", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.padding(horizontal = 5.dp))
                            Column(Modifier.weight(1f)) {
                                Text(location.nickname, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(location.address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                val summary = scheduleSummary(locationSection)
                                if (summary.isNotBlank()) Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("✎", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                                editingLocation = location
                                nicknameDraft = location.nickname
                            }.padding(8.dp))
                            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable {
                                val updated = savedLocations.filterNot { it.address == location.address }
                                savedLocations = updated
                                saveLocations(preferences, updated)
                                if (location.address == selectedAddress) {
                                    val next = updated.firstOrNull()
                                    selectedAddress = next?.address.orEmpty()
                                    selectedGroup = next?.groupId ?: selectedGroup
                                    showDetails = false
                                }
                            }.padding(8.dp))
                        }
                    }
                }
            }
        }

        if (savedLocations.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { addressDialogOpen = true }.padding(20.dp)) {
                        Text("+", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Додайте першу адресу", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { addressDialogOpen = true }) { Text("Додати адресу") }
                }
            }
        }
        if (showDetails && savedLocations.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("МОЯ АДРЕСА", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    selectedAddress.ifBlank { "Адресу ще не вибрано" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { addressDialogOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedAddress.isBlank()) "Обрати адресу" else "Змінити адресу")
                }
            }
        }

        }
        if (showDetails && savedLocations.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                    Text("ГРУПА ВІДКЛЮЧЕНЬ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Група ${displayGroup(selectedGroup)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (expanded) "▲" else "⌄", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    if (loadingGroups) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    else groups.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                val id = item.get("id")?.asString ?: return@forEach
                                Button(
                                    onClick = {
                                        selectedGroup = id
                                        preferences.edit().putString("naftogaz_group", id).apply()
                                        expanded = false
                                        scope.launch { loadHome(id) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (id == selectedGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (id == selectedGroup) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) { Text(id) }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Text("Стан послуг", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        if (loadingHome && home == null) {
            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        val sections = home?.getAsJsonArray("sections")
        sections?.forEach { element ->
            val sourceSection = element.asJsonObject
            val section = if (sourceSection.get("id")?.asString == "electricity" && selectedAddress.isNotBlank()) {
                addressSpecificElectricity(sourceSection, powerStatus, selectedAddress, selectedGroup)
            } else {
                sourceSection
            }
            ServiceCard(section, darkTheme)
        }
        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F0)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(error ?: "", color = Color(0xFF9F3028), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Спробувати ще раз", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { scope.launch { loadHome(selectedGroup, forceRefresh = true) } })
                }
            }
        }
        if (loadingHome && home != null) Text("Оновлюємо дані…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (addressDialogOpen) {
        AddressSelectionDialog(
            groups = groups,
            onDismiss = { addressDialogOpen = false },
            onLoeRequest = { addressDialogOpen = false; loeDialogOpen = true },
            onAddressSelected = { group, address ->
                selectedGroup = group
                selectedAddress = address
                showDetails = false
                detailLocation = null
                savedLocations = (savedLocations.filterNot { it.address == address } + SavedLocation(group, address, address, group))
                saveLocations(preferences, savedLocations)
                preferences.edit()
                    .putString("naftogaz_group", group)
                    .putString("selected_address", address)
                    .apply()
                addressDialogOpen = false
                scope.launch { loadHome(group) }
            }
        )
    }

    if (loeDialogOpen) {
        LoeAddressDialog(
            onDismiss = { loeDialogOpen = false },
            onAddressSelected = { group, address, groupLabel ->
                selectedGroup = "loe:" + group
                selectedAddress = address
                showDetails = false
                detailLocation = null
                savedLocations = (savedLocations.filterNot { it.address == address } + SavedLocation("loe:" + group, address, address, groupLabel))
                saveLocations(preferences, savedLocations)
                preferences.edit().putString("selected_address", address).apply()
                loeDialogOpen = false
                scope.launch { loadHome("loe:" + group) }
            }
        )
    }

    editingLocation?.let { location ->
        AlertDialog(
            onDismissRequest = { editingLocation = null },
            title = { Text("Назва адреси") },
            text = {
                OutlinedTextField(
                    value = nicknameDraft,
                    onValueChange = { nicknameDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Наприклад: Дача або Мій дім") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = nicknameDraft.trim().ifBlank { location.address }
                    savedLocations = savedLocations.map { item ->
                        if (item.address == location.address) item.copy(nickname = name) else item
                    }
                    saveLocations(preferences, savedLocations)
                    editingLocation = null
                }) { Text("Зберегти") }
            },
            dismissButton = {
                Button(onClick = { editingLocation = null }) { Text("Скасувати") }
            }
        )
    }
}
}
@Composable
private fun LoeAddressDialog(
    onDismiss: () -> Unit,
    onAddressSelected: (String, String, String) -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    var streets by remember { mutableStateOf<List<String>>(emptyList()) }
    var buildings by remember { mutableStateOf<List<String>>(emptyList()) }
    var city by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        cities = runCatching { loeValues(ApiClient.api.getLoeCities(), "cities").filter { it in loeOtgCities }.sorted() }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(city) {
        if (city.isBlank()) return@LaunchedEffect
        loading = true
        streets = runCatching { loeValues(ApiClient.api.getLoeStreets(city), "streets").sorted() }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(street) {
        if (city.isBlank() || street.isBlank()) return@LaunchedEffect
        loading = true
        buildings = runCatching { loeValues(ApiClient.api.getLoeBuildings(city, street), "buildings").sortedWith(buildingComparator) }.getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "Оберіть населений пункт" else if (step == 1) "Оберіть вулицю" else "Оберіть номер будинку") },
        text = {
            Column {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пошук") })
                Spacer(Modifier.height(8.dp))
                when (step) {
                    0 -> LazyColumn(Modifier.height(340.dp)) {
                        items(cities.filter { query.isBlank() || it.contains(query, true) }) { item ->
                            Button({ city = item; street = ""; query = ""; step = 1 }, Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(item) }
                        }
                    }
                    1 -> LazyColumn(Modifier.height(340.dp)) {
                        items(streets.filter { query.isBlank() || it.contains(query, true) }) { item ->
                            Button({ street = item; query = ""; step = 2 }, Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(item) }
                        }
                    }
                    else -> LazyColumn(Modifier.height(340.dp)) {
                        items(buildings.filter { query.isBlank() || it.contains(query, true) }) { item ->
                            Button(onClick = {
                                scope.launch {
                                    val result = runCatching { ApiClient.api.lookupLoe(city, street, item) }.getOrNull()
                                    val loe = result?.get("loe")?.takeIf { it.isJsonObject }?.asJsonObject
                                    val gpv = loe?.get("gpv")?.takeUnless { it.isJsonNull }?.asString
                                    val gav = loe?.get("gav")?.takeUnless { it.isJsonNull }?.asString
                                    val group = result?.get("group")?.takeUnless { it.isJsonNull }?.asString
                                        ?: result?.get("group_id")?.takeUnless { it.isJsonNull }?.asString
                                        ?: result?.get("queue")?.takeUnless { it.isJsonNull }?.asString
                                        ?: gpv
                                        ?: "unknown"
                                    val groupLabel = when {
                                        !gpv.isNullOrBlank() && !gav.isNullOrBlank() -> "ГПВ $gpv · ГАВ $gav"
                                        !gpv.isNullOrBlank() -> "ГПВ $gpv"
                                        !gav.isNullOrBlank() -> "ГАВ $gav"
                                        else -> group
                                    }
                                    onAddressSelected(group, "$city, $street, буд. $item", groupLabel)
                                }
                            }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(item) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when (step) {
                    0 -> onDismiss()
                    1 -> { city = ""; step = 0; query = "" }
                    else -> { street = ""; buildings = emptyList(); step = 1; query = "" }
                }
            }) { Text(if (step == 0) "Закрити" else "Назад") }
        }
    )
}

@Composable
private fun MenuScreen(
    darkTheme: Boolean,
    onBack: () -> Unit,
    onSupport: () -> Unit,
    onAbout: () -> Unit,
    onContact: () -> Unit,
    onToggleTheme: () -> Unit,
    onTestNotification: () -> Unit,
    onRefresh: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackArrowButton(onBack, Modifier.padding(end = 18.dp))
            Text("Меню", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MenuActionCard("₴", "Підтримати проєкт", MaterialTheme.colorScheme.primary, onSupport)
            MenuActionCard("ⓘ", "Про застосунок", MaterialTheme.colorScheme.onSurfaceVariant, onAbout)
            MenuActionCard("✉", "Зв'язатися зі мною", MaterialTheme.colorScheme.onSurfaceVariant, onContact)
            MenuActionCard(if (darkTheme) "☀" else "☾", if (darkTheme) "Світла тема" else "Темна тема", MaterialTheme.colorScheme.onSurfaceVariant, onToggleTheme)
            MenuActionCard("♧", "Тестове сповіщення", MaterialTheme.colorScheme.onSurfaceVariant, onTestNotification)
            MenuActionCard("↻", "Оновити дані", MaterialTheme.colorScheme.onSurfaceVariant, onRefresh)
            Spacer(Modifier.height(4.dp))
            Text("Розробник", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Іван-Дем'ян Тучапець",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINKEDIN_URL))) }
                }
            )
        }
    }
}
@Composable
private fun BackArrowButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(28.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        val arrowColor = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            val y = size.height / 2f
            val x = size.width * 0.18f
            drawLine(arrowColor, Offset(size.width * 0.86f, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(arrowColor, Offset(x, y), Offset(size.width * 0.46f, y - size.height * 0.28f), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(arrowColor, Offset(x, y), Offset(size.width * 0.46f, y + size.height * 0.28f), strokeWidth = stroke, cap = StrokeCap.Square)
        }
    }
}
@Composable
private fun MenuActionCard(icon: String, title: String, iconColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(icon, style = MaterialTheme.typography.headlineSmall, color = iconColor)
        }
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (icon == "₴") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 38.dp, end = 20.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(onBack, Modifier.padding(end = 18.dp))
            Spacer(Modifier.weight(1f))
            Text("↥", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(62.dp))
        Text("Про застосунок", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(28.dp))
        Text(
            "Ми створили цей застосунок як безкоштовного та зручного помічника під час відключень електроенергії.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Планові відключення можуть відрізнятися від фактичних через складну ситуацію в енергосистемі України.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(24.dp))
        Text("Застосунок підтримує населені пункти Новояворівської територіальної громади. Графіки нам надають Нафтогаз Тепло та Львівобленерго.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(24.dp))
        Text("Ми не пов’язані з жодним постачальником або виробником електроенергії.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(28.dp))
        Text("🇺🇦 Разом переможемо!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(28.dp))
        Text("Розробник: Іван-Демян Тучапець", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/ivan-demian-tuchapets-633083158/?isSelfProfile=true"))) } })
    }
}
@Composable
private fun LocationDetailsScreen(
    location: SavedLocation,
    home: JsonObject?,
    powerStatus: JsonObject?,
    loading: Boolean,
    darkTheme: Boolean,
        cacheUpdatedAt: Long?,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(location.nickname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(location.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("ГРУПА ВІДКЛЮЧЕНЬ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Група ${displayGroup(location.groupLabel)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (loading && home == null) {
            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        home?.getAsJsonArray("sections")?.forEach { element ->
            val source = element.asJsonObject
            val section = if (source.get("id")?.asString == "electricity") {
                addressSpecificElectricity(source, powerStatus, location.address, location.groupId).also {
                    cacheUpdatedAt?.let { timestamp -> it.addProperty("_cache_updated_at", timestamp) }
                }
            } else source
            ServiceCard(section, darkTheme)
        }
    }
        BackArrowButton(onBack, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp))
    }
}
private val buildingComparator = compareBy<String> { value ->
    value.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
}.thenBy { it.trim().lowercase(Locale.ROOT) }

private fun displayStreetName(value: String): String = value.replace(Regex("^І\\.\\s*Франка$", RegexOption.IGNORE_CASE), "Івана Франка")

private val AddressChoice.streetLabel: String
    get() = displayStreetName(label.substringAfter(", ", label))

private data class AddressChoice(
    val groupId: String,
    val label: String,
    val buildings: List<String>,
    val buildingGroups: Map<String, String> = emptyMap()
)

private val loeOtgCities = setOf(
    "Новояворівськ", "Шкло", "Прилбичі", "Стені", "Кам’янобрід", "Добростани",
    "Стадники", "Солиги", "Когути", "Воля-Добростанська", "Старичі",
    "Воля-Старицька", "Мужиловичі", "Чолгині", "Рулево", "Терновиця",
    "Качмарі", "Молошковичі", "Бердихів", "Підлуби", "Кертинів", "Нагачів"
)

private fun loeValues(element: JsonElement?, key: String): List<String> {
    val source = when {
        element == null || element.isJsonNull -> null
        element.isJsonArray -> element.asJsonArray
        element.isJsonObject -> element.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        else -> null
    } ?: return emptyList()
    return source.mapNotNull { item ->
        when {
            item.isJsonPrimitive -> item.asString
            item.isJsonObject -> item.asJsonObject.get("name")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("label")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("building")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("number")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("house")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("value")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("street_name")?.takeUnless { it.isJsonNull }?.asString
                ?: item.asJsonObject.get("city_name")?.takeUnless { it.isJsonNull }?.asString
            else -> null
        }
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

@Composable
private fun AddressSelectionDialog(
    groups: List<JsonObject>,
    onDismiss: () -> Unit,
    onLoeRequest: () -> Unit = {},
    onAddressSelected: (String, String) -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var placeQuery by remember { mutableStateOf("") }
    var streetQuery by remember { mutableStateOf("") }
    var selectedPlace by remember { mutableStateOf<String?>(null) }
    var selectedChoice by remember { mutableStateOf<AddressChoice?>(null) }
    val choices = remember(groups) {
        groups.flatMap { group ->
            val groupId = group.get("id")?.takeUnless { it.isJsonNull }?.asString ?: return@flatMap emptyList()
            val addresses = group.getAsJsonArray("addresses")
            if (addresses == null || addresses.size() == 0) {
                // Some Naftogaz groups are published only by settlement and do
                // not have street/building addresses. Keep those settlements in
                // the picker so their group and schedule can still be shown.
                val places = sequenceOf("settlements", "cities", "locations", "places")
                    .flatMap { key ->
                        val value = group.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                        (value?.asSequence() ?: emptySequence()).mapNotNull { item ->
                            when {
                                item.isJsonPrimitive -> item.asString
                                item.isJsonObject -> item.asJsonObject.get("name")?.takeUnless { it.isJsonNull }?.asString
                                    ?: item.asJsonObject.get("city")?.takeUnless { it.isJsonNull }?.asString
                                    ?: item.asJsonObject.get("settlement")?.takeUnless { it.isJsonNull }?.asString
                                else -> null
                            }
                        }
                    }.toList().ifEmpty {
                        listOfNotNull(
                            group.get("settlement")?.takeUnless { it.isJsonNull }?.asString,
                            group.get("city")?.takeUnless { it.isJsonNull }?.asString
                        )
                    }.map { place -> AddressChoice(groupId = groupId, label = place.trim(), buildings = emptyList()) }
                places
            } else addresses.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val address = element.asJsonObject
                val rawName = address.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (rawName.isBlank()) return@mapNotNull null
                val type = address.get("type")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val isSettlement = type.equals("settlement", ignoreCase = true)
                val place = if (isSettlement) rawName else address.get("city")?.takeUnless { it.isJsonNull }?.asString
                    ?: address.get("settlement")?.takeUnless { it.isJsonNull }?.asString
                    ?: "Новояворівськ"
                val name = if (isSettlement) "" else rawName
                val buildings = address.get("buildings")?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asString }.orEmpty()
                AddressChoice(
                    groupId = groupId,
                    label = listOf(place, name).distinct().joinToString(", "),
                    buildings = buildings,
                    buildingGroups = buildings.associateWith { groupId }
                )
            }
        }.groupBy { it.label }
            .map { (_, sameAddress) ->
                val first = sameAddress.first()
                AddressChoice(
                    groupId = first.groupId,
                    label = first.label,
                    buildings = sameAddress.flatMap { it.buildings }.distinct().sortedWith(buildingComparator),
                    buildingGroups = sameAddress.flatMap { it.buildingGroups.entries }.associate { it.key to it.value }
                )
            }
            .sortedBy { it.label.lowercase() }
    }
    val places = choices.map { it.label.substringBefore(", ").trim() }.distinct().sorted()
    val currentPlace = selectedPlace
    val placeChoices = choices.filter { it.label.substringBefore(", ").trim() == currentPlace }
    val filteredPlaces = places.filter { placeQuery.isBlank() || it.contains(placeQuery.trim(), ignoreCase = true) }
    val filteredStreets = placeChoices.filter { choice ->
        streetQuery.isBlank() || displayStreetName(choice.label.substringAfter(", ", choice.label)).contains(streetQuery.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when (step) {
                0 -> "Оберіть населений пункт"
                1 -> "Оберіть адресу або населений пункт"
                else -> "Оберіть номер будинку"
            })
        },
        text = {
            Column {
                when (step) {
                    0 -> {
                        Button(onClick = onLoeRequest, modifier = Modifier.fillMaxWidth()) { Text("Додати адресу Львівобленерго") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = placeQuery, onValueChange = { placeQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Назва населеного пункту") })
                        Spacer(Modifier.height(8.dp))
                        if (filteredPlaces.isEmpty()) Text("Населений пункт не знайдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else LazyColumn(modifier = Modifier.height(340.dp)) {
                            items(filteredPlaces) { place ->
                                Button(onClick = { selectedPlace = place; step = 1; streetQuery = "" }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(place) }
                            }
                        }
                    }
                    1 -> {
                        Text("Населений пункт: ${currentPlace ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = streetQuery, onValueChange = { streetQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Вулиця або адреса") })
                        Spacer(Modifier.height(8.dp))
                        if (filteredStreets.isEmpty()) Text("Адресу не знайдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else LazyColumn(modifier = Modifier.height(340.dp)) {
                            items(filteredStreets, key = { "${it.groupId}|${it.label}|${it.buildings.joinToString()}" }) { choice ->
                                val street = choice.label.substringAfter(", ", choice.label)
                                Button(onClick = {
                                    if (choice.buildings.isEmpty()) onAddressSelected(choice.groupId, choice.streetLabel)
                                    else { selectedChoice = choice; step = 2 }
                                }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(displayStreetName(street) + if (choice.buildings.isNotEmpty()) " · оберіть будинок" else "")
                                }
                            }
                        }
                    }
                    else -> {
                        Text(selectedChoice?.streetLabel ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        val choice = selectedChoice
                        if (choice == null) Text("Оберіть адресу ще раз")
                        else choice.buildings.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { building ->
                                    Button(onClick = { onAddressSelected(choice.buildingGroups[building] ?: choice.groupId, "${choice.streetLabel}, буд. $building") }, modifier = Modifier.weight(1f)) { Text(building) }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (step > 0) Button(onClick = { step -= 1 }) { Text("Назад") }
                Button(onClick = onDismiss) { Text("Закрити") }
            }
        }
    )
}
private fun addressSpecificElectricity(
    groupSection: JsonObject,
    status: JsonObject?,
    selectedAddress: String,
    selectedGroup: String
): JsonObject {
    val result = groupSection.deepCopy()
    if (status == null) {
        result.addProperty("status", "UNKNOWN")
        result.addProperty("title", "Не вдалося перевірити відключення")
        result.addProperty("subtitle", "Адресні дані тимчасово недоступні")
        result.add("date", com.google.gson.JsonNull.INSTANCE)
        result.add("intervals", com.google.gson.JsonArray())
        result.add("outages", com.google.gson.JsonArray())
        result.add("details", com.google.gson.JsonArray())
        return result
    }

    val matched = findAddressOutages(status, selectedAddress, selectedGroup)
    if (matched.isEmpty()) {
        result.addProperty("status", "ON")
        result.addProperty("has_outage", false)
        result.addProperty("title", "Світло має бути")
        result.addProperty("subtitle", "Для вибраної адреси планового відключення не знайдено")
        result.add("date", com.google.gson.JsonNull.INSTANCE)
        result.add("intervals", com.google.gson.JsonArray())
        result.add("outages", com.google.gson.JsonArray())
        result.add("details", com.google.gson.JsonArray())
    } else {
        val intervals = com.google.gson.JsonArray()
        matched.forEach { intervals.add(it.deepCopy()) }
        val date = status.get("date")?.takeUnless { it.isJsonNull }?.asString
        result.addProperty("status", "OFF")
        result.addProperty("has_outage", true)
        result.addProperty("title", status.get("message")?.takeUnless { it.isJsonNull }?.asString ?: "Є планове відключення")
        result.addProperty("subtitle", "Для вибраної адреси знайдено планове відключення")
        if (date.isNullOrBlank()) result.add("date", com.google.gson.JsonNull.INSTANCE) else result.addProperty("date", date)
        result.add("intervals", intervals)
        result.add("outages", com.google.gson.JsonArray())
        result.add("details", com.google.gson.JsonArray())
    }
    return result
}

private fun findAddressOutages(status: JsonObject?, selectedAddress: String, selectedGroup: String): List<JsonObject> {
    if (status == null || selectedAddress.isBlank()) return emptyList()
    val intervals = status.get("intervals")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    val selectedStreet = selectedAddress
        .substringAfter(", ", selectedAddress)
        .substringBeforeLast(", буд.")
        .trim()
    val normalizedStreet = normalizeAddress(selectedStreet)
    val selectedBuilding = Regex("буд\\.?\\s*([^, ]+)", RegexOption.IGNORE_CASE)
        .find(selectedAddress)?.groupValues?.getOrNull(1)

    return intervals.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val interval = element.asJsonObject
        val targets = interval.get("addresses")?.takeIf { it.isJsonArray }?.asJsonArray
        val addressMatch = targets?.any { targetElement ->
            if (!targetElement.isJsonObject) return@any false
            val target = targetElement.asJsonObject
            val targetText = listOf("street", "name", "city", "settlement")
                .mapNotNull { key -> target.get(key)?.takeUnless { it.isJsonNull }?.asString }
                .joinToString(" ")
            val normalizedTarget = normalizeAddress(targetText)
            if (normalizedStreet.isBlank() || !normalizedTarget.contains(normalizedStreet)) return@any false
            val buildings = target.get("buildings")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asString }
                .orEmpty()
            buildings.isEmpty() || selectedBuilding == null || buildings.any { it.equals(selectedBuilding, ignoreCase = true) }
        } ?: false

        val entityMatch = interval.get("entities")?.takeIf { it.isJsonArray }?.asJsonArray?.any { entityElement ->
            if (!entityElement.isJsonObject) return@any false
            val entity = entityElement.asJsonObject
            val name = entity.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            normalizeAddress(name).contains(normalizedStreet)
        } == true

        val groups = status.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray
        val groupMatch = interval.get("address_scope")?.takeUnless { it.isJsonNull }?.asString == "GROUPS" &&
            groups?.any { it.isJsonPrimitive && it.asString == selectedGroup } == true
        if (addressMatch || entityMatch || groupMatch) interval else null
    }
}

private fun normalizeAddress(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

@Composable
private fun ServiceCard(section: JsonObject, darkTheme: Boolean) {
    val id = section.get("id")?.asString.orEmpty()
    val label = section.get("label")?.asString ?: "Послуга"
    val title = section.get("title")?.asString ?: "Немає даних"
    val subtitle = section.get("subtitle")?.asString.orEmpty()
    val status = section.get("status")?.asString
    val isOn = status == "ON"
    val isOff = status == "OFF"
    val statusAccent = when {
        isOn -> Color(0xFF218653)
        isOff && darkTheme -> MaterialTheme.colorScheme.primary
        isOff -> Color(0xFFC64C43)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusBackground = when {
        isOn -> Color(0xFFE7F6EE)
        isOff && darkTheme -> Color(0xFF382512)
        isOff -> Color(0xFFFFECEA)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val availabilityLabel = when {
        isOn -> "Послуга доступна"
        isOff -> "Є планове відключення"
        else -> "Немає актуальних даних"
    }
    val statusPill = when {
        isOn -> "Є"
        isOff -> "Немає"
        else -> "—"
    }
    val accent = if (id == "water") Color(0xFF3187E8) else Color(0xFFFFB42B)
    val pale = if (id == "water") Color(0xFFEAF4FF) else Color(0xFFFFF6E4)
    val iconBackground = if (darkTheme) {
        if (id == "water") Color(0xFF17324E) else Color(0xFF44351D)
    } else pale
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(iconBackground, CircleShape).padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(if (id == "water") "💧" else "⚡", color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(availabilityLabel, style = MaterialTheme.typography.bodySmall, color = statusAccent)
                }
                Box(Modifier.background(statusBackground, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                    Text(statusPill, color = statusAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (id == "electricity") {
                Spacer(Modifier.height(14.dp))
                WeeklySchedule(section, section.get("_cache_updated_at")?.takeUnless { it.isJsonNull }?.asLong)
            }
        }
    }
}

@Composable
private fun CrossedBoltIcon(color: Color) {
    Canvas(Modifier.size(36.dp)) {
        val w = 36f
        val h = 36f
        val bolt = Path().apply {
            moveTo(w * 0.60f, h * 0.04f)
            lineTo(w * 0.25f, h * 0.52f)
            lineTo(w * 0.48f, h * 0.52f)
            lineTo(w * 0.34f, h * 0.96f)
            lineTo(w * 0.78f, h * 0.40f)
            lineTo(w * 0.55f, h * 0.40f)
            close()
        }
        drawPath(bolt, color)
        drawLine(color, Offset(w * 0.12f, h * 0.12f), Offset(w * 0.88f, h * 0.88f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    }
}

private data class SchedulePeriod(
    val startMinutes: Int,
    val endMinutes: Int,
    val isOutage: Boolean
)

@Composable
private fun WeeklySchedule(section: JsonObject, updatedAt: Long? = null) {
    val dateValue = section.get("date")?.takeUnless { it.isJsonNull }?.asString
    val scheduleDate = remember(dateValue) {
        dateValue?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val anchorDate = scheduleDate ?: LocalDate.now()
    var selectedDate by remember(anchorDate) { mutableStateOf(anchorDate) }
    val weekStart = anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
    val weekDays = (0L..6L).map { weekStart.plusDays(it) }
    val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

    Text("Графік відключень", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { day ->
            val selected = day == selectedDate
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { selectedDate = day }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(dayNames[day.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    updatedAt?.let {
        Text("Дані оновлено: ${formatUpdatedAt(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }


    if (scheduleDate == null || selectedDate != scheduleDate) {
        Text(
            if (scheduleDate == null) "Для цієї адреси відключень не знайдено"
            else "Графік на цей день ще не опубліковано",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val intervals = section.get("intervals")?.takeIf { it.isJsonArray }?.asJsonArray
    val outages = intervals?.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val interval = element.asJsonObject
        val start = intervalMinute(interval.get("from_time")?.takeUnless { it.isJsonNull }?.asString) ?: return@mapNotNull null
        val end = intervalMinute(interval.get("to_time")?.takeUnless { it.isJsonNull }?.asString) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        val state = interval.get("status")?.takeUnless { it.isJsonNull }?.asString
        SchedulePeriod(start, end, isOutage = state != "ON")
    }.orEmpty().sortedBy { it.startMinutes }

    if (outages.isEmpty()) {
        Text("На цей день відключень не заплановано", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val periods = mutableListOf<SchedulePeriod>()
    var cursor = 0
    outages.forEach { outage ->
        val start = outage.startMinutes.coerceIn(0, 1440)
        val end = outage.endMinutes.coerceIn(0, 1440)
        if (start > cursor) periods += SchedulePeriod(cursor, start, isOutage = false)
        if (end > cursor) {
            periods += SchedulePeriod(maxOf(cursor, start), end, outage.isOutage)
            cursor = end
        }
    }
    if (cursor < 1440) periods += SchedulePeriod(cursor, 1440, isOutage = false)

    periods.forEach { period ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (period.isOutage) {
                CrossedBoltIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (period.isOutage) "Відключення світла" else "Світло має бути",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (period.isOutage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
                Text(
                    "${formatMinute(period.startMinutes)}–${formatMinute(period.endMinutes)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (period.isOutage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                )
            }
            outageDuration(period.startMinutes, period.endMinutes)?.let { duration ->
                Text(duration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun displayGroup(group: String): String {
    if (!group.startsWith("loe:")) return group
    val value = group.removePrefix("loe:")
    return if (value == "unknown") "дані уточнюються" else "ГПВ $value"
}

private fun formatUpdatedAt(timestamp: Long): String {
    return runCatching {
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrDefault("невідомо")
}

private fun intervalMinute(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..24 || minutes !in 0..59 || (hours == 24 && minutes != 0)) return null
    return hours * 60 + minutes
}

private fun formatMinute(value: Int): String = "%02d:%02d".format(Locale.ROOT, value / 60, value % 60)

private fun outageDuration(startMinutes: Int, endMinutes: Int): String? {
    val duration = (endMinutes - startMinutes).coerceAtLeast(0)
    val hours = duration / 60
    val mins = duration % 60
    return when {
        hours > 0 && mins > 0 -> "$hours год $mins хв"
        hours > 0 -> "$hours год"
        mins > 0 -> "$mins хв"
        else -> null
    }
}






























