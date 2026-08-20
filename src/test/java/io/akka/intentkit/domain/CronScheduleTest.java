package io.akka.intentkit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 2, 3, 4 — what a schedule means, checked against the fire times intentkit's
 * own trigger produced.
 *
 * <p>These are the answers a hand-written cron engine gets wrong quietly: Monday is zero,
 * not Sunday, and a restricted day-of-month is combined with a restricted day-of-week by
 * and, not by or.
 */
public class CronScheduleTest {

  private final SourceAnswers answers = SourceAnswers.load();

  @Test
  public void fireTimesMatchTheSourcesOwnTrigger() {
    int compared = 0;
    for (var e : answers.expressions()) {
      if (!e.portAccepts() || e.fires().isEmpty()) {
        continue;
      }
      // The calendar sweep ran from a different instant than the interval sweep, and
      // reading them from one base would compare two different questions.
      var base = e.weekdays().isEmpty() ? answers.base() : answers.calendarBase();
      var schedule = CronPolicy.validate(e.cron());
      var actual = new ArrayList<Instant>();
      var cursor = base;
      for (int i = 0; i < e.fires().size(); i++) {
        cursor = schedule.nextAfter(cursor).orElseThrow();
        actual.add(cursor);
      }
      assertThat(actual).as("fire times for %s", e.cron()).isEqualTo(e.fires());
      compared++;
    }
    assertThat(compared).as("expressions with recorded fire times").isGreaterThan(20);
  }

  @Test
  public void dayOfWeekZeroIsMonday() {
    assertThat(weekdayOf("0 0 * * 0")).isEqualTo("Mon");
    assertThat(weekdayOf("0 0 * * 6")).isEqualTo("Sun");
    assertThat(weekdayOf("0 0 * * mon")).isEqualTo("Mon");
    assertThat(weekdayOf("0 0 * * sun")).isEqualTo("Sun");
    assertThat(weekdayOf("0 0 * * fri")).isEqualTo("Fri");
  }

  @Test
  public void dayOfMonthAndDayOfWeekAreCombinedByAnd() {
    // 0 0 13 * 5 is "the 13th, if it is a Saturday" — not "every 13th or every Saturday".
    var schedule = CronPolicy.validate("0 0 13 * 5");
    var first = schedule.nextAfter(answers.calendarBase()).orElseThrow();
    assertThat(first.atZone(ZoneOffset.UTC).toLocalDate().toString()).isEqualTo("2026-06-13");
    assertThat(weekdayOf("0 0 13 * 5")).isEqualTo("Sat");

    var everyFirst = CronPolicy.validate("0 0 1 * *");
    assertThat(everyFirst.nextAfter(answers.calendarBase()).orElseThrow()
        .atZone(ZoneOffset.UTC).toLocalDate().toString())
        .isEqualTo("2026-04-01");
  }

  @Test
  public void aScheduleThatNeverFiresAgainAnswersEmpty() {
    // February 30th parses field by field and matches no date in any year.
    var schedule = CronPolicy.validate("0 0 30 2 *");
    assertThat(schedule.nextAfter(answers.calendarBase())).isEmpty();
  }

  @Test
  public void leapDayIsFoundAcrossYears() {
    var schedule = CronPolicy.validate("0 0 29 2 *");
    assertThat(schedule.nextAfter(answers.calendarBase()).orElseThrow()
        .atZone(ZoneOffset.UTC).toLocalDate().toString())
        .isEqualTo("2028-02-29");
  }

  private String weekdayOf(String cron) {
    var next = CronPolicy.validate(cron).nextAfter(answers.calendarBase()).orElseThrow();
    return next.atZone(ZoneOffset.UTC)
        .getDayOfWeek()
        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
  }
}
