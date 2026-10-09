package dev.norihy.discordmobile;

import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class WatchConfigTest {
    private WatchConfig config(String time, String zone) {
        return new WatchConfig("https://discord.com/channels/123/456/", "Check-in ✅", time, zone, 5, 15);
    }
    @Test public void canonicalChannelAndExactBoundaries() {
        WatchConfig c = config("20:00", "Indian/Antananarivo");
        assertEquals("https://discord.com/channels/123/456", c.url);
        WatchConfig.Window w = c.on(LocalDate.parse("2026-10-09"));
        assertEquals(Instant.parse("2026-10-09T16:55:00Z"), w.start());
        assertTrue(w.active(w.start())); assertFalse(w.active(w.end()));
        assertEquals("2026-10-10", c.window(w.end()).day());
    }
    @Test public void afterMidnightBelongsToPreviousScheduledDay() {
        WatchConfig c = config("23:58", "UTC");
        assertEquals("2026-10-09", c.window(Instant.parse("2026-10-10T00:04:00Z")).day());
    }
    @Test public void beforeMidnightCanBelongToTomorrow() {
        WatchConfig c = config("00:02", "UTC");
        assertEquals("2026-10-10", c.window(Instant.parse("2026-10-09T23:59:00Z")).day());
    }
    @Test public void swissDaylightSavingConversion() {
        WatchConfig c = config("20:00", "Europe/Zurich");
        assertEquals(Instant.parse("2026-07-01T17:55:00Z"), c.on(LocalDate.parse("2026-07-01")).start());
        assertEquals(Instant.parse("2026-12-01T18:55:00Z"), c.on(LocalDate.parse("2026-12-01")).start());
    }
    @Test public void dstGapAndOverlapProduceValidWindows() {
        WatchConfig c = config("02:30", "Europe/Zurich");
        assertEquals(Instant.parse("2026-03-29T01:25:00Z"), c.on(LocalDate.parse("2026-03-29")).start());
        assertEquals(Instant.parse("2026-10-25T00:25:00Z"), c.on(LocalDate.parse("2026-10-25")).start());
    }
    @Test public void invalidSettingsAreRejected() {
        String[] urls = {"http://discord.com/channels/1/2", "https://discord.com.evil.test/channels/1/2", "https://discord.com/channels/@me/2", "https://discord.com/channels/0/2", "https://discord.com/channels/1/2?x=y"};
        for (String url : urls) assertThrows(IllegalArgumentException.class, () -> new WatchConfig(url, "x", "20:00", "UTC", 5, 15));
        assertThrows(IllegalArgumentException.class, () -> new WatchConfig("https://discord.com/channels/1/2", " ", "20:00", "UTC", 5, 15));
        assertThrows(IllegalArgumentException.class, () -> new WatchConfig("https://discord.com/channels/1/2", "x".repeat(2001), "20:00", "UTC", 5, 15));
        assertThrows(IllegalArgumentException.class, () -> new WatchConfig("https://discord.com/channels/1/2", "x", "20:00", "UTC", -1, 15));
        assertThrows(DateTimeException.class, () -> config("25:00", "UTC"));
        assertThrows(DateTimeException.class, () -> config("20:00", "Unknown/Zone"));
    }
}
