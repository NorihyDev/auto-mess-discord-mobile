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

    public WatchConfig(String url, String message, String time, String timezone, int before, int after) {
        if (!CHANNEL.matcher(url.trim()).matches()) throw new IllegalArgumentException("Lien attendu : https://discord.com/channels/SERVEUR/SALON");
        if (message.trim().isEmpty() || message.length() > 2000) throw new IllegalArgumentException("Message : entre 1 et 2 000 caractères.");
        if (!time.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException("Heure attendue : HH:MM.");
        if (before < 0 || before > 180 || after < 1 || after > 180) throw new IllegalArgumentException("Avant : 0–180 min ; après : 1–180 min.");
        this.url = url.trim().replaceAll("/$", "");
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
