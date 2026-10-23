package dev.jarvis.routine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** When routines run, and how their time and days are written. Pure logic. */
public final class RoutineSchedule {

    /** How days are saved and shown to the model: mon,tue,wed,thu,fri,sat,sun. */
    private static final List<String> CODES = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    /** Also the Italian names, and the full ones: the model passes what the user said. */
    private static final Map<String, DayOfWeek> NAMES = Map.ofEntries(
            Map.entry("lun", DayOfWeek.MONDAY), Map.entry("mar", DayOfWeek.TUESDAY),
            Map.entry("mer", DayOfWeek.WEDNESDAY), Map.entry("gio", DayOfWeek.THURSDAY),
            Map.entry("ven", DayOfWeek.FRIDAY), Map.entry("sab", DayOfWeek.SATURDAY),
            Map.entry("dom", DayOfWeek.SUNDAY));
    private static final Set<String> EVERY_DAY = Set.of("*", "every day", "everyday", "daily", "ogni giorno",
            "tutti i giorni", "sempre");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?");

    private RoutineSchedule() {
    }

    /** Enabled, today is one of its days, its time has come and it has not run today yet. */
    public static boolean due(Routine routine, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return routine.enabled() && routine.days().contains(today.getDayOfWeek())
                && !now.toLocalTime().isBefore(routine.at())
                && (routine.lastRun() == null || routine.lastRun().isBefore(today));
    }

    /** A routine created after its time today starts tomorrow: today counts as already run. */
    public static LocalDate initialLastRun(LocalTime at, LocalDateTime now) {
        return now.toLocalTime().isBefore(at) ? null : now.toLocalDate();
    }

    /** @throws IllegalArgumentException with a message for the model or the user */
    public static LocalTime parseTime(String text) {
        Matcher matcher = TIME.matcher(text == null ? "" : text.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Ora non valida: " + text + " (usa HH:mm, per esempio 08:00).");
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            throw new IllegalArgumentException("Ora non valida: " + text + " (usa HH:mm, per esempio 08:00).");
        }
        return LocalTime.of(hour, minute);
    }

    /**
     * "mon,wed,fri", "lun-ven", "every day", "sabato domenica"...
     *
     * @throws IllegalArgumentException with a message for the model or the user
     */
    public static Set<DayOfWeek> parseDays(String text) {
        String days = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (days.isEmpty() || EVERY_DAY.contains(days)) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        for (String part : days.split("[,;\\s]+")) {
            String[] range = part.split("-");
            if (range.length == 2) {
                DayOfWeek from = day(range[0], text);
                DayOfWeek to = day(range[1], text);
                for (DayOfWeek day = from; ; day = day.plus(1)) {
                    parsed.add(day);
                    if (day == to) {
                        break;
                    }
                }
            } else if (!part.isBlank() && !part.equals("e") && !part.equals("and")) {
                parsed.add(day(part, text));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Giorni non validi: " + text);
        }
        return parsed;
    }

    public static String format(Set<DayOfWeek> days) {
        return days.stream().sorted().map(day -> CODES.get(day.ordinal())).collect(Collectors.joining(","));
    }

    private static DayOfWeek day(String word, String text) {
        String prefix = word.length() < 3 ? word : word.substring(0, 3);
        int code = CODES.indexOf(prefix);
        if (code >= 0) {
            return DayOfWeek.of(code + 1);
        }
        DayOfWeek italian = NAMES.get(prefix);
        if (italian == null) {
            throw new IllegalArgumentException("Giorni non validi: " + text + " (usa mon,tue,wed,thu,fri,sat,sun).");
        }
        return italian;
    }
}
