/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.UserResource.ProjectRef;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Build statistics across projects: admins see every project, global leads the projects they lead.
 * Not to be confused with {@link BuildReport}, the per-build workflow reports.
 */
@Path("/api/reports/builds")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class BuildStatisticsResource {
  static final List<String> PERIODS = List.of("7d", "30d", "365d", "all");
  static final int MAX_BRANCHES = 20, MAX_FAILURES = 20;
  static final List<String> BUCKETS = List.of("day", "week");
  static final int MAX_BUCKETS = 120, MAX_SLOW = 10, MAX_FLAKY = 20;
  static final List<String> HISTOGRAM = List.of("< 1m", "1-5m", "5-15m", "15-60m", "1-6h", "> 6h");

  @Inject SecurityIdentity identity;
  @Inject ProjectAccess access;
  @Inject EntityManager em;
  @Inject BuildStatisticsPdf pdf;

  /** How long a computed trend report is cached, in seconds; 0 recomputes on every request. */
  @ConfigProperty(name = "cisys.trends.cache-seconds", defaultValue = "30")
  long trendsCacheSeconds;

  private volatile CachedTrends cachedTrends;

  private record CachedTrends(String key, Trends trends, Instant expires) {}

  /** One group of builds; {@code successRate} is null until a build has passed or failed. */
  public record Row(
      String key,
      String label,
      long builds,
      long passed,
      long failed,
      long cancelled,
      long active,
      Double successRate,
      Double averageSeconds,
      Instant lastBuildAt) {}

  /** A failed build, newest first. */
  public record Failure(
      Long id,
      Long projectId,
      String project,
      long number,
      String branch,
      String revision,
      Instant finishedAt) {}

  /** The whole report for the chosen project and period. */
  public record BuildStatistics(
      List<ProjectRef> projects,
      Long projectId,
      String period,
      Instant generatedAt,
      Row totals,
      List<Row> byProject,
      List<Row> byTrigger,
      List<Row> byBranch,
      List<Failure> recentFailures) {}

  /** The report; {@code period} is 7d, 30d (default), 365d or all. */
  @GET
  public BuildStatistics statistics(
      @QueryParam("projectId") Long projectId, @QueryParam("period") String period) {
    return compute(projectId, period);
  }

  /** The report as a PDF download, with the same filters. */
  @GET
  @Path("/pdf")
  @Produces("application/pdf")
  public Response export(
      @QueryParam("projectId") Long projectId, @QueryParam("period") String period) {
    BuildStatistics s = compute(projectId, period);
    Trends t = computeTrends(projectId, allowedProjectIds(), s.period(), null, null, null);
    String file = "build-report-" + LocalDate.ofInstant(s.generatedAt(), ZoneOffset.UTC) + ".pdf";
    return Response.ok(pdf.render(s, t))
        .header("Content-Disposition", "attachment; filename=\"" + file + "\"")
        .build();
  }

  BuildStatistics compute(Long projectId, String period) {
    String p = period == null || period.isBlank() ? "30d" : period;
    if (!PERIODS.contains(p)) {
      throw new BadRequestException();
    }
    Set<Long> allowed = allowedProjectIds();
    List<Project> projects =
        allowed == null
            ? Project.list("order by name")
            : Project.list("id in ?1 order by name", allowed);
    List<ProjectRef> refs = projects.stream().map(x -> new ProjectRef(x.id, x.name)).toList();
    if (projectId != null && refs.stream().noneMatch(r -> r.id().equals(projectId))) {
      throw new ForbiddenException();
    }
    Instant now = Instant.now();
    Scope scope = new Scope(projectId != null ? List.of(projectId) : allowed, since(p, now), null);
    if (scope.ids() != null && scope.ids().isEmpty()) {
      Row none = row(null, "", new Object[] {0L, 0L, 0L, 0L, 0L, null, null});
      return new BuildStatistics(
          refs, projectId, p, now, none, List.of(), List.of(), List.of(), List.of());
    }
    Map<Long, String> names = projects.stream().collect(Collectors.toMap(x -> x.id, x -> x.name));
    Row totals = rows(null, scope, 0).getFirst();
    List<Row> byProject =
        rows("b.projectId", scope, 0).stream()
            .map(r -> relabel(r, names.getOrDefault(Long.valueOf(r.key()), r.key())))
            .sorted(Comparator.comparing(Row::label, String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<Row> byTrigger =
        rows("b.trigger", scope, 0).stream().sorted(Comparator.comparing(Row::key)).toList();
    List<Row> byBranch = projectId == null ? List.of() : rows("b.branch", scope, MAX_BRANCHES);
    return new BuildStatistics(
        refs, projectId, p, now, totals, byProject, byTrigger, byBranch, failures(scope, names));
  }

  /** Null for admins (every project), the led projects for global leads. */
  private Set<Long> allowedProjectIds() {
    if (access.isAdmin()) {
      return null;
    }
    if (!identity.hasRole("lead")) {
      throw new ForbiddenException();
    }
    return ProjectMember.projectIds(access.subject(), ProjectMember.TeamRole.LEAD);
  }

  private static Instant since(String period, Instant now) {
    return switch (period) {
      case "7d" -> now.minus(7, ChronoUnit.DAYS);
      case "30d" -> now.minus(30, ChronoUnit.DAYS);
      case "365d" -> now.minus(365, ChronoUnit.DAYS);
      default -> null;
    };
  }

  /** {@code ids} null means every project, {@code since}/{@code until} null mean unbounded. */
  private record Scope(Collection<Long> ids, Instant since, Instant until) {
    String where() {
      List<String> parts = new ArrayList<>();
      if (ids != null) {
        parts.add("b.projectId in :ids");
      }
      if (since != null) {
        parts.add("coalesce(b.requestedAt, b.startedAt) >= :since");
      }
      if (until != null) {
        parts.add("coalesce(b.requestedAt, b.startedAt) <= :until");
      }
      return parts.isEmpty() ? "" : " where " + String.join(" and ", parts);
    }

    <T> TypedQuery<T> bind(TypedQuery<T> q) {
      if (ids != null) {
        q.setParameter("ids", ids);
      }
      if (since != null) {
        q.setParameter("since", since);
      }
      if (until != null) {
        q.setParameter("until", until);
      }
      return q;
    }
  }

  /** Grouped by {@code key}, or a single total row when it is null; largest groups first. */
  private List<Row> rows(String key, Scope scope, int limit) {
    String select = key == null ? "" : key + ", ";
    String group = key == null ? "" : " group by " + key + " order by count(b) desc";
    TypedQuery<Object[]> q =
        em.createQuery(
            "select "
                + select
                + """
                count(b),
                sum(case when b.status = 'PASSED' then 1 else 0 end),
                sum(case when b.status = 'FAILED' then 1 else 0 end),
                sum(case when b.status = 'CANCELLED' then 1 else 0 end),
                sum(case when b.status in ('QUEUED', 'RUNNING') then 1 else 0 end),
                avg(case when b.finishedAt is not null and b.startedAt is not null
                         then extract(epoch from b.finishedAt) - extract(epoch from b.startedAt)
                    end),
                max(coalesce(b.requestedAt, b.startedAt))
                from Build b\
                """
                + scope.where()
                + group,
            Object[].class);
    if (limit > 0) {
      q.setMaxResults(limit);
    }
    Function<Object[], Row> map =
        key == null
            ? r -> row(null, "", r)
            : r -> {
              String k = r[0] == null ? null : r[0].toString();
              Object[] rest = new Object[r.length - 1];
              System.arraycopy(r, 1, rest, 0, rest.length);
              return row(k, k == null ? "" : k, rest);
            };
    return scope.bind(q).getResultList().stream().map(map).toList();
  }

  private static Row row(String key, String label, Object[] r) {
    long passed = number(r[1]), failed = number(r[2]);
    Double rate = passed + failed == 0 ? null : (double) passed / (passed + failed);
    Double avg = r[5] == null ? null : ((Number) r[5]).doubleValue();
    return new Row(
        key,
        label,
        number(r[0]),
        passed,
        failed,
        number(r[3]),
        number(r[4]),
        rate,
        avg,
        (Instant) r[6]);
  }

  private static Row relabel(Row r, String label) {
    return new Row(
        r.key(),
        label,
        r.builds(),
        r.passed(),
        r.failed(),
        r.cancelled(),
        r.active(),
        r.successRate(),
        r.averageSeconds(),
        r.lastBuildAt());
  }

  private List<Failure> failures(Scope scope, Map<Long, String> names) {
    String where = scope.where();
    where = (where.isEmpty() ? " where " : where + " and ") + "b.status = :failed";
    TypedQuery<Build> q =
        em.createQuery(
            "select b from Build b"
                + where
                + " order by coalesce(b.finishedAt, b.startedAt) desc nulls last, b.id desc",
            Build.class);
    q.setParameter("failed", Build.Status.FAILED).setMaxResults(MAX_FAILURES);
    return scope.bind(q).getResultList().stream()
        .map(b ->
            new Failure(
                b.id,
                b.projectId,
                names.get(b.projectId),
                b.number,
                b.branch,
                b.revision,
                b.finishedAt))
        .toList();
  }

  /** sum() is null when no rows match. */
  private static long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }

  /** The seconds a finished build ran, null while it is unfinished. */
  private static final String DURATION_SECONDS =
      "case when finishedat is not null and startedat is not null"
          + " then extract(epoch from finishedat) - extract(epoch from startedat) end";

  /** The seconds a started build waited in the queue, null before it starts. */
  private static final String QUEUE_SECONDS =
      "case when startedat is not null"
          + " then extract(epoch from startedat)"
          + " - extract(epoch from coalesce(requestedat, startedat)) end";

  /**
   * The SQL metric columns after an optional bucket column: builds, passed, failed, cancelled,
   * active, average duration, p50, p95 and average queue wait.
   */
  private static final String METRICS =
      "count(*), "
          + "count(*) filter (where status = 'PASSED'), "
          + "count(*) filter (where status = 'FAILED'), "
          + "count(*) filter (where status = 'CANCELLED'), "
          + "count(*) filter (where status in ('QUEUED', 'RUNNING')), "
          + "avg("
          + DURATION_SECONDS
          + "), "
          + "percentile_cont(0.5) within group (order by "
          + DURATION_SECONDS
          + "), "
          + "percentile_cont(0.95) within group (order by "
          + DURATION_SECONDS
          + "), "
          + "avg("
          + QUEUE_SECONDS
          + ")";

  /** One time bucket; the success rate and durations are null when no build qualified. */
  public record TrendPoint(
      Instant bucket,
      long builds,
      long passed,
      long failed,
      long cancelled,
      long active,
      Double successRate,
      Double averageDurationSeconds,
      Double p50DurationSeconds,
      Double p95DurationSeconds,
      Double averageQueueSeconds) {}

  /** One trigger's buckets over the shared time grid. */
  public record TriggerSeries(String trigger, List<TrendPoint> buckets) {}

  /** One fixed duration histogram bucket. */
  public record HistogramBucket(String label, long builds) {}

  /** A project or branch ranked by its average duration. */
  public record SlowRow(String label, long builds, Double averageSeconds) {}

  /** A revision that failed and later passed, newest first. */
  public record FlakyRevision(
      Long projectId,
      String project,
      String branch,
      String revision,
      long failures,
      long passes,
      Instant lastAt) {}

  /**
   * The build trends for the chosen project, range and bucket: the totals, the zero-filled series,
   * the per-trigger series, a duration histogram, the slowest projects and branches, and the flaky
   * revisions. {@code bucket} is {@code day} or {@code week}; {@code truncated} is true when the
   * range held more than {@value #MAX_BUCKETS} buckets and only the newest ones are listed.
   */
  public record Trends(
      List<ProjectRef> projects,
      Long projectId,
      String period,
      String bucket,
      boolean truncated,
      Instant from,
      Instant to,
      Instant generatedAt,
      TrendPoint totals,
      List<TrendPoint> buckets,
      List<TriggerSeries> byTrigger,
      List<HistogramBucket> histogram,
      List<SlowRow> slowestProjects,
      List<SlowRow> slowestBranches,
      List<FlakyRevision> flakyRevisions) {}

  /**
   * The build trends for the chosen project and range. {@code period} is a preset or {@code all};
   * {@code bucket} is {@code day}, {@code week} or omitted. {@code from} and {@code to} are
   * ISO-8601 instants that override {@code period} when supplied. Invalid values answer {@code
   * 400}; a {@code projectId} outside the caller's projects answers {@code 403}.
   */
  @GET
  @Path("/trends")
  public Trends trends(
      @QueryParam("projectId") Long projectId,
      @QueryParam("period") String period,
      @QueryParam("bucket") String bucket,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    String p = period == null || period.isBlank() ? "30d" : period.strip();
    if (!PERIODS.contains(p)) {
      throw invalid("Period must be one of 7d, 30d, 365d or all.");
    }
    String unit =
        bucket == null || bucket.isBlank() ? null : bucket.strip().toLowerCase(Locale.ROOT);
    if (unit != null && !BUCKETS.contains(unit)) {
      throw invalid("Bucket must be day or week.");
    }
    Instant fromInstant = instant(from, "From");
    Instant toInstant = instant(to, "To");
    if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
      throw invalid("From must not be after To.");
    }
    Set<Long> allowed = allowedProjectIds();
    String key =
        allowed + "|" + projectId + "|" + p + "|" + unit + "|" + fromInstant + "|" + toInstant;
    CachedTrends current = cachedTrends;
    if (trendsCacheSeconds > 0
        && current != null
        && key.equals(current.key())
        && Instant.now().isBefore(current.expires())) {
      return current.trends();
    }
    Trends result = computeTrends(projectId, allowed, p, unit, fromInstant, toInstant);
    if (trendsCacheSeconds > 0) {
      cachedTrends = new CachedTrends(key, result, Instant.now().plusSeconds(trendsCacheSeconds));
    }
    return result;
  }

  private Trends computeTrends(
      Long projectId,
      Set<Long> allowed,
      String period,
      String requestedBucket,
      Instant fromInstant,
      Instant toInstant) {
    List<Project> projects =
        allowed == null
            ? Project.list("order by name")
            : Project.list("id in ?1 order by name", allowed);
    List<ProjectRef> refs = projects.stream().map(x -> new ProjectRef(x.id, x.name)).toList();
    if (projectId != null && refs.stream().noneMatch(r -> r.id().equals(projectId))) {
      throw new ForbiddenException();
    }
    Instant now = Instant.now();
    boolean explicit = fromInstant != null || toInstant != null;
    Collection<Long> ids = projectId != null ? List.of(projectId) : allowed;
    if (ids != null && ids.isEmpty()) {
      String unit =
          requestedBucket != null
              ? requestedBucket
              : defaultBucket(
                  period,
                  explicit,
                  fromInstant == null ? now : fromInstant,
                  toInstant == null ? now : toInstant);
      return emptyTrends(refs, projectId, period, unit, now);
    }
    Map<Long, String> names = projects.stream().collect(Collectors.toMap(x -> x.id, x -> x.name));
    Instant fullTo =
        toInstant != null
            ? toInstant
            : (fromInstant != null && fromInstant.isAfter(now) ? fromInstant : now);
    Instant fullFrom = explicit ? fromInstant : since(period, now);
    if (fullFrom == null) {
      fullFrom = earliest(ids, fullTo);
    }
    if (fullFrom == null || fullFrom.isAfter(fullTo)) {
      fullFrom = fullTo;
    }
    String unit =
        requestedBucket != null
            ? requestedBucket
            : defaultBucket(period, explicit, fullFrom, fullTo);
    List<Instant> starts = bucketStarts(fullFrom, fullTo, unit);
    if (unit.equals("day") && starts.size() > MAX_BUCKETS) {
      unit = "week";
      starts = bucketStarts(fullFrom, fullTo, unit);
    }
    boolean truncated = starts.size() > MAX_BUCKETS;
    if (truncated) {
      starts = starts.subList(starts.size() - MAX_BUCKETS, starts.size());
    }
    Scope scope = new Scope(ids, fullFrom, fullTo);
    return new Trends(
        refs,
        projectId,
        period,
        unit,
        truncated,
        fullFrom,
        fullTo,
        now,
        totalPoint(ids, fullFrom, fullTo),
        points(bucketRows(unit, ids, fullFrom, fullTo), starts),
        triggerSeries(unit, ids, fullFrom, fullTo, starts),
        histogram(ids, fullFrom, fullTo),
        slowest(rows("b.projectId", scope, 0), names),
        slowestBranches(rows("b.branch", scope, 0)),
        flaky(ids, fullFrom, fullTo, names));
  }

  private static String defaultBucket(String period, boolean explicit, Instant from, Instant to) {
    if (explicit) {
      return ChronoUnit.DAYS.between(from, to) <= 62 ? "day" : "week";
    }
    return period.equals("7d") || period.equals("30d") ? "day" : "week";
  }

  private static List<Instant> bucketStarts(Instant from, Instant to, String unit) {
    List<Instant> starts = new ArrayList<>();
    Instant cursor = align(from, unit);
    long days = unit.equals("week") ? 7 : 1;
    while (!cursor.isAfter(to)) {
      starts.add(cursor);
      cursor = cursor.plus(days, ChronoUnit.DAYS);
      if (starts.size() > 10 * MAX_BUCKETS * 40) {
        break;
      }
    }
    if (starts.isEmpty()) {
      starts.add(align(to, unit));
    }
    return starts;
  }

  private static Instant align(Instant at, String unit) {
    LocalDate date = LocalDate.ofInstant(at, ZoneOffset.UTC);
    if (unit.equals("week")) {
      date = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static TrendPoint emptyPoint(Instant bucket) {
    return new TrendPoint(bucket, 0, 0, 0, 0, 0, null, null, null, null, null);
  }

  private Trends emptyTrends(
      List<ProjectRef> refs, Long projectId, String period, String unit, Instant now) {
    return new Trends(
        refs,
        projectId,
        period,
        unit,
        false,
        now,
        now,
        now,
        emptyPoint(align(now, unit)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static String rangeSql(Collection<Long> ids) {
    List<String> parts = new ArrayList<>();
    if (ids != null) {
      parts.add(
          "projectid in ("
              + ids.stream().map(String::valueOf).collect(Collectors.joining(","))
              + ")");
    }
    parts.add("coalesce(requestedat, startedat) >= :from");
    parts.add("coalesce(requestedat, startedat) <= :to");
    return " where " + String.join(" and ", parts);
  }

  private Instant earliest(Collection<Long> ids, Instant to) {
    String sql =
        "select min(coalesce(requestedat, startedat)) from build"
            + " where coalesce(requestedat, startedat) <= :to";
    if (ids != null) {
      sql +=
          " and projectid in ("
              + ids.stream().map(String::valueOf).collect(Collectors.joining(","))
              + ")";
    }
    return toInstant(em.createNativeQuery(sql).setParameter("to", to).getSingleResult());
  }

  private TrendPoint totalPoint(Collection<Long> ids, Instant from, Instant to) {
    Object[] row =
        (Object[])
            em.createNativeQuery("select " + METRICS + " from build" + rangeSql(ids))
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    return point(null, row, 0);
  }

  private List<Object[]> bucketRows(String unit, Collection<Long> ids, Instant from, Instant to) {
    String sql =
        "select date_trunc('"
            + unit
            + "', coalesce(requestedat, startedat), 'UTC') as bucket, "
            + METRICS
            + " from build"
            + rangeSql(ids)
            + " group by 1 order by 1";
    return em.createNativeQuery(sql)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();
  }

  private static List<TrendPoint> points(List<Object[]> rows, List<Instant> starts) {
    Map<Instant, Object[]> byBucket = new LinkedHashMap<>();
    for (Object[] row : rows) {
      byBucket.put(toInstant(row[0]), row);
    }
    List<TrendPoint> result = new ArrayList<>(starts.size());
    for (Instant start : starts) {
      Object[] row = byBucket.get(start);
      result.add(row == null ? emptyPoint(start) : point(start, row, 1));
    }
    return result;
  }

  private List<TriggerSeries> triggerSeries(
      String unit, Collection<Long> ids, Instant from, Instant to, List<Instant> starts) {
    String sql =
        "select trigger, date_trunc('"
            + unit
            + "', coalesce(requestedat, startedat), 'UTC') as bucket, "
            + METRICS
            + " from build"
            + rangeSql(ids)
            + " group by 1, 2 order by 1, 2";
    List<Object[]> rows =
        em.createNativeQuery(sql).setParameter("from", from).setParameter("to", to).getResultList();
    Map<String, Map<Instant, Object[]>> byTrigger = new TreeMap<>();
    for (Object[] row : rows) {
      String trigger = row[0] == null ? "MANUAL" : row[0].toString();
      byTrigger.computeIfAbsent(trigger, k -> new LinkedHashMap<>()).put(toInstant(row[1]), row);
    }
    List<TriggerSeries> result = new ArrayList<>();
    for (Map.Entry<String, Map<Instant, Object[]>> entry : byTrigger.entrySet()) {
      List<TrendPoint> series = new ArrayList<>(starts.size());
      for (Instant start : starts) {
        Object[] row = entry.getValue().get(start);
        series.add(row == null ? emptyPoint(start) : point(start, row, 2));
      }
      result.add(new TriggerSeries(entry.getKey(), series));
    }
    return result;
  }

  private List<HistogramBucket> histogram(Collection<Long> ids, Instant from, Instant to) {
    String sql =
        "select case"
            + " when e < 60 then '< 1m'"
            + " when e < 300 then '1-5m'"
            + " when e < 900 then '5-15m'"
            + " when e < 3600 then '15-60m'"
            + " when e < 21600 then '1-6h'"
            + " else '> 6h' end as label, count(*)"
            + " from (select extract(epoch from finishedat) - extract(epoch from startedat) as e"
            + " from build"
            + rangeSql(ids)
            + " and finishedat is not null and startedat is not null) t"
            + " group by 1";
    List<Object[]> rows =
        em.createNativeQuery(sql).setParameter("from", from).setParameter("to", to).getResultList();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Object[] row : rows) {
      counts.put(row[0].toString(), number(row[1]));
    }
    List<HistogramBucket> result = new ArrayList<>(HISTOGRAM.size());
    for (String label : HISTOGRAM) {
      result.add(new HistogramBucket(label, counts.getOrDefault(label, 0L)));
    }
    return result;
  }

  private static List<SlowRow> slowest(List<Row> rows, Map<Long, String> names) {
    return rows.stream()
        .filter(r -> r.averageSeconds() != null)
        .sorted(Comparator.comparingDouble(Row::averageSeconds).reversed())
        .limit(MAX_SLOW)
        .map(r ->
            new SlowRow(
                names.getOrDefault(Long.valueOf(r.key()), r.key()), r.builds(), r.averageSeconds()))
        .toList();
  }

  private static List<SlowRow> slowestBranches(List<Row> rows) {
    return rows.stream()
        .filter(r -> r.averageSeconds() != null)
        .sorted(Comparator.comparingDouble(Row::averageSeconds).reversed())
        .limit(MAX_SLOW)
        .map(r ->
            new SlowRow(
                r.label() == null || r.label().isEmpty() ? "—" : r.label(),
                r.builds(),
                r.averageSeconds()))
        .toList();
  }

  private List<FlakyRevision> flaky(
      Collection<Long> ids, Instant from, Instant to, Map<Long, String> names) {
    String sql =
        "select projectid, branch, revision,"
            + " count(*) filter (where status = 'FAILED'),"
            + " count(*) filter (where status = 'PASSED'),"
            + " max(coalesce(finishedat, startedat))"
            + " from build"
            + rangeSql(ids)
            + " and status in ('PASSED', 'FAILED') and revision is not null and branch is not null"
            + " group by projectid, branch, revision"
            + " having count(*) filter (where status = 'FAILED') > 0"
            + " and count(*) filter (where status = 'PASSED') > 0"
            + " and min(coalesce(finishedat, startedat)) filter (where status = 'FAILED')"
            + " < max(coalesce(finishedat, startedat)) filter (where status = 'PASSED')"
            + " order by 6 desc nulls last, revision"
            + " limit "
            + MAX_FLAKY;
    List<Object[]> rows =
        em.createNativeQuery(sql).setParameter("from", from).setParameter("to", to).getResultList();
    List<FlakyRevision> result = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      Long projectId = row[0] == null ? null : ((Number) row[0]).longValue();
      result.add(
          new FlakyRevision(
              projectId,
              names.get(projectId),
              row[1] == null ? null : row[1].toString(),
              row[2] == null ? null : row[2].toString(),
              number(row[3]),
              number(row[4]),
              toInstant(row[5])));
    }
    return result;
  }

  private static TrendPoint point(Instant bucket, Object[] r, int offset) {
    long passed = number(r[offset + 1]), failed = number(r[offset + 2]);
    Double rate = passed + failed == 0 ? null : (double) passed / (passed + failed);
    return new TrendPoint(
        bucket,
        number(r[offset]),
        passed,
        failed,
        number(r[offset + 3]),
        number(r[offset + 4]),
        rate,
        decimal(r[offset + 5]),
        decimal(r[offset + 6]),
        decimal(r[offset + 7]),
        decimal(r[offset + 8]));
  }

  private static Double decimal(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  private static Instant instant(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw invalid(name + " must be an ISO-8601 instant.");
    }
  }

  /** A 400 whose JSON body carries a message the UI can show as-is. */
  private static BadRequestException invalid(String message) {
    return new BadRequestException(
        Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private static Instant toInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof java.time.OffsetDateTime offset) {
      return offset.toInstant();
    }
    if (value instanceof java.time.LocalDateTime local) {
      return local.toInstant(ZoneOffset.UTC);
    }
    return Instant.parse(value.toString());
  }
}
