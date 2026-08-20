package io.akka.intentkit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.Optional;

/**
 * A five-field schedule and the two questions asked of it: when does it next fire, and how
 * close together can it fire.
 *
 * <p>SPEC-001 rules 2, 3, 4, 6. Day-of-week is numbered from Monday, and a restricted
 * day-of-month is combined with a restricted day-of-week by and — both established by
 * running intentkit's own trigger (question-log #5, #6), and both the opposite of what
 * standard crontab does.
 *
 * <p>Everything is UTC. intentkit passes {@code timezone="UTC"} when it builds a trigger, so
 * there is no local-time case to carry.
 */
public final class CronSchedule {

  /** Where {@link #minGapMinutes()} starts looking when no instant is supplied. */
  private static final Instant DEFAULT_GAP_REFERENCE = Instant.parse("2026-03-01T12:00:30Z");

  /** How many fire times the reported gap is the smallest of. */
  private static final int GAP_SAMPLES = 6;

  /** A schedule that has not fired within this many years of the search start never will. */
  private static final int SEARCH_YEARS = 8;

  private final String expression;
  private final BitSet minutes;
  private final BitSet hours;
  private final BitSet daysOfMonth;
  private final BitSet months;
  private final BitSet daysOfWeek;

  CronSchedule(
      String expression,
      BitSet minutes,
      BitSet hours,
      BitSet daysOfMonth,
      BitSet months,
      BitSet daysOfWeek) {
    this.expression = expression;
    this.minutes = minutes;
    this.hours = hours;
    this.daysOfMonth = daysOfMonth;
    this.months = months;
    this.daysOfWeek = daysOfWeek;
  }

  public String expression() {
    return expression;
  }

  /** The first fire time strictly after {@code after}, or empty if the schedule never fires. */
  public Optional<Instant> nextAfter(Instant after) {
    var from = after.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
    var zoned = from.atZone(ZoneOffset.UTC);
    var date = zoned.toLocalDate();
    var limit = date.plusYears(SEARCH_YEARS);
    int startMinuteOfDay = zoned.getHour() * 60 + zoned.getMinute();

    while (!date.isAfter(limit)) {
      if (matchesDate(date)) {
        for (int minuteOfDay = startMinuteOfDay; minuteOfDay < 24 * 60; minuteOfDay++) {
          if (hours.get(minuteOfDay / 60) && minutes.get(minuteOfDay % 60)) {
            return Optional.of(
                date.atStartOfDay(ZoneOffset.UTC).plusMinutes(minuteOfDay).toInstant());
          }
        }
      }
      date = date.plusDays(1);
      startMinuteOfDay = 0;
    }
    return Optional.empty();
  }

  /**
   * The smallest gap, in minutes, between the next six fire times after {@code from}.
   *
   * <p>Six rather than "all of them" because the answer has to be finite and the figure is
   * reported per task on every read. It is the same window the recorded source answers were
   * taken over, so the two are comparable.
   *
   * <p>Reported because intentkit's own interval refusal claims a five-minute floor it does
   * not enforce — three accepted expressions fire 1, 2 and 4 minutes apart (question-log
   * #3). SPEC-001 decision 1.
   */
  public long minGapMinutes(Instant from) {
    long smallest = Long.MAX_VALUE;
    var previous = nextAfter(from);
    if (previous.isEmpty()) {
      return 0;
    }
    var cursor = previous.get();
    for (int i = 1; i < GAP_SAMPLES; i++) {
      var next = nextAfter(cursor);
      if (next.isEmpty()) {
        break;
      }
      smallest = Math.min(smallest, ChronoUnit.MINUTES.between(cursor, next.get()));
      cursor = next.get();
    }
    return smallest == Long.MAX_VALUE ? 0 : smallest;
  }

  public long minGapMinutes() {
    return minGapMinutes(DEFAULT_GAP_REFERENCE);
  }

  private boolean matchesDate(LocalDate date) {
    if (!months.get(date.getMonthValue())) {
      return false;
    }
    // Both must match. An unrestricted field has every bit set, so the same conjunction
    // gives standard-cron behaviour when only one of the two is restricted, and the
    // and-semantics intentkit's trigger has when both are (question-log #6).
    // getValue() is 1 for Monday; this schedule numbers Monday 0.
    return daysOfMonth.get(date.getDayOfMonth())
        && daysOfWeek.get(date.getDayOfWeek().getValue() - 1);
  }
}
