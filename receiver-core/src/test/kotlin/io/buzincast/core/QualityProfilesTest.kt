package io.buzincast.core
import org.junit.Assert.*
import org.junit.Test

class QualityProfilesTest {
    private val hd30=VideoMode(1280,720,30)
    private val hd60=VideoMode(1280,720,60)
    private val full30=VideoMode(1920,1080,30)
    private val full60=VideoMode(1920,1080,60)
    @Test fun `profiles prefer their intended mode on capable devices`() {
        val device=DeviceCapabilities(setOf(hd30,hd60,full30,full60),true)
        assertEquals(full30,QualityProfiles.choose(ExperienceProfile.CINEMA,device).mode)
        assertEquals(full60,QualityProfiles.choose(ExperienceProfile.PRESENTATION,device).mode)
        val games=QualityProfiles.choose(ExperienceProfile.GAMES,device)
        assertEquals(hd60,games.mode);assertTrue(games.lowLatency);assertFalse(games.autoBuffer);assertEquals(25,games.cushionMs)
        assertEquals(4,QualityProfiles.choose(ExperienceProfile.CINEMA,device).adaptiveStep)
    }
    @Test fun `all subsets negotiate only an advertised mode`() {
        val modes=listOf(hd30,hd60,full30,full60)
        for(mask in 1..15)for(profile in ExperienceProfile.entries)for(lowLatency in listOf(false,true)) {
            val supported=modes.filterIndexed { index,_ -> mask and (1 shl index)!=0 }.toSet()
            val plan=QualityProfiles.choose(profile,DeviceCapabilities(supported,lowLatency))
            assertTrue(plan.mode in supported)
            assertEquals(profile==ExperienceProfile.GAMES && lowLatency,plan.lowLatency)
        }
    }
    @Test(expected=IllegalStateException::class) fun `unknown capabilities do not invent support`() {
        QualityProfiles.choose(ExperienceProfile.GAMES,DeviceCapabilities(emptySet(),false))
    }
    @Test fun `unusual valid output has conservative fallback`() {
        val mode=VideoMode(640,480,24)
        assertEquals(mode,QualityProfiles.choose(ExperienceProfile.CINEMA,DeviceCapabilities(setOf(mode,VideoMode(0,480,30),VideoMode(640,0,30),VideoMode(640,480,0)),false)).mode)
    }
}
