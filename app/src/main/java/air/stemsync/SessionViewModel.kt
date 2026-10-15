package air.stemsync

import android.app.Application
import android.os.SystemClock
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import air.stemsync.audio.*
import air.stemsync.core.*
import air.stemsync.network.NearbyTransport
import androidx.media3.common.Player
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

fun nowNs() = SystemClock.elapsedRealtimeNanos()
data class Peer(val id: String, val name: String, val ready: Boolean = false, val acknowledged: Long = -1)
data class Auth(val id: String, val name: String, val digits: String)
data class Transfer(val payloadId: Long, val name: String, val phase: TransferPhase, val bytes: Long = 0, val total: Long = 0)
data class UiState(
    val role: String = "home", val status: String = "Your music. Every speaker. Together.",
    val hosts: Map<String,String> = emptyMap(), val peers: List<Peer> = emptyList(), val auth: Auth? = null,
    val tracks: List<Track> = emptyList(), val cachedIds: Set<String> = emptySet(), val selected: String? = null,
    val transfers: List<Transfer> = emptyList(), val playing: Boolean = false, val position: Long = 0, val duration: Long = 32000,
    val prepared: Boolean = false, val sessionReady: Boolean = false, val error: String? = null, val busy: Boolean = false,
    val rtt: Double? = null, val jitter: Double? = null, val offset: Double? = null, val syncAge: Double? = null,
    val timelineError: Long? = null, val throughput: Double? = null, val reconnects: Int = 0,
    val timingStatus: String = "Collecting timing samples…", val timingUncertainty: Double? = null,
    val volume: Float = .8f, val calibrationMs: Float = 0f, val syncReady: Boolean = false
)

class SessionViewModel(app: Application) : AndroidViewModel(app), NearbyTransport.Events {
    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()
    private val library = Library(app)
    private val clock = ClockEstimator()
    private val timeline = TimelineStore()
    private val drift = DriftController()
    private val network = NearbyTransport(app, this)
    private val player = PlaybackEngine(app, { updatePrepared() }, { failure(it) })
    private var sessionId = ""
    private val selfId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var revision = 0L
    private var songRevision = 0L
    private var manifestRevision = -1L
    private var manifestSequence = -1L
    private var host: String? = null
    private var everConnected = false
    private var foreground = true
    private var lastReady: Boolean? = null
    private var lastApplied = -1L
    private var endedRevision = -1L
    private var lastDrift = 0L
    private var selectedTrack: Track? = null
    private var localTracks = emptyList<Track>()
    private var manifest = emptyList<Track>()
    private val names = mutableMapOf<String,String>()
    private val dedup = LinkedHashSet<String>()
    private val pendingSync = mutableSetOf<Long>()
    private val payloads = mutableMapOf<Long,Payload>()
    private val outgoing = mutableMapOf<Long, Pair<String,Track>>()
    private val incoming = mutableMapOf<Long, Pair<String,Track?>>()
    private val started = mutableMapOf<Long,Long>()
    private val verifying = mutableSetOf<Long>()
    private val gate = TransferGate()
    private val manifestScope = ManifestScope()
    private val incomingScopes = mutableMapOf<Long,ManifestScope.Snapshot>()
    private var manifestJob: Job? = null
    private var generation = 0
    private var searchJob: Job? = null
    private var libraryJob: Job? = null
    init {
        player.player.volume = state.value.volume
        viewModelScope.launch { while (isActive) { tick(); delay(20) } }
        viewModelScope.launch { while (isActive) { delay(750); synchronize(); expireTransfers() } }
    }
    private fun mutate(f: (UiState)->UiState) { mutableState.update(f) }
    private fun message(kind: Kind, tracks: List<Track> = emptyList(), contentId: String? = null, payloadId: Long? = null,
        snapshot: Timeline? = null, t1: Long? = null, t2: Long? = null, t3: Long? = null, ready: Boolean? = null,
        rev: Long? = null, detail: String? = null) = Message(sessionId=sessionId,senderId=selfId,messageId=UUID.randomUUID().toString(),sequenceNumber=++sequence,timestampNs=nowNs(),kind=kind,tracks=tracks,contentId=contentId,payloadId=payloadId,timeline=snapshot,t1=t1,t2=t2,t3=t3,ready=ready,revision=rev,detail=detail)
    private fun send(id: String, m: Message) { runCatching { network.send(id,Protocol.encode(m)) }.onFailure { failure(it.message ?: "Message failed") } }
    private fun broadcast(m: Message) { state.value.peers.forEach { send(it.id,m) } }
    fun hostSession() {
        reset(); sessionId=UUID.randomUUID().toString()
        mutate { it.copy(role="host",status="Opening your room…",busy=true) }
        viewModelScope.launch {
            runCatching { network.advertise("StemSync · ${android.os.Build.MODEL.take(35)}") }
                .onSuccess { mutate { it.copy(status="Room open · invite a nearby device",busy=false) }; loadDemo() }
                .onFailure { failure("Could not open room: ${it.message}"); mutate { it.copy(busy=false,status="Room could not open. Check permissions and radios, then host again.") } }
        }
    }
    fun joinSession() {
        reset(); mutate { it.copy(role="guest",status="Searching for nearby rooms…") }; search()
    }
    fun reconnect() {
        if (state.value.role != "guest") return
        manifestScope.clear(); manifestJob?.cancel()
        host?.let { network.disconnect(it) }; host=null; clock.clear(); pendingSync.clear(); lastReady=null; lastApplied=-1; drift.reset(); player.player.pause()
        mutate { it.copy(peers=emptyList(),hosts=emptyMap(),status="Searching again · confirm the new code") }; search()
    }
    private fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            runCatching { network.discover() }.onFailure { failure("Search failed: ${it.message}") }
            delay(30_000)
            if (host == null) { network.stopDiscovery(); mutate { it.copy(status="Search stopped. Tap Search again if your host is ready.") } }
        }
    }
    fun connect(id: String) {
        if (host != null || state.value.auth != null) return
        mutate { it.copy(status="Connecting…",error=null) }; network.request(id,android.os.Build.MODEL.take(40))
    }
    fun confirm(accept: Boolean) {
        val auth=state.value.auth ?: return
        if (accept) { network.accept(auth.id); mutate { it.copy(status="Waiting for both devices to confirm…",auth=null) } }
        else { network.reject(auth.id); mutate { it.copy(auth=null,status="Connection declined") } }
    }
    override fun found(id: String, name: String) { names[id]=name; mutate { it.copy(hosts=it.hosts+(id to name)) } }
    override fun lost(id: String) { mutate { it.copy(hosts=it.hosts-id) } }
    override fun authenticate(id: String, name: String, digits: String) {
        // Required MVP is one guest. Explicit cap prevents accidental unsupported fanout.
        if (state.value.auth != null || state.value.peers.isNotEmpty()) { network.reject(id); return }
        names[id]=name; mutate { it.copy(auth=Auth(id,name.take(60),digits)) }
        viewModelScope.launch { delay(30_000); if (state.value.auth?.id==id) { network.reject(id); mutate { it.copy(auth=null,status="Verification timed out. Connect again.") } } }
    }
    override fun connected(id: String) {
        mutate { it.copy(peers=listOf(Peer(id,names[id] ?: "Nearby device")),status="Connected · preparing music",error=null,reconnects=it.reconnects + if (everConnected) 1 else 0) }
        everConnected=true
        if (state.value.role=="host") {
            send(id,message(Kind.HELLO)); if (manifest.isNotEmpty()) sendManifest(id)
        } else { manifestScope.clear(); host=id; lastApplied=-1; drift.reset(); network.stopDiscovery(); searchJob?.cancel(); clock.clear(); pendingSync.clear(); lastReady=null }
    }
    override fun disconnected(id: String) {
        mutate { it.copy(peers=it.peers.filterNot { p->p.id==id },auth=null,status=if (it.role=="host") "Guest disconnected · room remains open" else "Connection lost. Tap Reconnect to recover.") }
        incoming.filterValues { it.first==id }.keys.toList().forEach { failTransfer(it,"Connection interrupted") }
        outgoing.filterValues { it.first==id }.keys.toList().forEach { failTransfer(it,"Connection interrupted") }
        if (host==id) { manifestScope.clear(); manifestJob?.cancel(); host=null; lastApplied=-1; drift.reset(); player.player.pause(); clock.clear(); pendingSync.clear(); lastReady=null; mutate { it.copy(syncReady=false) } }
    }
    override fun failure(message: String) { mutate { it.copy(error=message.take(220)) } }
    fun clearError() { mutate { it.copy(error=null) } }
    fun leave() { reset(); mutate { UiState(volume=it.volume,calibrationMs=it.calibrationMs) } }
    private fun reset() {
        generation++; manifestScope.clear(); manifestJob?.cancel(); incomingScopes.clear(); searchJob?.cancel(); libraryJob?.cancel(); network.stop(); player.player.pause(); player.player.clearMediaItems()
        payloads.keys.toList().forEach { cleanup(it) }; incoming.clear(); outgoing.clear(); started.clear(); verifying.clear()
        host=null; selectedTrack=null; localTracks=emptyList(); sessionId=""; timeline.clear(); clock.clear(); drift.reset(); pendingSync.clear(); dedup.clear(); lastReady=null; lastApplied=-1; endedRevision=-1; revision=0; songRevision=0; manifestRevision=-1; manifestSequence=-1; manifest=emptyList(); everConnected=false
        mutate { UiState(volume=it.volume,calibrationMs=it.calibrationMs) }
    }
    fun loadDemo() {
        if (state.value.role!="host" || state.value.busy) return
        libraryJob?.cancel()
        libraryJob=viewModelScope.launch {
            mutate { it.copy(busy=true) }
            runCatching { withContext(Dispatchers.IO) { library.installDemo() } }
                .onSuccess { setSong(it) }.onFailure { failure("Demo could not load: ${it.message}") }
            mutate { it.copy(busy=false) }
        }
    }
    fun importAudio(uris: List<Uri>) {
        if (state.value.role!="host" || uris.isEmpty()) return
        libraryJob=viewModelScope.launch {
            mutate { it.copy(busy=true) }
            runCatching { withContext(Dispatchers.IO) {
                require(uris.size <= 6) { "Select at most six aligned PCM WAV files" }
                val tracks=uris.map { library.import(it, allowMp3=uris.size==1) }
                if (tracks.size==1) tracks else {
                    Wav.requireAligned(tracks.map { Wav.inspect(library.file(it)) })
                    require(tracks.map { it.id }.distinct().size == tracks.size) { "Choose different stem files" }
                    val song=tracks.first().songId
                    tracks.mapIndexed { index,t -> t.copy(songId=song,stem="Stem ${index+1}").also { library.remember(it) } }
                }
            } }.onSuccess { setSong(it) }.onFailure { failure("Import failed: ${it.message}") }
            mutate { it.copy(busy=false) }
        }
    }
    private fun setSong(tracks: List<Track>) {
        player.player.pause(); songRevision++; manifest=tracks; localTracks=(localTracks.filterNot { old->tracks.any { it.id==old.id } }+tracks)
        mutate { it.copy(tracks=tracks,peers=it.peers.map { p->p.copy(ready=false) },duration=tracks.first().durationMs) }
        refreshCache(); select(tracks.first().id)
        command(false,0)
        state.value.peers.forEach { sendManifest(it.id) }
    }
    private fun refreshCache() { mutate { it.copy(cachedIds=localTracks.map { t->t.id }.toSet()) } }
    private fun sendManifest(id: String) {
        send(id,message(Kind.MANIFEST,tracks=manifest,rev=songRevision)); timeline.latest?.let { send(id,message(Kind.TIMELINE,snapshot=it)) }
    }
    fun select(id: String) {
        val track=manifest.firstOrNull { it.id==id } ?: return
        if (id !in state.value.cachedIds) return
        val position=(timeline.current ?: timeline.latest)?.positionAt(hostNow()-state.value.calibrationMs.toLong()*1_000_000) ?: 0
        lastReady=null; drift.reset(); selectedTrack=track; player.load(id,library.file(track),position)
        lastApplied=-1
        mutate { it.copy(selected=id,prepared=false) }; updatePrepared()
    }
    fun volume(value: Float) { player.player.volume=value; mutate { it.copy(volume=value) } }
    fun calibrate(value: Float) { mutate { it.copy(calibrationMs=value) }; lastApplied=-1; drift.reset() }
    fun playPause() {
        val latest=timeline.latest
        val ended=state.value.position>=state.value.duration || latest?.let { it.playing && it.positionAt(hostNow())>=it.durationMs }==true
        val resume=latest?.playing!=true || ended
        val position=if (ended) 0 else if (!resume && latest!=null) latest.positionAt(nowNs()+1_000_000_000) else state.value.position
        command(resume,position)
    }
    fun seek(position: Long) = command(timeline.latest?.playing==true, position)
    fun stop() = command(false,0)
    private fun command(playing: Boolean, position: Long) {
        if (state.value.role!="host" || manifest.isEmpty()) return
        if (playing && (!SessionPolicy.mayStart(player.ready,state.value.peers.map { it.ready }))) { failure("Waiting for every connected device to verify files, prepare audio and have stable timing."); return }
        val track=manifest.first()
        val next=Timeline(sessionId,track.songId,++revision,playing,position.coerceIn(0,track.durationMs),nowNs()+1_000_000_000,track.durationMs)
        timeline.accept(next); broadcast(message(Kind.TIMELINE,snapshot=next))
        mutate { it.copy(status=if (playing) "Starting together…" else "Playback change scheduled") }
        viewModelScope.launch { delay(700); if (timeline.latest?.playbackRevision==next.playbackRevision && state.value.peers.any { it.acknowledged < next.playbackRevision }) {
            // Retry same authoritative revision. Receivers ACK duplicates without replaying them.
            broadcast(message(Kind.TIMELINE,snapshot=next)); mutate { it.copy(status="Awaiting playback acknowledgement") }
        } }
    }
    private fun hostNow() = if (state.value.role=="host") nowNs() else clock.hostTime(nowNs())
    private fun updatePrepared() {
        val ready=foreground && player.ready && state.value.selected in state.value.cachedIds && manifest.isNotEmpty() && manifest.all { it.id in state.value.cachedIds } && (state.value.role=="host" || clock.fresh(nowNs()))
        val time=nowNs()
        val timingStatus=if (state.value.role=="host") "Host clock · no estimate needed" else when(clock.quality(time)) {
            ClockEstimator.Quality.WARMING_UP -> "Collecting fresh timing samples…"
            ClockEstimator.Quality.STALE -> "Timing samples expired · waiting for the host"
            ClockEstimator.Quality.HIGH_RTT -> "Connection timing is delayed · waiting for a stable link"
            ClockEstimator.Quality.HIGH_UNCERTAINTY -> "Timing is uncertain · resynchronizing"
            ClockEstimator.Quality.READY -> "Timing ready"
        }
        mutate { it.copy(prepared=player.ready,sessionReady=ready,syncReady=if (it.role=="host") true else clock.fresh(time),timingStatus=timingStatus,timingUncertainty=if (it.role=="host") null else clock.uncertaintyMs(time)) }
        if (host!=null && sessionId.isNotBlank() && lastReady!=ready) { send(host!!,message(Kind.READY,ready=ready,contentId=manifest.firstOrNull()?.songId,rev=manifestRevision)); lastReady=ready }
    }
    private fun synchronize() {
        val id=host ?: return
        if (sessionId.isEmpty()) return
        val t=nowNs(); pendingSync.removeAll { t-it>5_000_000_000 }; pendingSync.add(t)
        send(id,message(Kind.SYNC_REQUEST,t1=t))
    }
    override fun bytes(id: String, bytes: ByteArray, receivedNs: Long) {
        runCatching {
            val m=Protocol.decode(bytes)
            require(state.value.peers.any { it.id==id })
            if (m.kind==Kind.HELLO && state.value.role=="guest" && id==host) {
                if (sessionId!=m.sessionId) { manifestScope.clear(); manifestJob?.cancel(); timeline.clear(); lastApplied=-1; manifestRevision=-1; manifestSequence=-1; sessionId=m.sessionId }
                require(sessionId.isNotBlank()); return@runCatching
            }
            require(m.sessionId==sessionId && sessionId.isNotBlank()) { "Session mismatch" }
            if (!dedup.add(m.messageId)) return@runCatching
            while (dedup.size>1024) dedup.remove(dedup.first())
            if (state.value.role=="host") hostMessage(id,m,receivedNs) else { require(id==host); guestMessage(id,m,receivedNs) }
        }.onFailure { failure("Rejected peer message: ${it.message}") }
    }
    private fun hostMessage(id: String, m: Message, received: Long) {
        require(SessionPolicy.guestMaySend(m.kind)) { "Guest cannot control host playback" }
        when (m.kind) {
            Kind.SYNC_REQUEST -> { val t1=requireNotNull(m.t1); require(t1>=0); send(id,message(Kind.SYNC_RESPONSE,t1=t1,t2=received,t3=nowNs())) }
            Kind.NEED -> manifest.firstOrNull { it.id==m.contentId }?.let { sendTrack(id,it) }
            Kind.FILE_READY -> {
                val pair=outgoing.entries.firstOrNull { it.value.first==id && it.value.second.id==m.contentId }
                pair?.let { transfer(it.key,it.value.second,TransferPhase.READY,it.value.second.size); cleanup(it.key) }
            }
            Kind.FILE_FAILED -> {
                outgoing.filterValues { it.first==id && it.second.id==m.contentId }.keys.toList().forEach { failTransfer(it,"Guest could not verify ${m.contentId}. Use Send / retry music.") }
            }
            Kind.READY -> if (m.contentId==manifest.firstOrNull()?.songId && m.revision==songRevision) mutate { it.copy(peers=it.peers.map { p->if (p.id==id) p.copy(ready=m.ready==true) else p }) }
            Kind.ACK -> mutate { it.copy(peers=it.peers.map { p->if (p.id==id) p.copy(acknowledged=maxOf(p.acknowledged,m.revision ?: -1)) else p }) }
            Kind.LEAVE -> { network.disconnect(id); disconnected(id) }
            else -> require(m.kind==Kind.ERROR) { "Guest cannot control host playback" }
        }
    }
    private fun guestMessage(id: String, m: Message, received: Long) {
        when (m.kind) {
            Kind.MANIFEST -> {
                if (m.sequenceNumber<=manifestSequence) return
                require(m.revision!=null && m.revision>=0)
                require(m.tracks.isNotEmpty() && m.tracks.map { it.id }.distinct().size==m.tracks.size)
                require(m.tracks.map { Triple(it.songId,it.durationMs,it.sampleRate) }.distinct().size==1)
                // Copy the manifest once. IO may finish after another song/session arrived.
                val snapshot=manifestScope.begin(sessionId,m.sequenceNumber,m.tracks)
                manifest=snapshot.tracks; manifestRevision=m.revision; manifestSequence=m.sequenceNumber; lastReady=null
                manifestJob?.cancel()
                if (selectedTrack !in snapshot.tracks) { player.player.pause(); player.player.clearMediaItems(); selectedTrack=null; mutate { it.copy(selected=null,prepared=false) } }
                incoming.keys.toList().forEach { network.cancel(it); cleanup(it) }
                mutate { it.copy(tracks=snapshot.tracks,cachedIds=emptySet(),duration=snapshot.tracks.first().durationMs) }
                manifestJob=viewModelScope.launch {
                    val verified=withContext(Dispatchers.IO) { library.verifiedTracks(snapshot.tracks) }
                    if (!manifestScope.accepts(snapshot) || host!=id) return@launch
                    localTracks=verified
                    refreshCache()
                    snapshot.tracks.filterNot { it.id in state.value.cachedIds }.forEach { send(id,message(Kind.NEED,contentId=it.id)) }
                    if (state.value.selected !in state.value.cachedIds || selectedTrack !in verified) verified.firstOrNull()?.let { select(it.id) }
                    updatePrepared()
                }
            }
            Kind.FILE_META -> {
                require(m.tracks.size==1); val t=m.tracks.single(); require(t in manifest)
                val pid=requireNotNull(m.payloadId); require(incoming.size<8 || pid in incoming)
                val scope=requireNotNull(manifestScope.current()); require(manifestScope.accepts(scope,t))
                gate.metadata(pid,t); incomingScopes[pid]=scope; incoming[pid]=id to t; started.putIfAbsent(pid,nowNs()); transfer(pid,t,TransferPhase.QUEUED,0); verify(pid)
            }
            Kind.SYNC_RESPONSE -> {
                val t1=requireNotNull(m.t1); require(pendingSync.remove(t1)) { "Unsolicited or stale sync reply" }
                clock.add(t1,requireNotNull(m.t2),requireNotNull(m.t3),received,player.player.isPlaying); updatePrepared()
            }
            Kind.TIMELINE -> {
                val next=requireNotNull(m.timeline); require(next.sessionId==sessionId)
                timeline.accept(next)
                send(id,message(Kind.ACK,rev=next.playbackRevision))
            }
            Kind.LEAVE -> { network.disconnect(id); disconnected(id) }
            Kind.ERROR -> failure(m.detail ?: "Peer error")
            else -> error("Unexpected host message")
        }
    }
    fun retryMusic() {
        if (state.value.role=="host") state.value.peers.forEach { sendManifest(it.id) }
        else host?.let { id -> manifest.filterNot { it.id in state.value.cachedIds }.forEach { send(id,message(Kind.NEED,contentId=it.id)) } }
    }
    private fun sendTrack(id: String, track: Track) {
        if (outgoing.values.any { it.first==id && it.second.id==track.id }) return
        runCatching {
            val p=network.payload(library.file(track)); outgoing[p.id]=id to track; payloads[p.id]=p; started[p.id]=nowNs()
            transfer(p.id,track,TransferPhase.QUEUED,0); send(id,message(Kind.FILE_META,tracks=listOf(track),payloadId=p.id)); network.sendFile(id,p)
        }.onFailure { failure("Send failed: ${it.message}") }
    }
    override fun file(id: String, payload: Payload) {
        if (state.value.role!="guest" || id!=host || incoming.size>=8 && payload.id !in incoming) { network.cancel(payload.id); payload.close(); return }
        if (payload.id in payloads) { payload.close(); return }
        payloads[payload.id]=payload; incoming.putIfAbsent(payload.id,id to null); started.putIfAbsent(payload.id,nowNs()); gate.arrived(payload.id); verify(payload.id)
    }
    override fun progress(id: String, update: PayloadTransferUpdate) {
        val pid=update.payloadId
        if (pid !in incoming && pid !in outgoing) return
        val t=incoming[pid]?.second ?: outgoing[pid]?.second
        if (update.totalBytes > MAX_FILE_BYTES || (t!=null && update.totalBytes>t.size)) { failTransfer(pid,"File too large"); return }
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> if (t!=null) transfer(pid,t,TransferPhase.TRANSFERRING,update.bytesTransferred)
            PayloadTransferUpdate.Status.SUCCESS -> {
                if (t!=null) {
                    val elapsed=nowNs()-(started[pid] ?: nowNs())
                    if (elapsed>0) mutate { it.copy(throughput=update.bytesTransferred.toDouble()*1e9/elapsed/1048576) }
                    transfer(pid,t,TransferPhase.VERIFYING,update.bytesTransferred)
                }
                if (pid in incoming) { gate.completed(pid); verify(pid) }
            }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> failTransfer(pid,"Song transfer interrupted. Retry.")
        }
    }
    private fun verify(pid: Long) {
        val track=gate.claim(pid) ?: return
        val scope=incomingScopes[pid] ?: return
        if (!manifestScope.accepts(scope,track)) { cleanup(pid); return }
        val p=payloads[pid] ?: return
        val uri=p.asFile()?.asUri() ?: run { failTransfer(pid,"Received file URI unavailable"); return }
        val endpoint=incoming[pid]?.first ?: return
        verifying.add(pid); transfer(pid,track,TransferPhase.VERIFYING,track.size)
        val gen=generation
        viewModelScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { library.receive(uri,track) }.also { runCatching { getApplication<Application>().contentResolver.delete(uri,null,null) } } }
            if (gen!=generation || pid !in incoming || !manifestScope.accepts(scope,track) || incomingScopes[pid]!=scope) return@launch
            result.onSuccess {
                localTracks=localTracks.filterNot { it.id==track.id }+track
                mutate { it.copy(cachedIds=it.cachedIds+track.id) }; transfer(pid,track,TransferPhase.READY,track.size)
                send(endpoint,message(Kind.FILE_READY,contentId=track.id))
                if (state.value.selected !in state.value.cachedIds || state.value.selected==track.id) select(track.id)
                updatePrepared()
            }.onFailure { transfer(pid,track,TransferPhase.FAILED,0); send(endpoint,message(Kind.FILE_FAILED,contentId=track.id)); failure("Could not verify ${track.stem}: ${it.message}") }
            cleanup(pid)
        }
    }
    private fun transfer(id: Long,t: Track,phase: TransferPhase,bytes: Long) {
        mutate { it.copy(transfers=(it.transfers.filterNot { x->x.payloadId==id }+Transfer(id,t.stem,phase,bytes,t.size)).takeLast(12)) }
    }
    fun cancelTransfers() { (incoming.keys+outgoing.keys).toList().forEach { failTransfer(it,"Transfer canceled. Retry when ready.") } }
    private fun failTransfer(pid: Long,reason: String) {
        network.cancel(pid); (incoming[pid]?.second ?: outgoing[pid]?.second)?.let { transfer(pid,it,TransferPhase.FAILED,0) }
        cleanup(pid); failure(reason)
    }
    private fun cleanup(pid: Long) {
        val p=payloads.remove(pid)
        if (pid in incoming && pid !in verifying) p?.asFile()?.asUri()?.let { uri ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { getApplication<Application>().contentResolver.delete(uri,null,null) } }
        }
        p?.close(); incomingScopes.remove(pid); incoming.remove(pid); outgoing.remove(pid); started.remove(pid); verifying.remove(pid); gate.remove(pid)
    }
    private fun expireTransfers() { started.filter { nowNs()-it.value>120_000_000_000L && it.key !in verifying }.keys.toList().forEach { failTransfer(it,"Transfer timed out. Retry music.") } }
    fun foreground(active: Boolean) {
        if (foreground==active) return
        foreground=active
        if (!active) { if (state.value.role=="host") command(false,state.value.position); player.player.pause() }
        else lastApplied=-1
        updatePrepared()
    }
    private fun tick() {
        val local=nowNs(); val hostTime=hostNow()-state.value.calibrationMs.toLong()*1_000_000
        val clockUsable=state.value.role=="host" || host!=null && clock.fresh(local)
        val current=if(clockUsable) timeline.at(hostTime) else timeline.current
        val valid=foreground && player.ready && state.value.selected in state.value.cachedIds && clockUsable && current!=null && hostTime>=current.effectiveHostTimeNs && current.trackId==manifest.firstOrNull()?.songId
        if (current!=null && valid) {
            val target=current.positionAt(hostTime)
            if (lastApplied!=current.playbackRevision) {
                player.player.setPlaybackSpeed(1f); player.player.seekTo(target)
                player.player.playWhenReady=current.playing && target<current.durationMs
                lastApplied=current.playbackRevision; drift.reset(); lastDrift=local
                mutate { it.copy(status=if(current.playing) "Playing on the shared timeline" else "Paused · ready for the next note") }
            }
            if (current.playing && local-lastDrift>=500_000_000L && player.player.isPlaying) {
                lastDrift=local
                val error=player.player.currentPosition-target
                val correction=drift.update(error)
                if (correction.seek) player.player.seekTo(target)
                player.player.setPlaybackSpeed(correction.speed)
                mutate { it.copy(timelineError=error) }
            }
            if (current.playing && current.positionAt(hostTime)>=current.durationMs && endedRevision!=current.playbackRevision) {
                endedRevision=current.playbackRevision
                player.player.pause(); player.player.seekTo(current.durationMs); player.player.setPlaybackSpeed(1f)
                mutate { it.copy(status="Song finished · play it again") }
            }
        } else if (player.player.isPlaying && state.value.role=="guest") { player.player.pause(); lastApplied=-1 }
        // UI and diagnostics updates at 5 Hz; scheduling checks at 50 Hz.
        if (local / 200_000_000 != lastUi / 200_000_000) {
            lastUi=local
            // Media3 may clamp an EOF seek to the final decodable sample.
            // Display the authoritative completed duration instead of a stuck 0:31.
            val displayPosition=if (current?.playing==true && endedRevision==current.playbackRevision && current.positionAt(hostTime)>=current.durationMs) current.durationMs else player.player.currentPosition.coerceAtLeast(0)
            mutate { it.copy(playing=player.player.isPlaying,position=displayPosition,rtt=clock.rttMs,jitter=clock.jitterMs,
                offset=clock.lastSampleNs?.let { clock.offsetNs/1e6 },syncAge=clock.lastSampleNs?.let { (local-it)/1e9 }) }
            updatePrepared()
        }
    }
    private var lastUi=0L
    fun diagnostics(): String = buildString {
        appendLine("StemSync Air diagnostics — actual app observations; acoustic accuracy UNVERIFIED")
        appendLine("role,status,rtt_mean_ms,rtt_population_sd_ms,estimated_host_minus_guest_ms,sync_age_s,estimated_timeline_error_ms,transfer_MiB_s,reconnections,timing_uncertainty_ms,timing_readiness")
        val s=state.value
        appendLine("${s.role},${s.status.replace(',',';')},${s.rtt ?: ""},${s.jitter ?: ""},${s.offset ?: ""},${s.syncAge ?: ""},${s.timelineError ?: ""},${s.throughput ?: ""},${s.reconnects},${s.timingUncertainty ?: ""},${s.timingStatus}")
        manifest.forEach { appendLine("content,${it.id},sha256,${it.sha256}") }
    }
    override fun onCleared() { reset(); player.release(); super.onCleared() }
}
