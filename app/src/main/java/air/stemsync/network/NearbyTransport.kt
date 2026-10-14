package air.stemsync.network

import android.content.Context
import air.stemsync.core.ConnectionGate
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await
import java.io.File

/** The SDK selects the radio transport; no Bluetooth-only or Wi-Fi claim is made. */
class NearbyTransport(context: Context, private val events: Events) {
    interface Events {
        fun found(id: String, name: String)
        fun lost(id: String)
        fun authenticate(id: String, name: String, digits: String)
        fun connected(id: String)
        fun disconnected(id: String)
        fun failure(message: String)
        fun bytes(id: String, bytes: ByteArray, receivedNs: Long)
        fun file(id: String, payload: Payload)
        fun progress(id: String, update: PayloadTransferUpdate)
    }
    private val client = Nearby.getConnectionsClient(context)
    private val gate = ConnectionGate()
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            if (!gate.canExchange(endpointId)) { client.cancelPayload(payload.id); return }
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { events.bytes(endpointId, it, now) }
                Payload.Type.FILE -> events.file(endpointId, payload)
                else -> client.cancelPayload(payload.id)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (gate.canExchange(endpointId)) events.progress(endpointId, update)
        }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { events.authenticate(id, info.endpointName, info.authenticationDigits) }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (gate.establish(id, result.status.isSuccess)) { events.connected(id) }
            else { gate.disconnect(id); client.disconnectFromEndpoint(id); events.failure("Connection rejected or failed (${result.status.statusCode}). Try connecting again.") }
        }
        override fun onDisconnected(id: String) { gate.disconnect(id); events.disconnected(id) }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { events.found(id, info.endpointName) }
        override fun onEndpointLost(id: String) { events.lost(id) }
    }
    suspend fun advertise(name: String) = client.startAdvertising(name, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()).await()
    suspend fun discover() = client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()).await()
    fun request(id: String, name: String) { client.requestConnection(name, id, lifecycle).report() }
    fun accept(id: String) { gate.approve(id); client.acceptConnection(id, payloads).report() }
    fun reject(id: String) { gate.disconnect(id); client.rejectConnection(id).report() }
    fun send(id: String, bytes: ByteArray) { if (gate.canExchange(id)) client.sendPayload(id, Payload.fromBytes(bytes)).report() }
    fun sendFile(id: String, payload: Payload) { if (gate.canExchange(id)) client.sendPayload(id, payload).report() }
    fun payload(file: File): Payload = Payload.fromFile(file)
    fun cancel(id: Long) { client.cancelPayload(id) }
    fun disconnect(id: String) { client.disconnectFromEndpoint(id); gate.disconnect(id) }
    fun stopDiscovery() = client.stopDiscovery()
    fun stop() { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints(); gate.clear() }
    private fun <T> Task<T>.report() { addOnFailureListener { events.failure(it.message ?: "Nearby connection failed") } }
    companion object { const val SERVICE = "air.stemsync.offline.v1" }
}
