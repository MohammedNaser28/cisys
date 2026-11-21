/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The timer that turns new commits into builds. One @Scheduled tick walks each project's mirror,
 * and for every branch that has a workflow file at its tip queues the commits that appeared since
 * the branch was last seen. The commits go into the ordinary build queue, so the runner and the
 * workflow file are unchanged.
 *
 * <p>The last-seen commit of a branch is advanced only after a commit was queued (or was already
 * built), so a full queue slows polling down instead of losing commits. A branch is first seeded
 * with its tip, so enabling polling never builds history.
 */
@ApplicationScoped
public class CommitPoller {
  @Inject RepositoryMirror mirrors;
  @Inject BuildRunner builds;
  @Inject ProjectCredentials credentials;
  @Inject PullRequestPoller pullRequests;

  @ConfigProperty(name = "cisys.poll.every", defaultValue = "60s")
  Duration pollEvery;

  @ConfigProperty(name = "cisys.poll.max-commits", defaultValue = "1000")
  int maxCommits;

  private volatile Instant lastRun;

  /** Projects whose last fetch failed; each is logged once until it succeeds again. */
  private final Set<Long> fetchFailures = ConcurrentHashMap.newKeySet();

  /** Projects whose mirror could not be read; logged once until a poll reads it again. */
  private final Set<Long> readFailures = ConcurrentHashMap.newKeySet();

  /** Projects over the mirror size limit; polling them is stopped until cleanup clears the set. */
  private final Set<Long> oversized = ConcurrentHashMap.newKeySet();

  /** Projects already warned about the per-poll commit limit; logged once per project. */
  private final Set<Long> limitWarned = ConcurrentHashMap.newKeySet();

  /** How long a cleanup waits for a project lock before leaving it for the next tick. */
  private static final int LOCK_WAIT_SECONDS = 5;

  /** Polls at the configured interval. The check runs more often so an admin's interval applies. */
  @Scheduled(
      every = "{cisys.poll.tick:10s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    Instant now = Instant.now();
    Instant last = lastRun;
    Duration interval = interval();
    if (last != null && now.isBefore(last.plus(interval))) {
      return;
    }
    lastRun = now;
    try {
      poll();
    } catch (RuntimeException e) {
      Log.error("Commit polling failed", e);
    }
  }

  /** The configured interval, or the admin's value when one is set. */
  Duration interval() {
    Integer seconds =
        QuarkusTransaction.requiringNew().call(() -> Settings.current().pollIntervalSeconds);
    return seconds == null ? pollEvery : Duration.ofSeconds(Math.max(1, seconds));
  }

  /** The range the settings endpoint accepts for an admin-set interval. */
  static final int MIN_INTERVAL_SECONDS = 5, MAX_INTERVAL_SECONDS = 86400;

  /** The configured interval, clamped to the range the settings endpoint accepts. */
  int defaultIntervalSeconds() {
    long seconds = pollEvery.getSeconds();
    if (seconds < MIN_INTERVAL_SECONDS) {
      return MIN_INTERVAL_SECONDS;
    }
    if (seconds > MAX_INTERVAL_SECONDS) {
      return MAX_INTERVAL_SECONDS;
    }
    return (int) seconds;
  }

  /** One polling pass. Returns the number of builds queued; direct entry point for tests. */
  public int poll() {
    Settings settings = QuarkusTransaction.requiringNew().call(Settings::current);
    if (!settings.pollEnabled && !settings.pullRequestsEnabled) {
      cleanUpDisabled(settings.mirrorManualBuilds);
      return 0;
    }
    // Turning branch polling off resets the branch cursors, so a later enable seeds instead of
    // building what happened while it was off. The mirror is kept while pull request builds run.
    if (!settings.pollEnabled) {
      resetBranchCursors();
    }
    List<Project> projects = QuarkusTransaction.requiringNew().call(() -> Project.listAll());
    int queued = 0;
    Set<Long> live = new HashSet<>();
    for (Project project : projects) {
      live.add(project.id);
      ReentrantLock lock = mirrors.lock(project.id);
      lock.lock();
      try {
        queued += pollProject(project, settings);
      } catch (RuntimeException e) {
        Log.errorf(e, "Polling project %d failed; will retry", project.id);
      } finally {
        lock.unlock();
      }
    }
    sweepOrphans(live);
    return queued;
  }

  private int pollProject(Project project, Settings settings) {
    if (project.privateRepository && !credentials.configured(project)) {
      if (fetchFailures.add(project.id)) {
        Log.warnf("Skipping private project %d: no credential", project.id);
      }
      return 0;
    }
    if (oversized.contains(project.id)) {
      return 0;
    }
    try {
      mirrors.fetch(project);
      if (fetchFailures.remove(project.id)) {
        Log.infof("Project %d can be fetched again", project.id);
      }
    } catch (RepositoryMirror.TooLargeException e) {
      if (oversized.add(project.id)) {
        Log.warnf("Project %d: %s; polling stops", project.id, e.getMessage());
      }
      return 0;
    } catch (Exception e) {
      if (fetchFailures.add(project.id)) {
        Log.warnf("Project %d: could not fetch; will retry: %s", project.id, reason(e));
        Log.debugf(e, "Project %d fetch failure", project.id);
      }
      return 0;
    }
    int queued = 0;
    if (settings.pollEnabled) {
      try {
        queued += enqueue(project, settings);
      } catch (Exception e) {
        readFailure(project, "-", e);
      }
    }
    // Re-read under the project lock: a concurrent disable may have been committed after this
    // tick captured its settings, and a disabled feature must not recreate cursors.
    if (settings.pullRequestsEnabled && pullRequestsEnabled()) {
      queued += pullRequests.pollProject(project);
    }
    return queued;
  }

  private int enqueue(Project project, Settings settings) throws Exception {
    Set<String> branches = mirrors.branches(project.id);
    List<ProjectBranch> cursors =
        QuarkusTransaction.requiringNew().call(() -> ProjectBranch.list("projectId", project.id));
    boolean readFailed = false;
    for (ProjectBranch cursor : cursors) {
      try {
        if (!eligible(project, cursor.branch, settings.pollScope, branches)) {
          // Out of scope or no workflow file at the tip: drop the cursor so it seeds again later.
          removeCursor(project.id, cursor.branch);
        }
      } catch (Exception e) {
        readFailed = true;
        readFailure(project, cursor.branch, e);
      }
    }
    int queued = 0;
    for (String branch : branches) {
      try {
        if (!eligible(project, branch, settings.pollScope, branches)) {
          continue;
        }
        queued += pollBranch(project, settings, branch);
      } catch (Exception e) {
        readFailed = true;
        readFailure(project, branch, e);
      }
    }
    if (!readFailed) {
      readFailures.remove(project.id);
    }
    return queued;
  }

  /** A mirror read failed: stop this branch for this tick, never advance or prune its cursor. */
  private void readFailure(Project project, String branch, Exception e) {
    if (readFailures.add(project.id)) {
      Log.warnf(
          "Project %d branch %s: could not read the mirror; will retry: %s",
          project.id, branch, reason(e));
      Log.debugf(e, "Project %d branch %s mirror read failure", project.id, branch);
    }
  }

  /** The root cause's message, so an expected failure is one line instead of a stack trace. */
  private static String reason(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
  }

  /** Clears every branch cursor while branch polling is off, without touching any mirror. */
  private void resetBranchCursors() {
    QuarkusTransaction.requiringNew().run(() -> ProjectBranch.deleteAll());
  }

  private void removeCursor(Long projectId, String branch) {
    QuarkusTransaction.requiringNew()
        .run(() -> ProjectBranch.delete("projectId = ?1 and branch = ?2", projectId, branch));
  }

  private boolean eligible(
      Project project, String branch, Settings.Scope scope, Set<String> branches) throws Exception {
    if (!branches.contains(branch)) {
      return false;
    }
    if (scope == Settings.Scope.MAIN_ONLY && !branch.equals("main") && !branch.equals("master")) {
      return false;
    }
    String tip = mirrors.tip(project.id, branch);
    return tip != null && mirrors.hasFile(project.id, tip, Workflow.PATH);
  }

  /** Queues the commits that appeared on one branch, oldest first, and advances its cursor. */
  private int pollBranch(Project project, Settings settings, String branch) throws Exception {
    String tip = mirrors.tip(project.id, branch);
    if (tip == null) {
      return 0;
    }
    ProjectBranch cursor =
        QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(project.id, branch));
    if (cursor == null) {
      // First sighting: seed the tip without building history.
      QuarkusTransaction.requiringNew()
          .run(() -> {
            ProjectBranch row = new ProjectBranch();
            row.projectId = project.id;
            row.branch = branch;
            row.lastSeenRevision = tip;
            row.persist();
          });
      return 0;
    }
    String from = cursor.lastSeenRevision;
    if (tip.equals(from)) {
      return 0;
    }
    List<String> commits;
    if (settings.pollGranularity == Settings.Granularity.TIP) {
      commits = List.of(tip);
    } else {
      // A rewritten branch (the stored commit is not an ancestor of the tip) yields only the tip.
      commits = mirrors.commitsSince(project.id, branch, from);
    }
    int queued = 0;
    boolean stopped = false;
    for (String commit : commits) {
      if (queued >= maxCommits) {
        stopped = true;
        if (limitWarned.add(project.id)) {
          Log.warnf(
              "Project %d branch %s: reached the %d commits per poll limit; the rest follow next tick",
              project.id, branch, maxCommits);
        }
        break;
      }
      if (!mirrors.hasFile(project.id, commit, Workflow.PATH)) {
        // No workflow file in this commit's tree: nothing to run, but do not look at it again.
        advance(project.id, branch, commit);
        continue;
      }
      if (alreadyBuilt(project.id, commit)) {
        advance(project.id, branch, commit);
        continue;
      }
      try {
        builds.submit(
            project.id,
            "poll",
            project.repositoryUrl,
            branch,
            credentials.snapshotRef(project),
            commit,
            Build.Trigger.POLL,
            "poll",
            null,
            null);
      } catch (NotFoundException e) {
        Log.debugf("Project %d was deleted during polling", project.id);
        stopped = true;
        break;
      } catch (WebApplicationException e) {
        if (queueFull(e)) {
          Log.debugf(
              "Project %d branch %s: the build queue is full; the rest follow next tick",
              project.id, branch);
        } else {
          Log.errorf(
              e, "Project %d branch %s: submitting a build failed; will retry", project.id, branch);
        }
        stopped = true;
        break;
      } catch (RuntimeException e) {
        Log.errorf(
            e, "Project %d branch %s: submitting a build failed; will retry", project.id, branch);
        stopped = true;
        break;
      }
      advance(project.id, branch, commit);
      queued++;
    }
    if (!stopped) {
      limitWarned.remove(project.id);
    }
    return queued;
  }

  static boolean queueFull(WebApplicationException e) {
    return e.getResponse() != null && e.getResponse().getStatus() == 429;
  }

  /**
   * A commit already built by the poller, or covered by a pull request build, is not polled again.
   * A manual build, whatever its status, never stops the poller.
   */
  private boolean alreadyBuilt(Long projectId, String revision) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.count(
                    "projectId = ?1 and revision = ?2 and (trigger = ?3 or trigger = ?4)",
                    projectId,
                    revision,
                    Build.Trigger.POLL,
                    Build.Trigger.PULL_REQUEST)
                > 0);
  }

  /** The committed value of the pull request switch, read under the project lock. */
  private boolean pullRequestsEnabled() {
    return QuarkusTransaction.requiringNew().call(() -> Settings.current().pullRequestsEnabled);
  }

  private void advance(Long projectId, String branch, String revision) {
    QuarkusTransaction.requiringNew()
        .run(() ->
            ProjectBranch.update(
                "lastSeenRevision = ?1 where projectId = ?2 and branch = ?3",
                revision,
                projectId,
                branch));
  }

  /**
   * Removes cursors and (when manual-build mirroring is off) mirrors while polling is off, so
   * switching it back on seeds instead of building what happened while it was off. A project with a
   * queued or running build is left alone when deleting mirrors, because that build reads its
   * mirror at run time.
   */
  private void cleanUpDisabled(boolean keepMirrors) {
    List<Project> projects = QuarkusTransaction.requiringNew().call(() -> Project.listAll());
    Set<Long> ids = new HashSet<>();
    for (Project project : projects) {
      ids.add(project.id);
      ReentrantLock lock = mirrors.lock(project.id);
      if (!tryLock(lock)) {
        continue;
      }
      try {
        pullRequests.disableProject(project.id);
        if (!keepMirrors) {
          if (hasActiveBuild(project.id)) {
            continue;
          }
          mirrors.delete(project.id);
          fetchFailures.remove(project.id);
          oversized.remove(project.id);
          limitWarned.remove(project.id);
        }
        QuarkusTransaction.requiringNew().run(() -> ProjectBranch.delete("projectId", project.id));
      } finally {
        lock.unlock();
      }
    }
    sweepOrphans(ids);
  }

  /** Waits a bounded time for a project lock; a miss is simply retried on the next tick. */
  private static boolean tryLock(ReentrantLock lock) {
    try {
      return lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Removes mirrors whose project is gone, unless an active build still reads them. */
  private void sweepOrphans(Set<Long> live) {
    Path root = mirrors.root();
    if (!Files.isDirectory(root)) {
      return;
    }
    try (var entries = Files.list(root)) {
      for (Path dir : entries.toList()) {
        Long id = parseProjectId(dir.getFileName().toString());
        if (id == null || live.contains(id)) {
          continue;
        }
        ReentrantLock lock = mirrors.lock(id);
        if (!tryLock(lock)) {
          continue;
        }
        try {
          if (hasActiveBuild(id)) {
            continue;
          }
          mirrors.delete(id);
        } finally {
          lock.unlock();
        }
      }
    } catch (java.io.IOException ignored) {
    }
  }

  private static Long parseProjectId(String name) {
    if (!name.endsWith(".git")) {
      return null;
    }
    try {
      return Long.parseLong(name.substring(0, name.length() - 4));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean hasActiveBuild(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.count(
                    "projectId = ?1 and trigger in ?2 and status in ?3",
                    projectId,
                    List.of(Build.Trigger.POLL, Build.Trigger.PULL_REQUEST, Build.Trigger.MANUAL),
                    List.of(Build.Status.QUEUED, Build.Status.RUNNING))
                > 0);
  }
}
