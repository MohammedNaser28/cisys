/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.lib.Repository;

/**
 * The Git history of a project page, newest first. Public projects are readable by anyone; private
 * projects are readable by their members and admins, with the same 404 rule as the rest of the
 * page. The history comes from the project's bare mirror. When the mirror directory or the branch
 * is absent, the page falls back to the commits that already have builds; a mirror read failure is
 * a gateway error.
 */
@Path("/api/project-pages/{name}/commits")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@PublicRateLimited
public class ProjectCommitResource {
  /** The API exposes at most this many commits from the start of a branch's history. */
  static final int MAX_COMMITS = 5000;

  static final int MAX_LIMIT = 100;
  static final int RETRY_AFTER_SECONDS = 1;
  static final String HISTORY_LIMIT = "HISTORY_LIMIT";
  static final String HISTORY_UNAVAILABLE = "HISTORY_UNAVAILABLE";

  @Inject ProjectAccess access;
  @Inject RepositoryMirror mirrors;
  @Inject ProjectCredentials credentials;
  @Inject EntityManager entityManager;

  /** A build of one commit, with only the fields the commit view links with. */
  public record CommitBuild(
      Long id, Long number, Build.Status status, String branch, Build.Trigger trigger) {}

  /** One commit and every build of it, whichever branch each build used. */
  public record CommitView(
      String revision,
      String message,
      String author,
      Instant authoredAt,
      List<CommitBuild> builds) {}

  /** A page of commits and the reason the page is incomplete, if it is. */
  public record Commits(List<CommitView> commits, boolean hasMore, String reason) {}

  /**
   * The branch's commits, newest first. The API exposes positions 0 through {@link #MAX_COMMITS}-1;
   * {@code HISTORY_LIMIT} is reported only when more history exists beyond that window. The mirror
   * is read under a non-blocking lock and released before the database is queried.
   */
  @GET
  public Response list(
      @PathParam("name") String name,
      @QueryParam("branch") String branch,
      @QueryParam("skip") @DefaultValue("0") int skip,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    Project project = access.visible(name);
    String branchName = branch == null ? "" : branch.trim();
    if (branchName.isEmpty()) {
      return error(400, "A branch is required");
    }
    if (skip < 0 || skip > MAX_COMMITS) {
      return error(400, "skip must be between 0 and " + MAX_COMMITS);
    }
    if (!Repository.isValidRefName("refs/heads/" + branchName)) {
      return error(400, "branch is not a valid Git branch name");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      return error(400, "limit must be between 1 and " + MAX_LIMIT);
    }

    int window = Math.min(limit, MAX_COMMITS - skip);
    int fetchLimit = window + 1;
    boolean atBoundary = skip + limit >= MAX_COMMITS;
    List<RepositoryMirror.CommitInfo> commits;
    boolean fallback;
    if (project.privateRepository && !credentials.configured(project)) {
      // The poller never fetched this repository, so any mirror is stale or absent.
      commits = List.of();
      fallback = true;
    } else if (!Files.isDirectory(mirrors.path(project.id))) {
      commits = List.of();
      fallback = true;
    } else {
      ReentrantLock lock = mirrors.lock(project.id);
      if (!lock.tryLock()) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .header("Retry-After", RETRY_AFTER_SECONDS)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "The project's Git mirror is busy; try again"))
            .build();
      }
      try {
        if (!Files.isDirectory(mirrors.path(project.id))) {
          commits = List.of();
          fallback = true;
        } else {
          try {
            RepositoryMirror.CommitLog log =
                mirrors.commitLog(project.id, branchName, skip, fetchLimit);
            if (log.branchPresent()) {
              commits = log.commits();
              fallback = false;
            } else {
              commits = List.of();
              fallback = true;
            }
          } catch (Exception e) {
            if (Files.isDirectory(mirrors.path(project.id))) {
              return error(502, "Unable to read commits from the repository mirror");
            }
            commits = List.of();
            fallback = true;
          }
        }
      } finally {
        lock.unlock();
      }
    }

    List<RepositoryMirror.CommitInfo> page;
    boolean extra;
    boolean hasMore;
    if (fallback) {
      List<String> revisions = fallbackRevisions(project.id, branchName, skip, fetchLimit);
      extra = revisions.size() > window;
      hasMore = extra && !atBoundary;
      page =
          revisions.subList(0, Math.min(window, revisions.size())).stream()
              .map(revision -> new RepositoryMirror.CommitInfo(revision, null, null, null))
              .toList();
    } else {
      extra = commits.size() > window;
      hasMore = extra && !atBoundary;
      page = commits.subList(0, Math.min(window, commits.size()));
    }
    String reason = extra && atBoundary ? HISTORY_LIMIT : fallback ? HISTORY_UNAVAILABLE : null;
    return Response.ok(new Commits(views(project.id, page), hasMore, reason)).build();
  }

  /** Commits that already have builds, grouped by revision and ordered by their newest build. */
  private List<String> fallbackRevisions(Long projectId, String branch, int skip, int fetchLimit) {
    return entityManager
        .createQuery(
            "select b.revision from Build b"
                + " where b.projectId = :projectId and b.branch = :branch"
                + " and b.revision is not null and b.revision <> ''"
                + " group by b.revision order by max(b.number) desc",
            String.class)
        .setParameter("projectId", projectId)
        .setParameter("branch", branch)
        .setFirstResult(skip)
        .setMaxResults(fetchLimit)
        .getResultList();
  }

  /** One query for every build of every revision on the page. */
  private List<CommitView> views(Long projectId, List<RepositoryMirror.CommitInfo> commits) {
    if (commits.isEmpty()) {
      return List.of();
    }
    List<String> revisions = commits.stream().map(RepositoryMirror.CommitInfo::revision).toList();
    Map<String, List<CommitBuild>> byRevision = builds(projectId, revisions);
    return commits.stream()
        .map(commit ->
            new CommitView(
                commit.revision(),
                commit.message(),
                commit.author(),
                commit.authoredAt(),
                byRevision.getOrDefault(commit.revision(), List.of())))
        .toList();
  }

  private Map<String, List<CommitBuild>> builds(Long projectId, List<String> revisions) {
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select b.revision, b.id, b.number, b.status, b.branch, b.trigger"
                    + " from Build b where b.projectId = :projectId and b.revision in :revisions"
                    + " order by b.number desc",
                Object[].class)
            .setParameter("projectId", projectId)
            .setParameter("revisions", revisions)
            .getResultList();
    Map<String, List<CommitBuild>> byRevision = new HashMap<>();
    for (Object[] row : rows) {
      String revision = (String) row[0];
      byRevision
          .computeIfAbsent(revision, key -> new ArrayList<>())
          .add(
              new CommitBuild(
                  (Long) row[1],
                  (Long) row[2],
                  (Build.Status) row[3],
                  (String) row[4],
                  (Build.Trigger) row[5]));
    }
    return byRevision;
  }

  private static Response error(int status, String message) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("error", message))
        .build();
  }
}
