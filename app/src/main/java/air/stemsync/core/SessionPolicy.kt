package air.stemsync.core

/** Consent is local; Nearby's successful result additionally confirms the remote decision. */
class ConnectionGate {
    private val approved=mutableSetOf<String>()
    private val connected=mutableSetOf<String>()
    fun approve(id: String) { approved.add(id) }
    fun establish(id: String, successful: Boolean): Boolean {
        if (!successful || id !in approved) { disconnect(id); return false }
        connected.add(id); return true
    }
    fun canExchange(id: String)=id in connected
    fun disconnect(id: String) { approved.remove(id); connected.remove(id) }
    fun clear() { approved.clear(); connected.clear() }
}
object SessionPolicy {
    fun guestMaySend(kind: Kind)=kind in setOf(Kind.SYNC_REQUEST,Kind.NEED,Kind.FILE_READY,Kind.FILE_FAILED,Kind.READY,Kind.ACK,Kind.LEAVE,Kind.ERROR)
    fun mayStart(localPrepared: Boolean, connectedReadiness: List<Boolean>)=localPrepared && connectedReadiness.all { it }
}
