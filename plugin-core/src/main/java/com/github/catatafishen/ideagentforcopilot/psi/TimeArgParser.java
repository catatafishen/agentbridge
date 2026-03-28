package com.github.catatafishen.ideagentforcopilot.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared time/date argument parser for MCP tools.
 *
 * <p>Accepted formats:
 * <ul>
 *   <li>Relative: {@code "5m"}, {@code "30s"}, {@code "2h"}, {@code "2min"}, {@code "90sec"}
 *   <li>Time today (HH:mm or HH:mm:ss): {@code "16:57"}, {@code "16:57:30"}
 *   <li>Date only: {@code "2026-03-28"} - start of day in local time
 *   <li>Date-time: {@code "2026-03-28 16:57:30"}
 *   <li>ISO 8601 with T (with or without Z): {@code "2026-03-28T16:57:30Z"}, {@code "2026-03-28T16:57:30"}
 * </ul>
 *
 * <p>All parse methods return {@code null} for null/blank input (meaning "not provided"),
 * and throw {@link IllegalArgumentException} for non-blank unparseable input.
 */
public final class TimeArgParser {

    public static final String ACCEPTED_FORMATS =
        "\"5m\" / \"30s\" / \"2h\" (relative), \"16:57:30\" (time today), " +
            "\"2026-03-28\" (date), \"2026-03-28 16:57:30\" (datetime), \"2026-03-28T16:57:30Z\" (ISO 8601)";

    private static final Pattern RELATIVE_PATTERN =
        Pattern.compile("^(\\d+)(h|hours?|m|min|minutes?|s|sec|seconds?)$", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private TimeArgParser() {
    }

    /**
     * Parses a time argument into a {@link LocalDateTime}.
     *
     * @param value the raw parameter value; {@code null} or blank means "not provided"
     * @return {@code null} if value is null/blank
     * @throws IllegalArgumentException if value is non-blank but cannot be parsed
     */
    @Nullable
    public static LocalDateTime parseLocalDateTime(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();

        Matcher rel = RELATIVE_PATTERN.matcher(v);
        if (rel.matches()) return parseRelative(rel);

        if (v.contains("T")) {
            LocalDateTime iso = tryParseIso8601(v);
            if (iso != null) return iso;
        }

        try {
            return LocalDateTime.parse(v, DATETIME_SPACE);
        } catch (DateTimeParseException ignored) { /* fall through */ }

        try {
            return LocalDate.parse(v, DATE_ONLY).atStartOfDay();
        } catch (DateTimeParseException ignored) { /* fall through */ }

        LocalDateTime timeToday = tryParseTimeToday(v);
        if (timeToday != null) return timeToday;

        throw new IllegalArgumentException(
            "Unrecognized time format: \"" + v + "\". Accepted: " + ACCEPTED_FORMATS);
    }

    @NotNull
    private static LocalDateTime parseRelative(@NotNull Matcher rel) {
        long amount = Long.parseLong(rel.group(1));
        String unit = rel.group(2).toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        if (unit.startsWith("h")) return now.minusHours(amount);
        if (unit.startsWith("m")) return now.minusMinutes(amount);
        return now.minusSeconds(amount);
    }

    @Nullable
    private static LocalDateTime tryParseIso8601(@NotNull String v) {
        try {
            return v.endsWith("Z")
                ? LocalDateTime.ofInstant(Instant.parse(v), ZoneId.systemDefault())
                : LocalDateTime.parse(v, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    @Nullable
    private static LocalDateTime tryParseTimeToday(@NotNull String v) {
        try {
            return LocalDate.now().atTime(LocalTime.parse(v, TIME_HMS));
        } catch (DateTimeParseException ignored) { /* fall through */ }
        try {
            return LocalDate.now().atTime(LocalTime.parse(v, TIME_HM));
        } catch (DateTimeParseException ignored) { /* fall through */ }
        return null;
    }

    /**
     * Parses a time argument into an {@link Instant}.
     * Local date/time values are interpreted in the system default time zone.
     *
     * @param value the raw parameter value; {@code null} or blank means "not provided"
     * @return {@code null} if value is null/blank
     * @throws IllegalArgumentException if value is non-blank but cannot be parsed
     */
    @Nullable
    public static Instant parseInstant(@Nullable String value) {
        LocalDateTime ldt = parseLocalDateTime(value);
        return ldt == null ? null : ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Parses a time argument into epoch milliseconds.
     *
     * @param value the raw parameter value; {@code null} or blank means "not provided"
     * @return epoch milliseconds, or {@code -1} if value is null/blank
     * @throws IllegalArgumentException if value is non-blank but cannot be parsed
     */
    public static long parseEpochMillis(@Nullable String value) {
        Instant instant = parseInstant(value);
        return instant == null ? -1L : instant.toEpochMilli();
    }
}
