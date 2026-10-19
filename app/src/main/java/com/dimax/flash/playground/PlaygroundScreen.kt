package com.dimax.flash.playground

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dimax.flash.R
import com.dimax.flash.nfc.NfcManager
import com.dimax.flash.ui.theme.FlashExpressiveTheme
import com.dimax.flash.ui.theme.ThemeMode
import com.dimax.flash.util.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val TRIALS = 10

private enum class Experiment(val label: String) {
    WIFI("Wi-Fi tag"), CONTACT("Contact card"), LINK("Link tag"), BLINK("Blink test"),
    STOPWATCH("Handshake stopwatch"), RACE("Touch race"), TAP("Tap together"),
    RELAY("Relay chain"), FIELD("Contact time"), HOLD("Hold test")
}

/**
 * Experiments with NFC and the phones. Experimental and English-only for now.
 * - Wi-Fi tag, contact card, link tag: one phone shares, the other reads (and acts after a tap).
 * - Blink test: the leader fires blinks at unannounced moments; the follower flashes and reports its time.
 * - Lab (NFC link timing): handshake stopwatch, touch race, tap together, relay chain, contact time and the
 *   hold test (1 MB stream, text ping-pong, quiet). Each phone shows its own side: the card and the
 *   reader see different things, and only the card learns when the link drops for sure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundScreen(nfcManager: NfcManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var experiment by rememberSaveable { mutableStateOf(Experiment.WIFI) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var contactName by rememberSaveable { mutableStateOf("") }
    var contactPhone by rememberSaveable { mutableStateOf("") }
    var contactEmail by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var leader by rememberSaveable { mutableStateOf(false) }
    var holdMode by rememberSaveable { mutableStateOf(LabMode.STREAM) }
    var status by remember { mutableStateOf("Pick an experiment.") }
    var log by remember { mutableStateOf(listOf<String>()) }
    var deltas by remember { mutableStateOf(listOf<Long>()) }
    var flashOn by remember { mutableStateOf(false) }
    var contactIn by remember { mutableStateOf<ContactCard?>(null) }
    var linkIn by remember { mutableStateOf<String?>(null) }
    var blinkCard by remember { mutableStateOf<android.nfc.NdefMessage?>(null) }
    var blinkServer by remember { mutableStateOf<BlinkServer?>(null) }
    var snap by remember { mutableStateOf(LabSnapshot()) }
    var wifi by remember { mutableStateOf("") }
    var relayText by remember { mutableStateOf("") }
    val running = remember { AtomicBoolean(false) }
    val tapSensor = remember(context) { TapSensor(context.applicationContext) }

    val labMode: LabMode? = when (experiment) {
        Experiment.STOPWATCH -> LabMode.STOPWATCH
        Experiment.RACE -> LabMode.RACE
        Experiment.TAP -> LabMode.TAP
        Experiment.RELAY -> LabMode.RELAY
        Experiment.FIELD -> LabMode.FIELD
        Experiment.HOLD -> holdMode
        else -> null
    }
    val labCardOn = labMode != null && sharing

    // Only one side can be a card: the sharing phone or the leader. The other one reads.
    val cardWanted = if (experiment == Experiment.BLINK) leader else sharing
    val card = remember(experiment, sharing, ssid, password, contactName, contactPhone, contactEmail, link, blinkCard) {
        when {
            experiment == Experiment.BLINK -> blinkCard
            !sharing -> null
            experiment == Experiment.WIFI && ssid.isNotBlank() ->
                PlaygroundNdef.wifiMessage(WifiCredential(ssid, password))
            experiment == Experiment.CONTACT && contactName.isNotBlank() ->
                PlaygroundNdef.contactMessage(ContactCard(contactName, contactPhone, contactEmail))
            experiment == Experiment.LINK && link.isNotBlank() -> PlaygroundNdef.linkMessage(link)
            else -> null
        }
    }
    DisposableEffect(card) {
        nfcManager.serveNdef(card)
        onDispose { nfcManager.serveNdef(null) }
    }
    DisposableEffect(cardWanted) {
        if (activity != null) {
            if (cardWanted) nfcManager.disableReaderMode(activity) else nfcManager.enableReaderMode(activity)
        }
        onDispose { if (activity != null) nfcManager.disableReaderMode(activity) }
    }
    DisposableEffect(Unit) {
        onDispose {
            running.set(false)
            blinkServer?.stop()
        }
    }

    // Lab: the card side answers the experiment's commands while a lab card is on
    DisposableEffect(labCardOn, labMode) {
        labMode?.let { if (labCardOn) NfcLab.startCard(context, it) }
        onDispose { if (labCardOn) NfcLab.stopCard() }
    }
    LaunchedEffect(labCardOn, labMode) {
        if (labCardOn && labMode == LabMode.RACE) NfcLab.listenForRaces()
    }
    DisposableEffect(labMode) {
        if (labMode == LabMode.TAP) {
            tapSensor.start()
            NfcLab.cardPeakSource = { tapSensor.all() }
        }
        onDispose {
            tapSensor.stop()
            NfcLab.cardPeakSource = null
        }
    }
    // Lab: the reader side takes the tag itself while the phone is not a card
    DisposableEffect(labMode, labCardOn) {
        val mode = labMode
        if (mode != null && !labCardOn) {
            nfcManager.setLabReader { tag, iso -> NfcLab.readerSession(tag, iso, mode, tapSensor) }
        }
        onDispose { nfcManager.setLabReader(null) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            snap = NfcLab.snapshot()
            wifi = NfcLab.wifiLine(context)
            relayText = String(NfcLab.relay, Charsets.UTF_8)
            delay(300)
        }
    }

    suspend fun say(line: String) = withContext(Dispatchers.Main) { log = (log + line).takeLast(20) }
    suspend fun setStatus(text: String) = withContext(Dispatchers.Main) { status = text }
    suspend fun flash(on: Boolean) = withContext(Dispatchers.Main) { flashOn = on }

    fun stopAll() {
        running.set(false)
        blinkServer?.stop()
        blinkServer = null
        blinkCard = null
        contactIn = null
        linkIn = null
        status = "Stopped."
    }

    fun startFollower(target: PlaygroundTarget) {
        running.set(true)
        status = "Following the other phone…"
        scope.launch(Dispatchers.IO) {
            val client = BlinkClient(target)
            val offset = client.clockOffsetMs()
            say("clock difference: $offset ms (other phone minus this one)")
            setStatus("Waiting for blinks…")
            while (running.get()) {
                if (!client.awaitBlink()) continue
                val now = System.currentTimeMillis()
                flash(true)
                delay(150)
                flash(false)
                client.report(now + offset)
                say("flashed, reported to the other phone")
            }
        }
    }

    fun startLeader() {
        val ip = LocalNetwork.localIpv4(context)
        if (ip == null) {
            status = "No Wi-Fi address. Join the same Wi-Fi network on both phones first."
            return
        }
        val token = UUID.randomUUID().toString()
        val server = BlinkServer(token)
        val port = server.start()
        blinkServer = server
        blinkCard = PlaygroundNdef.targetMessage(ip, port, token)
        deltas = emptyList()
        log = emptyList()
        running.set(true)
        status = "Leader ready. Touch the other phone."
        scope.launch(Dispatchers.IO) {
            repeat(TRIALS) { i ->
                // Unannounced: the follower can't know when the next blink comes
                delay(Random.nextLong(1_000, 3_000))
                if (!running.get()) return@repeat
                val t = System.currentTimeMillis()
                server.fire(t)
                flash(true)
                delay(150)
                flash(false)
                val report = server.nextReport()
                if (report == null) {
                    say("blink ${i + 1}: no answer")
                } else {
                    val gap = report - t
                    withContext(Dispatchers.Main) { deltas = deltas + gap }
                    say("blink ${i + 1}: the other phone flashed $gap ms later")
                }
            }
            setStatus("Finished.")
        }
    }

    // A phone reads a tag. Contacts and links wait for a tap before anything opens.
    LaunchedEffect(experiment) {
        nfcManager.readNdef.collect { message ->
            if (message == null) return@collect
            nfcManager.consumeReadNdef()
            when (experiment) {
                Experiment.WIFI -> {
                    val credential = PlaygroundNdef.parseWifi(message)
                    status = if (credential == null) "Not a Wi-Fi tag." else WifiSuggestions.add(context, credential)
                }
                Experiment.CONTACT -> {
                    contactIn = PlaygroundNdef.parseContact(message)
                    status = if (contactIn == null) "Not a contact card." else "Contact card received. Tap Save contact."
                }
                Experiment.LINK -> {
                    val url = PlaygroundNdef.parseLink(message)
                    linkIn = url?.takeIf { PlaygroundNdef.isWebUrl(it) }
                    status = when {
                        url == null -> "Not a link tag."
                        linkIn == null -> "Refused: only web links (http or https) are opened."
                        else -> "Link received. Tap Open link to visit it."
                    }
                }
                Experiment.BLINK -> {
                    val target = PlaygroundNdef.parseTarget(message)
                    if (target == null) status = "Not a blink target." else startFollower(target)
                }
                else -> Unit
            }
        }
    }

    FlashExpressiveTheme(ThemeMode.SYSTEM) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Playground") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = null)
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Experimental. English only for now.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Experiment.entries.forEach { e ->
                            FilterChip(
                                selected = experiment == e,
                                onClick = {
                                    stopAll()
                                    experiment = e
                                },
                                label = { Text(e.label) }
                            )
                        }
                    }

                    if (experiment == Experiment.BLINK) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = leader, onClick = { leader = true }, label = { Text("Leader (card)") })
                            FilterChip(selected = !leader, onClick = { leader = false }, label = { Text("Follower (reader)") })
                        }
                        Text("Both phones must be on the same Wi-Fi network. Keep them touching until the test starts.")
                        if (leader) {
                            Button(onClick = { startLeader() }, enabled = !running.get()) { Text("Start blink test") }
                        }
                        OutlinedButton(onClick = { stopAll() }) { Text("Stop") }
                        if (deltas.isNotEmpty()) {
                            val sorted = deltas.sorted()
                            val median = sorted[sorted.size / 2]
                            val average = deltas.average().toLong()
                            Text(
                                "${deltas.size} blinks: median gap $median ms, average $average ms. " +
                                    "Includes network and screen delays on both phones.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (labMode != null) {
                        val isRelay = experiment == Experiment.RELAY
                        Text(labHint(experiment), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = sharing,
                                onClick = { sharing = true },
                                label = { Text(if (isRelay) "Forward (card)" else "Card") }
                            )
                            FilterChip(
                                selected = !sharing,
                                onClick = { sharing = false },
                                label = { Text(if (isRelay) "Receive (reader)" else "Reader") }
                            )
                        }
                        if (experiment == Experiment.HOLD) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    LabMode.STREAM to "1 MB stream",
                                    LabMode.PINGPONG to "Text ping-pong",
                                    LabMode.QUIET to "Quiet"
                                ).forEach { (mode, name) ->
                                    FilterChip(
                                        selected = holdMode == mode,
                                        onClick = { holdMode = mode },
                                        label = { Text(name) }
                                    )
                                }
                            }
                            Text("Choose the variant before touching.")
                        }
                        if (isRelay && sharing) {
                            Text("Chain so far: ${relayText.ifEmpty { "(empty)" }}")
                            OutlinedButton(onClick = { NfcLab.relay = "start".toByteArray(Charsets.UTF_8) }) {
                                Text("Start a new chain here")
                            }
                        }
                        Text(
                            when {
                                sharing -> "Keep this phone on this screen while the other one touches it."
                                else -> "Touch the other phone and hold still. Lift to end the touch."
                            }
                        )
                        LabReport(snap, wifi)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = sharing, onClick = { sharing = true }, label = { Text("Share (card)") })
                            FilterChip(selected = !sharing, onClick = { sharing = false }, label = { Text("Receive (reader)") })
                        }
                        if (sharing) {
                            when (experiment) {
                                Experiment.WIFI -> {
                                    OutlinedTextField(
                                        value = ssid,
                                        onValueChange = { ssid = it },
                                        label = { Text("Network name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = { Text("Password (empty for an open network)") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "Anyone who touches this phone can read the password. Use a network you control.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Experiment.CONTACT -> {
                                    OutlinedTextField(
                                        value = contactName,
                                        onValueChange = { contactName = it },
                                        label = { Text("Name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = contactPhone,
                                        onValueChange = { contactPhone = it },
                                        label = { Text("Phone (optional)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = contactEmail,
                                        onValueChange = { contactEmail = it },
                                        label = { Text("Email (optional)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "Only what you type here is shared. Your contacts are not read.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Experiment.LINK -> {
                                    OutlinedTextField(
                                        value = link,
                                        onValueChange = { link = it },
                                        label = { Text("Web address") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                else -> Unit
                            }
                        } else {
                            Text(
                                when (experiment) {
                                    Experiment.WIFI -> "Touch a phone that is sharing a network. Android may ask you to confirm."
                                    Experiment.CONTACT -> "Touch a phone that is sharing a contact card, then tap Save contact."
                                    Experiment.LINK -> "Touch a phone that is sharing a link, then tap Open link."
                                    else -> ""
                                }
                            )
                        }

                        contactIn?.let { received ->
                            Text("${received.name}${if (received.phone.isNotBlank()) " · ${received.phone}" else ""}")
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    type = ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.NAME, received.name)
                                    putExtra(ContactsContract.Intents.Insert.PHONE, received.phone)
                                    putExtra(ContactsContract.Intents.Insert.EMAIL, received.email)
                                }
                                status = if (runCatching { context.startActivity(intent) }.isSuccess) {
                                    "Opened the contacts app."
                                } else {
                                    "No contacts app found."
                                }
                            }) { Text("Save contact") }
                        }
                        linkIn?.let { url ->
                            Text(url, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                status = if (runCatching { context.startActivity(intent) }.isSuccess) {
                                    "Opened $url"
                                } else {
                                    "No app can open this link."
                                }
                            }) { Text("Open link") }
                        }
                    }

                    if (labMode == null) {
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (flashOn) {
                Box(Modifier.fillMaxSize().background(Color.White))
            }
        }
    }
}

private fun labHint(experiment: Experiment): String = when (experiment) {
    Experiment.STOPWATCH -> "The card serves the handshake. The reader touches, then lifts. Shows the time from touch to select and to the first answer."
    Experiment.RACE -> "Touch the phones once. The card records when its first command arrived; the reader sends its touch time over Wi-Fi, so the gap can be compared."
    Experiment.TAP -> "Hold the two phones and tap them together on a table. Each accelerometer records the jolt; the reader sends its jolt times over NFC."
    Experiment.RELAY -> "Start a chain on one phone (Forward). Each next phone touches it as Receive (reader), adds its hop time, then switches to Forward."
    Experiment.FIELD -> "The reader sends small pings while the phones stay together. The card reports how long they were in contact and the longest pause."
    Experiment.HOLD -> "Pick a variant before touching. 1 MB stream pulls a photo over and over, text ping-pong trades messages, quiet syncs once and then sends nothing."
    else -> ""
}

@Composable
private fun LabReport(snap: LabSnapshot, wifi: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Role: ${snap.role.ifEmpty { "none yet" }} · touches: ${snap.touches}")
        if (snap.sessionMs > 0) Text("Time in contact: ${snap.sessionMs} ms")
        Text("Moved: ${snap.bytes / 1024} KB · speed: ${"%.1f".format(snap.speedKBs)} KB/s")
        if (snap.rtt.isNotEmpty()) {
            Text("Round trip: min ${snap.rtt[0]} ms · median ${snap.rtt[1]} ms · max ${snap.rtt[2]} ms")
        }
        if (snap.maxGapMs > 0) Text("Longest pause between commands: ${snap.maxGapMs} ms")
        if (snap.passes > 0) Text("1 MB photos sent: ${snap.passes}")
        if (snap.races.isNotEmpty()) {
            Text("Touch race, ms the card's first command came after the reader's touch: ${snap.races.joinToString()}")
        }
        if (snap.taps.isNotEmpty()) Text(snap.taps)
        if (snap.tag.isNotEmpty()) Text("Tag: ${snap.tag}")
        if (snap.lostAt.isNotEmpty()) {
            Text(
                "Connection lost at ${snap.lostAt}: ${snap.lostReason}",
                color = MaterialTheme.colorScheme.error
            )
        } else if (snap.sessionMs > 0) {
            Text("No drop seen by this phone.")
        }
        if (snap.note.isNotEmpty()) Text(snap.note)
        if (wifi.isNotEmpty()) Text(wifi)
        Text(
            "Android does not expose NFC field strength. Round-trip time is the closest NFC measure; " +
                "the Wi-Fi signal is for the network both phones use.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
