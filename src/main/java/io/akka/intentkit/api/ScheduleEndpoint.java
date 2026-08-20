package io.akka.intentkit.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.HttpException;
import io.akka.intentkit.domain.CronPolicy;
import io.akka.intentkit.domain.ScheduleRejected;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ask a schedule what it means, without storing anything.
 *
 * <p>Exists so that the two systems can be asked the same question literally: intentkit's
 * trigger answers "when does this fire after that instant", and without this there is no way
 * to put that question to the port. It is also where {@code minGapMinutes} — the figure
 * intentkit's interval refusal only claims — can be read for a schedule that was never
 * stored.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/schedules")
public class ScheduleEndpoint {

  private static final int MAX_FIRES = 20;

  public record ScheduleView(
      String cron, boolean accepted, String key, String message,
      List<Instant> fires, Long minGapMinutes) {}

  /** Asked with a body rather than a query string: the endpoint model takes path
   * parameters and a body, and a schedule question has three parts. */
  public record ExplainRequest(String cron, String from, Integer count) {}

  @Post("/explain")
  public ScheduleView explain(ExplainRequest request) {
    var cron = request.cron();
    var from = request.from();
    var count = request.count();
    try {
      var schedule = CronPolicy.validate(cron);
      var base = from == null ? Instant.now() : Instant.parse(from);
      int wanted = count == null ? 3 : Math.min(count, MAX_FIRES);
      var fires = new ArrayList<Instant>();
      var cursor = base;
      for (int i = 0; i < wanted; i++) {
        var next = schedule.nextAfter(cursor);
        if (next.isEmpty()) {
          break;
        }
        fires.add(next.get());
        cursor = next.get();
      }
      return new ScheduleView(cron, true, null, null, fires, schedule.minGapMinutes(base));
    } catch (ScheduleRejected e) {
      return new ScheduleView(cron, false, e.key(), e.getMessage(), List.of(), null);
    } catch (java.time.format.DateTimeParseException e) {
      throw HttpException.badRequest("from must be an ISO-8601 instant");
    }
  }
}
