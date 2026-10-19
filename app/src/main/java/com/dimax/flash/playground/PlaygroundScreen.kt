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
    WIFI("Wi-Fi tag"), CONTACT("Contact card"), LINK("Link tag"), BLINK("Blink test")
}

/**
 * Experiments with NFC and the phones. Experimental and English-only for now.
 * - Wi-Fi tag: one phone shares a network as a tag, the other reads it and offers it to Android.
 * - Contact card: one phone shares typed-in contact details as a vCard; the other saves them after a tap.
 * - Link tag: one phone shares a web address; the other opens it after a tap (web links only).
 * - Blink test: the leader fires blinks at unannounced moments; the follower flashes when it hears
 *   about each one, and both phones report their times, so the gap can be measured. NFC pairs the
 *   phones; the timing runs over Wi-Fi.
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
    var status by remember { mutableStateOf("Pick an experiment.") }
    var log by remember { mutableStateOf(listOf<String>()) }
    var deltas by remember { mutableStateOf(listOf<Long>()) }
    var flashOn by remember { mutableStateOf(false) }
    var contactIn by remember { mutableStateOf<ContactCard?>(null) }
    var linkIn by remember { mutableStateOf<String?>(null) }
    var blinkCard by remember { mutableStateOf<android.nfc.NdefMessage?>(null) }
    var blinkServer by remember { mutableStateOf<BlinkServer?>(null) }
    val running = remember { AtomicBoolean(false) }

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
                                Experiment.BLINK -> Unit
                            }
                        } else {
                            Text(
                                when (experiment) {
                                    Experiment.WIFI -> "Touch a phone that is sharing a network. Android may ask you to confirm."
                                    Experiment.CONTACT -> "Touch a phone that is sharing a contact card, then tap Save contact."
                                    Experiment.LINK -> "Touch a phone that is sharing a link, then tap Open link."
                                    Experiment.BLINK -> ""
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

                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (flashOn) {
                Box(Modifier.fillMaxSize().background(Color.White))
            }
        }
    }
}
