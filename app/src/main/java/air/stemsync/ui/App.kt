package air.stemsync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import air.stemsync.*
import air.stemsync.core.TransferPhase
import kotlin.math.*

private val Ink=Color(0xFF101916)
private val Cream=Color(0xFFF0EADB)
private val Amber=Color(0xFFE9AD78)
private val Muted=Color(0xFFA8B7AD)
@Composable fun StemTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Amber,onPrimary=Ink,background=Ink,surface=Color(0xFF1C2923),onSurface=Cream,onBackground=Cream,secondary=Color(0xFFB5C9A8)),content=content)
}
@Composable fun StemSyncApp(s: UiState, vm: SessionViewModel, host: ()->Unit, join: ()->Unit, reconnect: ()->Unit, import: ()->Unit, export: ()->Unit, settings: ()->Unit, radios: ()->Unit) {
    var panel by remember { mutableStateOf("music") }
    StemTheme {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide=maxWidth>720.dp
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if(wide) 36.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Column { Text("STEMSYNC AIR",fontSize=15.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp); Text(if(s.role=="home") "OFFLINE MUSIC, SHARED" else "${s.role.uppercase()} SESSION",fontSize=10.sp,color=Muted,letterSpacing=2.sp) }
                        TextButton(onClick={ panel=if(panel=="help") "music" else "help" }) { Text(if(panel=="help") "Back" else "Help") }
                    }
                    s.error?.let { message ->
                        Surface(color=Color(0xFF4A2B27),shape=RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text(message); TextButton(onClick=vm::clearError) { Text("Dismiss") } } }
                    }
                    when {
                        panel=="help" -> Help(s,vm,settings,radios)
                        s.role=="home" -> Home(host,join)
                        else -> {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                TextButton(onClick={ panel=if(panel=="diagnostics") "music" else "diagnostics" }) { Text(if(panel=="diagnostics") "← Music" else "Connection details") }
                                TextButton(onClick=vm::leave) { Text("Leave") }
                            }
                            if(panel=="diagnostics") Diagnostics(s,export)
                            else {
                                Text(s.status,color=Muted,fontSize=14.sp)
                                if(s.role=="guest" && s.peers.isEmpty()) {
                                    Text("Find your people.",fontSize=32.sp,fontWeight=FontWeight.SemiBold)
                                    Text("Ask the other device to host a session. Keep both screens open.",color=Muted)
                                    s.hosts.forEach { (id,name) -> OutlinedButton(onClick={vm.connect(id)},modifier=Modifier.fillMaxWidth()) { Text(name) } }
                                    OutlinedButton(onClick=reconnect) { Text(if(s.reconnects>0 || s.tracks.isNotEmpty()) "Reconnect" else "Search again") }
                                }
                                if(wide) Row(horizontalArrangement=Arrangement.spacedBy(40.dp)) {
                                    Column(Modifier.weight(1f)) { Artwork(Modifier.fillMaxWidth().aspectRatio(1f),s.tracks.firstOrNull()?.songId=="afterglow-v1") }
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(20.dp)) { PlayerPanel(s,vm); Room(s,vm,import) }
                                } else {
                                    Artwork(Modifier.fillMaxWidth().aspectRatio(1.25f),s.tracks.firstOrNull()?.songId=="afterglow-v1")
                                    PlayerPanel(s,vm); Room(s,vm,import)
                                }
                            }
                        }
                    }
                    Text("Your music. Every speaker. Together.",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
                }
            }
        }
        s.auth?.let { a -> AlertDialog(onDismissRequest={},title={Text("Same code on both screens?")},text={Column(verticalArrangement=Arrangement.spacedBy(18.dp)) { Text(a.name); Text(a.digits,fontSize=40.sp,fontWeight=FontWeight.Bold,letterSpacing=6.sp); Text("Compare this code with the other device before allowing music and playback messages.") }},confirmButton={Button(onClick={vm.confirm(true)}){Text("Codes match")}},dismissButton={TextButton(onClick={vm.confirm(false)}){Text("Reject")}}) }
    }
}
@Composable fun Home(host: ()->Unit,join: ()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(24.dp),modifier=Modifier.widthIn(max=600.dp)) {
        Artwork(Modifier.fillMaxWidth().height(260.dp))
        Text("A little closer.\nA little louder.",fontSize=42.sp,lineHeight=46.sp,fontWeight=FontWeight.SemiBold)
        Text("Music together. No internet needed.",fontSize=18.sp,color=Muted)
        Text("Share a song with a nearby device. Play it together, or give each speaker its own instrument.",color=Muted)
        Button(onClick=host,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(14.dp)) { Text("Host Session",fontSize=17.sp) }
        OutlinedButton(onClick=join,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(14.dp)) { Text("Join Session",fontSize=17.sp) }
        Text("Nearby access connects your devices over local radios. On older Android versions, discovery also needs precise Location permission and received files need legacy storage access. Audio stays on your devices.",fontSize=12.sp,color=Muted)
    }
}
@Composable private fun Artwork(modifier: Modifier,original: Boolean=true) {
    Box(modifier.background(Color(0xFF253A30),RoundedCornerShape(22.dp))) {
        Canvas(Modifier.fillMaxSize().padding(24.dp)) {
            val center=Offset(size.width*.58f,size.height*.47f); val radius=min(size.width,size.height)*.36f
            drawCircle(Amber.copy(alpha=.95f),radius,center)
            for(i in 1..9) drawCircle(Ink.copy(alpha=.35f),radius*i/10,center,style=Stroke(1.5f))
            drawCircle(Ink,radius*.12f,center)
            for(i in 0..6) { val y=size.height*(.64f+i*.048f); drawLine(Color(0xFF253A30),Offset(0f,y),Offset(size.width,y-size.height*.16f),strokeWidth=5f) }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) { Text(if(original) "AFTERGLOW" else "YOUR MUSIC",fontSize=12.sp,letterSpacing=4.sp,fontWeight=FontWeight.Bold); Text(if(original) "ON THE LAKE" else "LOCAL SESSION",fontSize=10.sp,letterSpacing=3.sp,color=Cream.copy(alpha=.7f)) }
    }
}
@Composable private fun PlayerPanel(s: UiState,vm: SessionViewModel) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(s.tracks.firstOrNull()?.title ?: "Waiting for the music",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
        Text(if(s.tracks.firstOrNull()?.songId=="afterglow-v1") "Original session · 120 BPM · A minor" else "Local audio · synchronized timeline",color=Muted,fontSize=13.sp)
        var seeking by remember { mutableStateOf<Float?>(null) }
        Slider(value=seeking ?: s.position.toFloat(),onValueChange={seeking=it},onValueChangeFinished={seeking?.let{vm.seek(it.toLong())};seeking=null},valueRange=0f..s.duration.coerceAtLeast(1).toFloat(),enabled=s.role=="host" && s.tracks.isNotEmpty())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(time(s.position),color=Muted); Text(time(s.duration),color=Muted) }
        if(s.role=="host") Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick=vm::playPause,enabled=s.prepared && !s.busy,modifier=Modifier.weight(1f).height(56.dp)) { Text(if(s.playing) "Pause" else "Play together",fontSize=18.sp) }
            OutlinedButton(onClick=vm::stop,enabled=s.tracks.isNotEmpty()) { Text("Stop") }
        } else Text(if(s.sessionReady) "Ready · following the host" else if(s.prepared && !s.syncReady) s.timingStatus else "Preparing files and timing…",color=Amber)
        Text("THIS SPEAKER",fontSize=11.sp,letterSpacing=2.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            s.tracks.forEach { track -> FilterChip(selected=s.selected==track.id,onClick={vm.select(track.id)},enabled=track.id in s.cachedIds,label={Text(track.stem)}) }
        }
        Text("Local volume",fontSize=12.sp,color=Muted)
        Slider(value=s.volume,onValueChange=vm::volume)
    }
}
@Composable private fun Room(s: UiState,vm: SessionViewModel,import: ()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color=Muted.copy(alpha=.2f))
        Text("IN THE ROOM",fontSize=11.sp,letterSpacing=2.sp,color=Muted)
        Text("You · ${if(s.prepared) "audio prepared" else "preparing"}")
        s.peers.forEach { Text("${it.name} · ${if(s.role=="guest") "host connected" else if(it.ready) "ready" else "connected / preparing"}",color=if(it.ready) Amber else Muted) }
        if(s.peers.isEmpty() && s.role=="host") Text("On the other device: Join Session, select this room, then compare the verification code.",color=Muted)
        if(s.role=="host") FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=import,enabled=!s.busy) { Text("Import song / stems") }
            TextButton(onClick=vm::loadDemo,enabled=!s.busy) { Text("Original demo") }
        }
        if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.peers.isNotEmpty()) TextButton(onClick=vm::retryMusic) { Text("Send / retry music") }
        s.transfers.forEach { t ->
            Text("${t.name} · ${t.phase.name.lowercase().replaceFirstChar { it.uppercase() }}",fontSize=12.sp)
            if(t.phase==TransferPhase.TRANSFERRING || t.phase==TransferPhase.QUEUED) LinearProgressIndicator(progress={if(t.total>0) (t.bytes.toFloat()/t.total).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth())
        }
        if(s.transfers.any { it.phase==TransferPhase.TRANSFERRING || it.phase==TransferPhase.QUEUED }) TextButton(onClick=vm::cancelTransfers) { Text("Cancel transfers") }
    }
}
@Composable private fun Diagnostics(s: UiState,export: ()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("Between the speakers",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
        Text("Live observations from this connection. No acoustic measurement is available.",color=Muted)
        val metrics=listOf("Connection" to s.status,"Mean message RTT" to metric(s.rtt,"ms"),"RTT variation (population SD)" to metric(s.jitter,"ms"),"Estimated host − guest clock" to metric(s.offset,"ms"),"Last clock sample" to metric(s.syncAge,"s ago"),"Network timing uncertainty budget" to metric(s.timingUncertainty,"ms"),"Timing readiness" to s.timingStatus,"Estimated playback timeline error" to (s.timelineError?.let{"$it ms"} ?: "Not measured"),"Last file transfer throughput" to metric(s.throughput,"MiB/s"),"Reconnections" to s.reconnects.toString())
        metrics.forEach { (name,value) -> Column { Text(name,color=Muted,fontSize=12.sp); Text(value,fontSize=18.sp); HorizontalDivider(Modifier.padding(top=12.dp),color=Muted.copy(alpha=.15f)) } }
        Text("RTT and offset are measured on the guest. Nearby chooses the local transport; its radio type is not exposed here. Player position excludes unknown speaker and output-buffer latency.",color=Muted,fontSize=12.sp)
        OutlinedButton(onClick=export) { Text("Export diagnostics") }
    }
}
@Composable private fun Help(s: UiState,vm: SessionViewModel,settings: ()->Unit,radios: ()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Make room for music.",fontSize=30.sp,fontWeight=FontWeight.SemiBold)
        Text("1. Keep Bluetooth and Wi-Fi enabled on both devices. Mobile data and internet are unnecessary.\n\n2. Host on one device and join on the other. Compare the code on both screens.\n\n3. Wait for every file to be verified and both speakers to be ready. The host controls play, pause and seek.\n\n4. Choose Full mix on both devices, or different instruments. Keep the app in the foreground.")
        OutlinedButton(onClick=radios) { Text("Wireless settings") }; OutlinedButton(onClick=settings) { Text("App settings & permissions") }
        Text("Speaker delay",fontSize=22.sp)
        Text("${s.calibrationMs.roundToInt()} ms · positive delays this device",color=Amber)
        Slider(value=s.calibrationMs,onValueChange=vm::calibrate,valueRange=-300f..300f,steps=119)
        Text("If this speaker sounds early, add delay in small steps. Use the same drum stem on both devices, listen halfway between them, and adjust until the double hit is reduced. This is a listening adjustment, not a measurement.",color=Muted)
        Text("Imports: select one MP3 song, one WAV song, or up to six aligned 16-bit PCM WAV stems. MP3 is converted locally for sharing; decoded audio is limited to 100 MiB. Multiple files must share exact PCM frame count and sample rate and are treated as stems. Alignment remains your responsibility.",color=Muted)
        Text("StemSync Air 0.2.1 · one host + one guest\nOriginal demo: Afterglow on the lake\nOffline file distribution followed by local playback. Google Play services required. Acoustic synchronization varies by device. Background playback is not supported.",fontSize=12.sp,color=Muted)
    }
}
private fun time(ms: Long)="%d:%02d".format(ms/60000,(ms/1000)%60)
private fun metric(value: Double?,unit: String)=value?.let{"%.2f %s".format(it,unit)} ?: "Not measured"
