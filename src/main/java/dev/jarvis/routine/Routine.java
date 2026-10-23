package dev.jarvis.routine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Something Jarvis does by himself at a time of the day, on some days of the week (e.g. the morning briefing).
 *
 * @param instructions what to do, in the user's words: the routine specialist receives them as its task
 * @param lastRun      the day it last ran; null if never. A routine due today and not run yet runs as soon as Jarvis
 *                     is on, so a briefing at 8 still arrives if the PC is switched on at 9
 */
public record Routine(String id, String name, LocalTime at, Set<DayOfWeek> days, String instructions, boolean enabled,
                      LocalDate lastRun) {
}
