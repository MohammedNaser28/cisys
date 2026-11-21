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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Build statistics across projects: admins see every project, global leads the projects they lead.
 * Not to be confused with {@link BuildReport}, the per-build workflow reports.
 */
@Path("/api/reports/builds")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class BuildStatisticsResource {
  static final List<String> PERIODS = List.of("7d", "30d", "365d", "all");
  static final int MAX_BRANCHES = 20, MAX_FAILURES = 20;

  @Inject SecurityIdentity identity;
  @Inject ProjectAccess access;
  @Inject EntityManager em;
  @Inject BuildStatisticsPdf pdf;

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
    String file = "build-report-" + LocalDate.ofInstant(s.generatedAt(), ZoneOffset.UTC) + ".pdf";
    return Response.ok(pdf.render(s))
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
    Scope scope = new Scope(projectId != null ? List.of(projectId) : allowed, since(p, now));
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

  /** Null for admins (every project), the effectively led projects for global leads. */
  private Set<Long> allowedProjectIds() {
    if (access.isAdmin()) {
      return null;
    }
    if (!identity.hasRole("lead")) {
      throw new ForbiddenException();
    }
    return Project.<Project>listAll().stream()
        .filter(p -> ProjectRoles.of(p.id, access.subject()) == ProjectMember.TeamRole.LEAD)
        .map(p -> p.id)
        .collect(Collectors.toSet());
  }

  private static Instant since(String period, Instant now) {
    return switch (period) {
      case "7d" -> now.minus(7, ChronoUnit.DAYS);
      case "30d" -> now.minus(30, ChronoUnit.DAYS);
      case "365d" -> now.minus(365, ChronoUnit.DAYS);
      default -> null;
    };
  }

  /** {@code ids} null means every project, {@code since} null means all time. */
  private record Scope(Collection<Long> ids, Instant since) {
    String where() {
      List<String> parts = new ArrayList<>();
      if (ids != null) {
        parts.add("b.projectId in :ids");
      }
      if (since != null) {
        parts.add("coalesce(b.requestedAt, b.startedAt) >= :since");
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
}
