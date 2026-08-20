package io.akka.intentkit.domain;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Whether a schedule may be stored — SPEC-001 rules 1, 2, 3, 3a, 5, 6.
 *
 * <p>intentkit checks a task's cron twice with two different libraries: a grammar check when
 * the task is written, and a second, stricter check when the scheduler tries to build a job
 * from it. Four expressions pass the first and fail the second (question-log #40); intentkit
 * stores those and logs a failure once a minute forever, and the caller is never told. Both
 * checks are applied here, at write time, so an accepted task is a task that can run.
 * SPEC-001 decision 9.
 *
 * <p>The refusal keys are intentkit's own, in intentkit's own order: grammar first, then the
 * interval rule.
 */
public final class CronPolicy {

  private CronPolicy() {}

  private static final List<String> MONTH_NAMES =
      List.of("jan", "feb", "mar", "apr", "may", "jun",
              "jul", "aug", "sep", "oct", "nov", "dec");

  /** Monday first — the numbering intentkit's trigger uses (question-log #5). */
  private static final List<String> DAY_NAMES =
      List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

  public static CronSchedule validate(String cron) {
    if (cron == null || cron.isBlank()) {
      throw new ScheduleRejected("InvalidAutonomousConfig", "cron must have a value");
    }

    // Split on a single space, so a leading, trailing or doubled space produces an empty
    // field and is refused — which is what intentkit's validator does, and what a
    // whitespace-collapsing split would hide (question-log #40).
    var fields = cron.split(" ", -1);
    if (fields.length != 5) {
      throw new ScheduleRejected("InvalidCronExpression", "Invalid cron expression format");
    }

    var minutes = parse(fields[0], 0, 59, null, "minute");
    var hours = parse(fields[1], 0, 23, null, "hour");
    var daysOfMonth = parse(fields[2], 1, 31, null, "day");
    var months = parse(fields[3], 1, 12, MONTH_NAMES, "month");
    var daysOfWeek = parse(fields[4], 0, 6, DAY_NAMES, "day_of_week");

    rejectTooFrequent(fields[0], fields[1]);

    return new CronSchedule(cron, minutes, hours, daysOfMonth, months, daysOfWeek);
  }

  /**
   * intentkit's interval rule, copied including the three shapes it lets through.
   *
   * <p>It guards only when the hour field is unrestricted, and only against a step below
   * five, so {@code &#42;/2 0 * * *} (two minutes), {@code 0-5 2 * * *} (one minute) and
   * {@code &#42;/59 * * * *} (one minute, at :59 → :00) are all accepted while the refusal
   * message says the shortest interval is five minutes. Copied rather than corrected —
   * SPEC-001 decision 1 — and the real gap is reported by
   * {@link CronSchedule#minGapMinutes()} instead.
   */
  private static void rejectTooFrequent(String minute, String hour) {
    var tooOften =
        new ScheduleRejected(
            "InvalidAutonomousInterval", "The shortest execution interval is 5 minutes");
    if (minute.equals("*")) {
      throw tooOften;
    }
    int slash = minute.indexOf('/');
    if (slash >= 0 && hour.equals("*")) {
      if (Integer.parseInt(minute.substring(slash + 1)) < 5) {
        throw tooOften;
      }
    }
    if ((minute.indexOf(',') >= 0 || minute.indexOf('-') >= 0) && hour.equals("*")) {
      throw tooOften;
    }
  }

  private static BitSet parse(String field, int min, int max, List<String> names, String label) {
    var set = new BitSet(max + 1);
    if (field.isEmpty()) {
      throw invalid(field, label);
    }
    if (field.equals("*")) {
      set.set(min, max + 1);
      return set;
    }
    if (field.startsWith("*/")) {
      set(set, min, max, step(field.substring(2), min, max, field, label), min, max);
      return set;
    }
    // A comma list holds plain values only: intentkit's validator refuses "5-10,20" and
    // "&#42;/5,30" while accepting "1,3,5" (question-log #7).
    if (field.indexOf(',') >= 0) {
      for (var part : field.split(",", -1)) {
        set.set(value(part, min, max, names, field, label));
      }
      return set;
    }
    int dash = field.indexOf('-');
    if (dash > 0) {
      int low = value(field.substring(0, dash), min, max, names, field, label);
      int high = value(field.substring(dash + 1), min, max, names, field, label);
      if (low > high) {
        throw invalid(field, "The minimum value in a range must not be higher than the maximum");
      }
      set.set(low, high + 1);
      return set;
    }
    set.set(value(field, min, max, names, field, label));
    return set;
  }

  private static void set(BitSet target, int from, int to, int step, int min, int max) {
    for (int v = from; v <= to; v += step) {
      if (v >= min && v <= max) {
        target.set(v);
      }
    }
  }

  /**
   * A step is capped at the field's span, not at its maximum.
   *
   * <p>intentkit's validator caps it at the maximum and its scheduler at the span, so
   * {@code &#42;/31} on day and {@code &#42;/12} on month pass the first and fail the second
   * (question-log #39, #40). The stricter of the two is applied here.
   */
  private static int step(String text, int min, int max, String field, String label) {
    int step;
    try {
      step = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw invalid(field, label);
    }
    if (step < 1) {
      throw invalid(field, "Increment must be higher than 0");
    }
    if (step > max - min) {
      throw invalid(
          field, "the step value (" + step + ") is higher than the total range of the expression");
    }
    return step;
  }

  private static int value(
      String text, int min, int max, List<String> names, String field, String label) {
    if (text.isEmpty()) {
      throw invalid(field, label);
    }
    if (names != null) {
      int named = names.indexOf(text.toLowerCase(Locale.ROOT));
      if (named >= 0) {
        return named + min;
      }
    }
    int parsed;
    try {
      parsed = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw invalid(field, label);
    }
    if (parsed < min || parsed > max) {
      throw invalid(field, "value " + parsed + " is outside " + min + "-" + max);
    }
    return parsed;
  }

  private static ScheduleRejected invalid(String field, String detail) {
    return new ScheduleRejected(
        "InvalidCronExpression", "Error validating expression '" + field + "': " + detail);
  }
}
