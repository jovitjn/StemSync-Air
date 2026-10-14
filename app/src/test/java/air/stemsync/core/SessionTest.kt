package air.stemsync.core
import org.junit.Assert.*
import org.junit.Test
class SessionTest {
    @Test fun newHostHasNoImplicitGuests() { assertFalse(ConnectionGate().canExchange("guest")) }
    @Test fun successWithoutUserConsentRejected() { val g=ConnectionGate(); assertFalse(g.establish("guest",true)); assertFalse(g.canExchange("guest")) }
    @Test fun authenticatedGuestCanExchange() { val g=ConnectionGate(); g.approve("guest"); assertTrue(g.establish("guest",true)); assertTrue(g.canExchange("guest")) }
    @Test fun remoteRejectionPreventsExchange() { val g=ConnectionGate(); g.approve("guest"); assertFalse(g.establish("guest",false)); assertFalse(g.canExchange("guest")) }
    @Test fun guestDisconnectRevokesConsent() { val g=ConnectionGate(); g.approve("guest"); g.establish("guest",true); g.disconnect("guest"); assertFalse(g.canExchange("guest")); assertFalse(g.establish("guest",true)) }
    @Test fun hostDepartureClearsAllConnections() { val g=ConnectionGate(); g.approve("host"); g.establish("host",true); g.clear(); assertFalse(g.canExchange("host")) }
    @Test fun unpreparedParticipantBlocksStart() { assertFalse(SessionPolicy.mayStart(true,listOf(true,false))) }
    @Test fun unpreparedHostBlocksStart() { assertFalse(SessionPolicy.mayStart(false,listOf(true))) }
    @Test fun allPreparedAllowsStart() { assertTrue(SessionPolicy.mayStart(true,listOf(true))) }
    @Test fun guestsCannotPublishTimelineOrManifest() { assertFalse(SessionPolicy.guestMaySend(Kind.TIMELINE)); assertFalse(SessionPolicy.guestMaySend(Kind.MANIFEST)); assertTrue(SessionPolicy.guestMaySend(Kind.READY)) }
}
