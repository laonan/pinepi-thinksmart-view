@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.pinepi.thinksmart.launcher

import android.view.Gravity
import android.view.KeyEvent
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class HomePage { MESSAGES, CHAT, SETTINGS }

@Composable
fun HomeApp(
    page: HomePage, messages: List<HomeMessage>, selectedId: String?, hubStatus: Int,
    hubs: List<DiscoveredHub>, bluetooth: BluetoothMonitor.State, proximity: Boolean,
    mode: ThemeMode, active: Boolean, storageNotice: Int, media: HomeMediaResources,
    arrivalId: String?, onArrivalShown: (String) -> Unit,
    onPage: (HomePage) -> Unit, onSelect: (String) -> Unit, onTheme: (ThemeMode) -> Unit,
    onProximity: (Boolean) -> Unit, onSettings: (String) -> Unit, onOtherLauncher: () -> Unit,
    onConfirmHub: (String) -> Unit, onClear: () -> Unit, onVersion: () -> Unit,
    onSurfaceColors: (Color, Boolean) -> Unit,
    voiceVolume: Int, volumeSteps: Int, onVoiceVolume: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background == Color(0xFF101B16)
    SideEffect { onSurfaceColors(colors.background, dark) }
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = colors.background) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 18.dp)) {
            HomeHeader(page, hubStatus, bluetooth, active, onPage)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    HomePage.MESSAGES -> MessagePage(messages, selectedId, hubs.isNotEmpty(), active, bluetooth, media, onSelect, arrivalId, onArrivalShown)
                    HomePage.CHAT -> ChatPage()
                    HomePage.SETTINGS -> SettingsPage(hubStatus, hubs, proximity, mode, storageNotice,
                        onTheme, onProximity, onSettings, onOtherLauncher, onConfirmHub, onClear, onVersion,
                        voiceVolume, volumeSteps, onVoiceVolume)
                }
            }
            if (page == HomePage.MESSAGES) {
                Spacer(Modifier.height(16.dp))
                val selected = messages.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                HomeFooter(selected, messages.size) { onPage(HomePage.CHAT) }
            }
        }
    }
}

@Composable
private fun HomeHeader(page: HomePage, hubStatus: Int, bluetooth: BluetoothMonitor.State, active: Boolean, onPage: (HomePage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_pine), null, Modifier.size(30.dp), tint = colors.primary)
            Text("PinePi Home", Modifier.testTag("home_brand"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp), color = colors.onSurface)
        }
        Spacer(Modifier.width(24.dp))
        ClockText(active, Modifier.weight(1f))
        StatusLabel(stringResource(if (hubStatus == R.string.hub_test_connected) R.string.main_hub_connected else R.string.main_hub_unavailable), hubStatus == R.string.hub_test_connected)
        Spacer(Modifier.width(16.dp))
        val btLabel = when (bluetooth) {
            BluetoothMonitor.State.CONNECTED -> R.string.main_bluetooth_connected
            BluetoothMonitor.State.CONNECTING -> R.string.main_bluetooth_connecting
            BluetoothMonitor.State.DISCONNECTED -> R.string.main_bluetooth_disconnected
            BluetoothMonitor.State.OFF -> R.string.main_bluetooth_off
            BluetoothMonitor.State.UNAVAILABLE -> R.string.main_bluetooth_unavailable
            BluetoothMonitor.State.UNKNOWN -> R.string.main_bluetooth_unknown
        }
        val btIcon = when (bluetooth) {
            BluetoothMonitor.State.CONNECTED -> R.drawable.ic_bluetooth_connected
            BluetoothMonitor.State.CONNECTING -> R.drawable.ic_bluetooth_searching
            BluetoothMonitor.State.OFF, BluetoothMonitor.State.UNAVAILABLE -> R.drawable.ic_bluetooth_disabled
            else -> R.drawable.ic_bluetooth
        }
        Icon(painterResource(btIcon), stringResource(btLabel),
            Modifier.size(18.dp).testTag("home_bluetooth"),
            tint = if (bluetooth == BluetoothMonitor.State.CONNECTED) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        FilledTonalIconButton(onClick = { onPage(if (page == HomePage.MESSAGES) HomePage.SETTINGS else HomePage.MESSAGES) },
            modifier = Modifier.testTag("open_settings")) {
            Icon(if (page == HomePage.MESSAGES) Icons.Default.Settings else Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(if (page == HomePage.MESSAGES) R.string.main_settings else R.string.main_back_home), Modifier.size(20.dp))
        }
    }
}

/** Only this composable reads the seconds clock; it does not invalidate message/media content. */
@Composable
private fun ClockText(active: Boolean, modifier: Modifier) {
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (active) while (true) { time = System.currentTimeMillis(); delay(1000 - time % 1000) }
    }
    Text(SimpleDateFormat("yyyy年M月d日 EEEE  HH:mm:ss", Locale.SIMPLIFIED_CHINESE).format(Date(time)),
        modifier.testTag("home_datetime"), fontSize = 16.sp, fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

@Composable
private fun StatusLabel(label: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessagePage(messages: List<HomeMessage>, selectedId: String?, discovered: Boolean,
                        active: Boolean, bluetooth: BluetoothMonitor.State, media: HomeMediaResources, onSelect: (String) -> Unit,
                        arrivalId: String?, onArrivalShown: (String) -> Unit) {
    var displayedMessages by remember { mutableStateOf(messages) }
    var transitioning by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(if (arrivalId != null && arrivalId == messages.firstOrNull()?.id) 0f else 1f) }
    val consumeArrival by rememberUpdatedState(onArrivalShown)
    // Retain the old pager until it has faded out, then switch at zero opacity.
    // Consuming arrivalId does not cancel this effect; only a newer message can.
    LaunchedEffect(messages) {
        val newest = messages.firstOrNull()?.id
        val animate = newest != null && newest == arrivalId
        transitioning = animate
        if (animate) {
            if (displayedMessages.isNotEmpty() && displayedMessages.first().id != newest) {
                opacity.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
            } else opacity.snapTo(0f)
            displayedMessages = messages
            consumeArrival(newest!!)
            delay(150)
            opacity.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        } else {
            displayedMessages = messages
            opacity.snapTo(1f)
        }
        transitioning = false
    }
    val index = if (transitioning && displayedMessages.firstOrNull()?.id == messages.firstOrNull()?.id) 0
        else displayedMessages.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val modifier = Modifier.fillMaxSize().testTag("page_messages")
    if (displayedMessages.isEmpty()) {
        Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            EmptyState(if (discovered) R.string.main_hub_found else R.string.main_no_messages, R.string.home_waiting_message)
        }
        return
    }
    key(displayedMessages.first().id) {
        val pager = rememberPagerState(initialPage = index, pageCount = { displayedMessages.size })
        val select by rememberUpdatedState(onSelect)
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { page ->
                displayedMessages.getOrNull(page)?.let { select(it.id) }
            }
        }
        HorizontalPager(state = pager, modifier = modifier, pageSpacing = 16.dp,
            userScrollEnabled = !transitioning,
            key = { displayedMessages[it].id }, beyondViewportPageCount = 0) { page ->
            MessageCard(displayedMessages[page], active && !transitioning && pager.settledPage == page && !pager.isScrollInProgress, bluetooth, media) { opacity.value }
        }
    }
}

@Composable
private fun MessageCard(message: HomeMessage, active: Boolean, bluetooth: BluetoothMonitor.State, media: HomeMediaResources, contentOpacity: () -> Float) {
    val critical = message.severity == HomeMessage.Severity.CRITICAL
    val warning = messageWarningColors()
    Surface(Modifier.fillMaxSize().testTag("message_card"), shape = RoundedCornerShape(28.dp),
        color = if (critical) warning.container else MaterialTheme.colorScheme.surface,
        contentColor = if (critical) warning.content else MaterialTheme.colorScheme.onSurface,
        border = if (critical) BorderStroke(1.dp, warning.outline.copy(alpha = 0.6f)) else null) {
        Column(Modifier.padding(start = 28.dp, top = 28.dp, end = 28.dp,
            bottom = if (message.kind == HomeMessage.Kind.TEXT) 0.dp else 28.dp)
            .graphicsLayer { alpha = contentOpacity() }) {
            Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(modifier = Modifier.widthIn(max = 280.dp), shape = RoundedCornerShape(8.dp), color = if (critical) warning.content.copy(alpha = 0.08f) else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(message.source, Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("message_source"),
                        style = MaterialTheme.typography.labelMedium, color = if (critical) warning.content else MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(
                    if (message.version == 2) java.time.OffsetDateTime.parse(message.createdAt).toInstant().toEpochMilli() else message.receivedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (critical) warning.content.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.textMode == HomeMessage.TextMode.PRIVATE) {
                    Icon(Icons.Default.Lock, stringResource(R.string.main_private_message),
                        Modifier.size(18.dp).testTag("message_private"),
                        tint = if (critical) warning.content else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.kind == HomeMessage.Kind.TEXT && message.textMode == HomeMessage.TextMode.TTS) {
                    AudioControls(message, bluetooth.allowsAudio, active, compact = true)
                }
                if (critical) {
                    Surface(shape = RoundedCornerShape(8.dp), color = warning.content.copy(alpha = 0.1f),
                        contentColor = warning.content, modifier = Modifier.testTag("message_critical")) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp))
                            Text(stringResource(R.string.main_severity_critical), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (message.kind != HomeMessage.Kind.TEXT && message.title.isNotBlank()) {
                Text(message.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (message.kind) {
                    HomeMessage.Kind.TEXT -> TextMessage(message)
                    HomeMessage.Kind.IMAGE -> ImageMessage(message, active, media)
                    HomeMessage.Kind.VIDEO -> VideoMessage(message, active, bluetooth.allowsAudio, media)
                    HomeMessage.Kind.AUDIO -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AudioControls(message, bluetooth.allowsAudio, active)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioControls(message: HomeMessage, allowsPlayback: Boolean, active: Boolean, compact: Boolean = false) {
    val context = LocalContext.current
    val audio = (context.applicationContext as LauncherApplication).messageAudio
    val playing = audio.currentId == message.id
    if (compact) {
        val label = stringResource(if (playing) R.string.main_stop_audio else R.string.main_read_text)
        val description = if (!allowsPlayback && !playing) label + "，" + stringResource(R.string.main_audio_blocked)
            else if (playing && audio.loading) stringResource(R.string.main_audio_loading) + "，" + label else label
        IconButton(onClick = { if (playing) audio.stop() else audio.play(message) },
            enabled = active && (playing || allowsPlayback), modifier = Modifier.testTag("message_audio_play")) {
            if (playing && audio.loading) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Icon(Icons.Default.Close, description, Modifier.size(12.dp))
                }
            } else if (playing) Icon(painterResource(R.drawable.ic_stop), description, Modifier.size(20.dp))
            else Icon(Icons.Default.PlayArrow, description, Modifier.size(20.dp))
        }
        val error = audio.lastError
        LaunchedEffect(error, active) {
            if (active && error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(onClick = { if (playing) audio.stop() else audio.play(message) },
            enabled = active && (playing || allowsPlayback), modifier = Modifier.testTag("message_audio_play")) {
            Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (playing) R.string.main_stop_audio else if (message.kind == HomeMessage.Kind.TEXT) R.string.main_read_text else R.string.main_play_audio))
        }
        if (playing && audio.loading) Text(stringResource(R.string.main_audio_loading), style = MaterialTheme.typography.bodySmall)
        else if (!allowsPlayback) Text(stringResource(R.string.main_audio_blocked), style = MaterialTheme.typography.bodySmall)
        else audio.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Keep the hint outside the text viewport so it never obscures a line or causes a layout jump. */
@Composable
private fun TextMessage(message: HomeMessage) {
    val context = LocalContext.current
    var hintToast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { hintToast?.cancel() } }
    val scroll = rememberScrollState()
    val moreBelow by remember(scroll) {
        derivedStateOf { scroll.maxValue != Int.MAX_VALUE && scroll.canScrollForward }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            if (message.title.isNotBlank()) {
                Text(message.title, Modifier.testTag("message_title"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(36.dp))
            }
            Text(message.text, Modifier.testTag("message_body"), style = MaterialTheme.typography.bodyLarge)
        }
        // Stable space avoids the last line jumping when the hint disappears at the end.
        Box(Modifier.fillMaxWidth().height(32.dp).testTag("message_scroll_hint_area")
            .scrollable(scroll, Orientation.Vertical, reverseDirection = true), contentAlignment = Alignment.Center) {
            if (moreBelow) {
                Box(Modifier.width(48.dp).fillMaxHeight().testTag("message_more_below")
                    .clickable(role = Role.Button) {
                        hintToast?.cancel()
                        hintToast = Toast.makeText(context, R.string.main_scroll_hint, Toast.LENGTH_SHORT).also { it.show() }
                    }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_double_chevron_down), stringResource(R.string.main_more_below),
                        Modifier.size(24.dp).testTag("message_more_below_icon"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: Int, subtitle: Int, microphone: Boolean = false) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)) {
            Icon(painterResource(if (microphone) R.drawable.ic_mic else R.drawable.ic_pine), null,
                Modifier.padding(22.dp).size(42.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(title), Modifier.testTag(if (microphone) "chat_unavailable" else "message_empty"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeFooter(selected: Int, count: Int, onChat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            if (count > 1) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { index -> Box(Modifier.size(if (index == selected) 14.dp else 4.dp, 4.dp)
                    .background(if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
            }
            if (count > 1) Spacer(Modifier.height(5.dp))
            Text(if (count == 0) stringResource(R.string.main_no_messages) else stringResource(R.string.main_position, selected + 1, count),
                Modifier.testTag("message_position"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(32.dp))
        FilledTonalButton(onClick = onChat, modifier = Modifier.height(48.dp).testTag("main_page_action"), contentPadding = PaddingValues(horizontal = 22.dp)) {
            Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text(stringResource(R.string.main_open_chat))
        }
    }
}

@Composable
private fun ChatPage() {
    Column(Modifier.fillMaxSize().testTag("page_chat")) {
        Text(stringResource(R.string.main_chat_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            EmptyState(R.string.chat_service_unavailable, R.string.chat_service_hint, microphone = true)
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.align(Alignment.CenterHorizontally).height(48.dp).widthIn(min = 180.dp).testTag("chat_record")) {
            Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.main_start_talking))
        }
    }
}

@Composable
private fun SettingsPage(hubStatus: Int, hubs: List<DiscoveredHub>, proximity: Boolean, mode: ThemeMode, storageNotice: Int,
                         onTheme: (ThemeMode) -> Unit, onProximity: (Boolean) -> Unit, onSettings: (String) -> Unit,
                         onOtherLauncher: () -> Unit, onConfirmHub: (String) -> Unit, onClear: () -> Unit, onVersion: () -> Unit,
                         voiceVolume: Int, volumeSteps: Int, onVoiceVolume: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("page_settings").verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.main_settings), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) {
                        SectionLabel(R.string.theme_title)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { index, item ->
                                SegmentedButton(selected = item == mode, onClick = { onTheme(item) },
                                    shape = SegmentedButtonDefaults.itemShape(index, 3), modifier = Modifier.weight(1f).testTag("theme_" + item.name.lowercase(Locale.ROOT))) {
                                    Text(stringResource(when (item) { ThemeMode.AUTO -> R.string.theme_auto; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.theme_schedule), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) {
                        var draft by remember(voiceVolume) { mutableFloatStateOf(voiceVolume.toFloat()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.voice_volume_title), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.voice_volume_step, draft.roundToInt(), volumeSteps), Modifier.testTag("voice_volume_value"))
                        }
                        Slider(value = draft, onValueChange = { draft = it }, valueRange = 1f..volumeSteps.coerceAtLeast(2).toFloat(), enabled = volumeSteps > 1,
                            steps = (volumeSteps - 2).coerceAtLeast(0), onValueChangeFinished = { onVoiceVolume(draft.roundToInt()) },
                            modifier = Modifier.testTag("voice_volume_slider"))
                        Text(stringResource(R.string.voice_volume_hint), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        SettingItem(R.string.wifi_settings, Icons.Default.Settings, "main_wifi") { onSettings(Settings.ACTION_WIFI_SETTINGS) }
                        SettingItem(R.string.bluetooth_settings, Icons.Default.Settings, "main_bluetooth") { onSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                        SettingItem(R.string.system_settings, Icons.Default.Settings, "main_system") { onSettings(Settings.ACTION_SETTINGS) }
                        SettingItem(R.string.main_clear_messages, Icons.Default.Delete, "clear_local_messages") { onClear() }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        SectionLabel(R.string.settings_connection)
                        Spacer(Modifier.height(7.dp))
                        Text(stringResource(hubStatus), Modifier.testTag("settings_hub_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        hubs.forEach { hub ->
                            OutlinedButton(onClick = { onConfirmHub(hub.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.hub_confirm, hub.name, hub.host, hub.port), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.proximity_wake_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = proximity, onCheckedChange = onProximity, modifier = Modifier.testTag("main_proximity_enabled"))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        SettingItem(R.string.date_time_settings, Icons.Default.Info, "main_date_settings") { onSettings(Settings.ACTION_DATE_SETTINGS) }
                        SettingItem(R.string.home_settings, Icons.Default.Home, "main_home_settings") { onSettings(Settings.ACTION_HOME_SETTINGS) }
                        SettingItem(R.string.maintenance_launcher, Icons.Default.Home, "main_other_launcher", onOtherLauncher)
                    }
                }
                Text(stringResource(storageNotice), Modifier.padding(horizontal = 12.dp).testTag("settings_storage_status"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onVersion, modifier = Modifier.testTag("about_version")) {
            Icon(painterResource(R.drawable.ic_pine), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.main_version), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionLabel(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingItem(title: Int, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ImageMessage(message: HomeMessage, active: Boolean, resources: HomeMediaResources) {
    var state by remember(message.id) { mutableStateOf<HomeImageState>(HomeImageState.Loading) }
    var expanded by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) expanded = false }
    DisposableEffect(message.id, active) {
        var valid = true
        // Keep the decoded image visible while its card is being dragged away.
        val call = if (active && state !is HomeImageState.Ready) {
            state = HomeImageState.Loading
            resources.loadImage(message.mediaUrl) { if (valid) state = it }
        } else null
        onDispose { valid = false; call?.cancel() }
    }
    when (val result = state) {
        is HomeImageState.Ready -> {
            Box(Modifier.fillMaxSize()) {
                Image(result.bitmap.asImageBitmap(), stringResource(R.string.main_image_fullscreen),
                    Modifier.fillMaxSize().testTag("message_image")
                        .clickable(enabled = active, role = Role.Button) { expanded = true }, contentScale = ContentScale.Fit)
                IconButton(onClick = { expanded = true }, enabled = active,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .testTag("image_fullscreen_open")) {
                    Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.main_image_fullscreen), tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
            }
            if (expanded && active) FullscreenMedia(onClose = { expanded = false }) {
                Image(result.bitmap.asImageBitmap(), stringResource(R.string.main_image_message),
                    Modifier.fillMaxSize().testTag("fullscreen_image"), contentScale = ContentScale.Fit)
            }
        }
        HomeImageState.Loading -> MediaNotice(R.string.main_media_loading)
        HomeImageState.Failed -> MediaNotice(R.string.main_media_failed)
    }
}

@Composable
private fun MediaNotice(text: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(text), Modifier.testTag("message_media_status"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dialog owns the entire display; it never opens another Activity or loses the selected card. */
@Composable
private fun FullscreenMedia(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                // SurfaceView can expose the window behind its aspect-ratio letterbox.
                it.setBackgroundDrawableResource(android.R.color.black)
                it.setDimAmount(0f)
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen_media")
            .semantics { testTagsAsResourceId = true }) {
            content()
            IconButton(onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape).testTag("fullscreen_close")) {
                Icon(Icons.Default.Close, stringResource(R.string.main_close_fullscreen), Modifier.size(24.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun VideoMessage(message: HomeMessage, active: Boolean, allowsPlayback: Boolean, resources: HomeMediaResources) {
    val context = LocalContext.current
    val messageAudio = (context.applicationContext as LauncherApplication).messageAudio
    var expanded by remember(message.id) { mutableStateOf(false) }
    var requested by remember(message.id) { mutableStateOf(false) }
    var loading by remember(message.id) { mutableStateOf(false) }
    var finished by remember(message.id) { mutableStateOf(false) }
    var error by remember(message.id) { mutableStateOf<Int?>(null) }
    val stillActive by rememberUpdatedState(active && allowsPlayback)
    fun startPlayback() {
        if (!active || !allowsPlayback) { error = R.string.main_video_blocked; return }
        messageAudio.stop()
        error = null; loading = true; finished = false; requested = true
    }
    fun open() {
        if (!active) return
        expanded = true
        startPlayback()
    }
    fun close() { expanded = false; requested = false; loading = false }
    LaunchedEffect(active, allowsPlayback) {
        if (!active) close()
        if (!allowsPlayback) { requested = false; loading = false; error = R.string.main_video_blocked }
        else if (error == R.string.main_video_blocked) error = null
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled = active, role = Role.Button, onClickLabel = stringResource(R.string.main_video_fullscreen)) { open() }
        .testTag("video_fullscreen_open"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FilledTonalIconButton(onClick = { open() }, enabled = active,
                modifier = Modifier.size(64.dp).testTag("message_video_play")) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.main_video_fullscreen), Modifier.size(32.dp))
            }
            Text(stringResource(R.string.main_video_fullscreen), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (expanded && active) FullscreenMedia(onClose = { close() }) {
        if (requested && allowsPlayback) {
            AndroidView(factory = { context ->
                // Keep letterboxing in the native hierarchy: SurfaceView's transparent
                // region must expose an opaque native background, not the Home window.
                val container = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                val video = VideoView(context).apply {
                    resources.video = this
                    resources.onVideoStopped = { close() }
                    setOnPreparedListener { player ->
                        if (stillActive && resources.video === this) {
                            loading = false
                            player.isLooping = false
                            val controls = object : MediaController(context) {
                                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                                        if (event.action == KeyEvent.ACTION_UP) close()
                                        return true
                                    }
                                    return super.dispatchKeyEvent(event)
                                }
                            }
                            controls.setAnchorView(this)
                            tag = controls
                            setMediaController(controls)
                            start()
                        } else stopPlayback()
                    }
                    setOnCompletionListener { requested = false; loading = false; finished = true }
                    setOnErrorListener { _, _, _ -> error = R.string.main_media_failed; requested = false; loading = false; true }
                    if (HomeMediaUrl.allowed(message.mediaUrl)) setVideoPath(message.mediaUrl)
                    else { error = R.string.main_media_failed; requested = false; loading = false }
                }
                container.addView(video, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                container
            }, modifier = Modifier.fillMaxSize().testTag("message_video"),
                onRelease = { container ->
                    val view = container.getChildAt(0) as VideoView
                    if (resources.video === view) {
                        // Removing a completed player keeps the viewer open for replay.
                        resources.onVideoStopped = null
                        resources.stopVideo()
                    } else {
                        (view.tag as? MediaController)?.hide(); view.tag = null
                        view.setOnPreparedListener(null); view.setOnCompletionListener(null); view.setOnErrorListener(null)
                        view.stopPlayback()
                    }
                })
            if (loading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color.White)
                Text(stringResource(R.string.main_media_loading), color = Color.White)
            }
        } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FilledTonalIconButton(onClick = { startPlayback() }, enabled = allowsPlayback,
                modifier = Modifier.size(64.dp).testTag("fullscreen_video_play")) {
                Icon(Icons.Default.PlayArrow, stringResource(if (finished) R.string.main_replay_video else R.string.main_play_video), Modifier.size(32.dp))
            }
            Text(stringResource(error ?: if (finished) R.string.main_replay_video else R.string.main_play_video),
                Modifier.testTag("message_media_status"), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
