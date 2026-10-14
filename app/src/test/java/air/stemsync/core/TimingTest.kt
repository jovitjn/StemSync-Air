package air.stemsync.core
import org.junit.Assert.*
import org.junit.Test

class TimingTest {
    private fun sample(c: ClockEstimator, offset: Long, forward: Long=10, back: Long=10, processing: Long=2, n: Long=0, playing: Boolean=false): Boolean {
        val t1=1_000_000_000L+n*1_000_000_000
        return c.add(t1,t1+(forward+offset)*1_000_000,t1+(forward+offset+processing)*1_000_000,t1+(forward+back+processing)*1_000_000,playing)
    }
    @Test fun positiveOffset() { val c=ClockEstimator(); sample(c,37); assertEquals(37.0,c.offsetNs/1e6,1e-9); assertEquals(20.0,c.rttMs!!,1e-9) }
    @Test fun negativeOffset() { val c=ClockEstimator(); sample(c,-83); assertEquals(-83.0,c.offsetNs/1e6,1e-9) }
    @Test fun zeroOffset() { val c=ClockEstimator(); sample(c,0); assertEquals(0.0,c.offsetNs,0.0) }
    @Test fun hostConversion() { val c=ClockEstimator(); sample(c,37); assertEquals(2_037_000_000,c.hostTime(2_000_000_000)) }
    @Test fun asymmetryHasKnownBias() { val c=ClockEstimator(); sample(c,37,30,10); assertEquals(47.0,c.offsetNs/1e6,1e-9) }
    @Test fun hostProcessingRemovedFromRtt() { val c=ClockEstimator(); sample(c,37,10,10,400); assertEquals(20.0,c.rttMs!!,1e-9) }
    @Test fun jitterOutliersDoNotMoveBestEstimate() { val c=ClockEstimator(); repeat(8){sample(c,37,10,10,n=it.toLong())}; repeat(12){sample(c,37,200L+it,10,n=(it+8).toLong())}; assertEquals(37.0,c.offsetNs/1e6,1e-9); assertTrue(c.jitterMs!!>0) }
    @Test fun negativeTimestampsRejected() { assertFalse(ClockEstimator().add(-1,10,20,30)) }
    @Test fun reversedGuestTimeRejected() { assertFalse(ClockEstimator().add(100,101,102,99)) }
    @Test fun reversedHostTimeRejected() { assertFalse(ClockEstimator().add(100,102,101,110)) }
    @Test fun impossibleRttRejected() { assertFalse(ClockEstimator().add(100,100,200,110)) }
    @Test fun staleResponseRejected() { assertFalse(ClockEstimator().add(0,1,2,6_000_000_000)) }
    @Test fun activeOffsetSlewIsBounded() { val c=ClockEstimator(); repeat(5){sample(c,0,n=it.toLong())}; val before=c.offsetNs; repeat(24){sample(c,100,n=(it+5).toLong(),playing=true)}; assertTrue(c.offsetNs-before<=24*250_000); assertTrue(c.offsetNs>0) }
    @Test fun stoppedClockCanReestimate() { val c=ClockEstimator(); repeat(5){sample(c,0,n=it.toLong())}; repeat(24){sample(c,90,n=(it+5).toLong())}; assertEquals(90.0,c.offsetNs/1e6,0.001) }
    @Test fun rollingWindowBounded() { val c=ClockEstimator(); repeat(50){sample(c,1,n=it.toLong())}; assertEquals(24,c.count) }
    @Test fun freshnessAndReset() { val c=ClockEstimator(); repeat(5){sample(c,37,n=it.toLong())}; assertTrue(c.fresh(5_500_000_000)); assertFalse(c.fresh(20_000_000_000)); c.clear(); assertFalse(c.ready) }
    @Test fun populationStandardDeviation() { val c=ClockEstimator(); sample(c,0,5,5); sample(c,0,15,15,n=1); assertEquals(20.0,c.rttMs!!,0.001); assertEquals(10.0,c.jitterMs!!,0.001) }
}
class TimelineTest {
    private fun line(rev: Long=1,playing: Boolean=true,pos: Long=0,time: Long=1_000_000_000,track: String="song")=Timeline("session",track,rev,playing,pos,time,60000)
    @Test fun scheduledStart() { assertEquals(0,line().positionAt(500_000_000)); assertEquals(0,line().positionAt(1_000_000_000)); assertEquals(1000,line().positionAt(2_000_000_000)) }
    @Test fun pausedDoesNotAdvance() { assertEquals(1234,line(playing=false,pos=1234).positionAt(90_000_000_000)) }
    @Test fun resume() { assertEquals(15000,line(pos=10000,time=5_000_000_000).positionAt(10_000_000_000)) }
    @Test fun seek() { assertEquals(46000,line(pos=45000,time=9_000_000_000).positionAt(10_000_000_000)) }
    @Test fun lateJoin() { assertEquals(25000,line().positionAt(26_000_000_000)) }
    @Test fun reconnectAfter25Seconds() { val c=ClockEstimator(); c.add(1_000_000_000,1_047_000_000,1_049_000_000,1_022_000_000); assertEquals(25000,line().positionAt(c.hostTime(25_963_000_000))) }
    @Test fun endClamps() { assertEquals(60000,line().positionAt(100_000_000_000)) }
    @Test fun futurePauseKeepsOldTimelineUntilEffective() { val s=TimelineStore(); s.accept(line()); s.at(1_000_000_000); s.accept(line(2,false,3000,4_000_000_000)); assertEquals(2000,s.at(3_000_000_000)!!.positionAt(3_000_000_000)); assertEquals(3000,s.at(5_000_000_000)!!.positionAt(5_000_000_000)) }
    @Test fun staleAndDuplicateRejected() { val s=TimelineStore(); assertTrue(s.accept(line(9))); assertFalse(s.accept(line(8))); assertFalse(s.accept(line(9))) }
    @Test fun wrongSessionRejected() { val s=TimelineStore(); s.accept(line()); assertFalse(s.accept(line(2).copy(sessionId="other"))) }
    @Test fun trackChangeAtBoundary() { val s=TimelineStore(); s.accept(line()); s.at(1_000_000_000); s.accept(line(2,false,0,3_000_000_000,"new")); assertEquals("song",s.at(2_000_000_000)!!.trackId); assertEquals("new",s.at(3_000_000_000)!!.trackId) }
    @Test fun invalidPositionRejected() { assertFalse(TimelineStore().accept(line(pos=-1))) }
    @Test fun invalidRateRejected() { assertFalse(TimelineStore().accept(line().copy(playbackRate=Double.NaN))) }
}
class DriftTest {
    @Test fun smallErrorIgnored() { val c=DriftController(); repeat(10) { assertEquals(1f,c.update(20).speed); assertFalse(c.update(-20).seek) } }
    @Test fun aheadSlowsDownAfterPersistence() { val c=DriftController(); assertEquals(1f,c.update(80).speed); c.update(80); assertEquals(.995f,c.update(80).speed) }
    @Test fun behindSpeedsUp() { val c=DriftController(); repeat(3){c.update(-80)}; assertEquals(1.005f,c.update(-80).speed) }
    @Test fun largeSustainedErrorSeeks() { val c=DriftController(); assertFalse(c.update(400).seek); assertFalse(c.update(400).seek); assertTrue(c.update(400).seek) }
    @Test fun alternatingNoiseDoesNotSeek() { val c=DriftController(); repeat(20) { assertFalse(c.update(if(it%2==0) 400 else -400).seek) } }
}
