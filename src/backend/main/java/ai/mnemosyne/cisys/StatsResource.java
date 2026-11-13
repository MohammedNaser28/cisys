/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Statistics for the public front page, computed from public projects only. Private projects and
 * their builds are left out entirely, including from the totals.
 */
@Path("/api/stats")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StatsResource {
  @Inject EntityManager em;

  @ConfigProperty(name = "cisys.stats.cache-seconds", defaultValue = "30")
  long cacheSeconds;

  public record PublicStats(
      long projects,
      long builds,
      long passed,
      long failed,
      long running,
      long buildsLast7Days,
      Double successRate,
      Instant lastBuildAt) {}

  private record Cached(PublicStats stats, Instant expires) {}

  private volatile Cached cached;

  @GET
  @Path("/public")
  @PermitAll
  @PublicRateLimited
  public Response publicStats() {
    Cached current = cached;
    if (current == null || !Instant.now().isBefore(current.expires())) {
      current = new Cached(compute(), Instant.now().plusSeconds(cacheSeconds));
      if (cacheSeconds > 0) cached = current;
    }
    return Response.ok(current.stats())
        .header("Cache-Control", "public, max-age=" + cacheSeconds)
        .build();
  }

  private PublicStats compute() {
    long projects = Project.count("privateRepository", false);
    // Build.projectId is a plain Long, so restrict to public projects with a subquery.
    Object[] row =
        em.createQuery(
                """
                select count(b),
                       sum(case when b.status = 'PASSED' then 1 else 0 end),
                       sum(case when b.status = 'FAILED' then 1 else 0 end),
                       sum(case when b.status = 'RUNNING' then 1 else 0 end),
                       sum(case when b.startedAt >= :since then 1 else 0 end),
                       max(b.startedAt)
                from Build b
                where b.projectId in (select p.id from Project p where p.privateRepository = false)
                """,
                Object[].class)
            .setParameter("since", Instant.now().minus(7, ChronoUnit.DAYS))
            .getSingleResult();
    long builds = number(row[0]),
        passed = number(row[1]),
        failed = number(row[2]),
        running = number(row[3]),
        last7 = number(row[4]);
    Double successRate = passed + failed == 0 ? null : (double) passed / (passed + failed);
    return new PublicStats(
        projects, builds, passed, failed, running, last7, successRate, (Instant) row[5]);
  }

  /** sum() is null when no rows match. */
  private static long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }
}
