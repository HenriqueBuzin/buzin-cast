package io.leancast.domain.cast;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PlayerPortJvmCompatibilityTest {
    @Test
    public void legacyDefaultKeepsAnOnDemandItemSeekableWithItsReportedDuration() {
        LegacyPlayer player = new LegacyPlayer();
        CastSession session = new CastSession(player, () -> 0L);
        session.setUri("http://example.test/video.mp4", null);
        session.play();
        session.onPlayerPrepared();

        assertFalse(session.isLive());
        assertEquals(45_000L, session.durationMs());
        session.seekTo(12_000L);
        assertEquals(12_000L, player.position);
    }

    private static final class LegacyPlayer implements PlayerPort {
        private long position;

        @Override public void prepareAndPlay(String uri) {}
        @Override public void play() {}
        @Override public void pause() {}
        @Override public void stop() {}
        @Override public void seekTo(long ms) { position = ms; }
        @Override public long currentPositionMs() { return position; }
        @Override public long currentDurationMs() { return 45_000L; }

        @Override
        @SuppressWarnings("deprecation")
        public boolean isLive() {
            // Kotlin implementations compiled without JVM defaults use this bridge.
            return PlayerPort.DefaultImpls.isLive(this);
        }
    }
}
