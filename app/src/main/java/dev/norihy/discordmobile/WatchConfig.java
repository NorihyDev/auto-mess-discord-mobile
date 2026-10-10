package dev.norihy.discordmobile;

import java.time.*;
import java.util.regex.Pattern;

/** Pure Java: configuration and timezone-aware windows, including midnight. */
public final class WatchConfig {
    private static final Pattern CHANNEL = Pattern.compile("https://discord\\.com/channels/[1-9][0-9]*/[1-9][0-9]*/?");
    public final String url, message;
    public final ZoneId zone;
    public final LocalTime opening;
    public final int before, after;

    public static String normalizeChannel(String url) {
        if (!CHANNEL.matcher(url.trim()).matches()) throw new IllegalArgumentException("Use a Discord channel link: https://discord.com/channels/SERVER/CHANNEL");
        return url.trim().replaceAll("/$", "");
    }

    public WatchConfig(String url, String message, String time, String timezone, int before, int after) {
        String normalizedUrl = normalizeChannel(url);
        if (message.trim().isEmpty() || message.length() > 2000) throw new IllegalArgumentException("Write a message between 1 and 2000 characters.");
        if (!time.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException("Use an opening time in HH:MM format.");
        if (before < 0 || before > 180 || after < 1 || after > 180) throw new IllegalArgumentException("Watch window: 0-180 minutes before, 1-180 minutes after.");
        this.url = normalizedUrl;
        this.message = message;
        this.opening = LocalTime.parse(time);
        this.zone = ZoneId.of(timezone.trim());
        this.before = before;
        this.after = after;
    }

    public Window window(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int offset = -1; offset <= 1; offset++) {
            Window w = on(today.plusDays(offset));
            if (!now.isBefore(w.start) && now.isBefore(w.end)) return w;
        }
        Window todayWindow = on(today);
        return now.isBefore(todayWindow.start) ? todayWindow : on(today.plusDays(1));
    }

    public Window on(LocalDate day) {
        Instant center = day.atTime(opening).atZone(zone).toInstant();
        return new Window(day.toString(), center.minusSeconds(before * 60L), center.plusSeconds(after * 60L));
    }

    public record Window(String day, Instant start, Instant end) {
        public boolean active(Instant now) { return !now.isBefore(start) && now.isBefore(end); }
    }
}
