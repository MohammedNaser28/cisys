/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectCredentialsTest {
  /** Substitutes environment and team lookups so the resolution order is observable. */
  static class Fake extends ProjectCredentials {
    final Map<String, String> environment = new HashMap<>();
    final Map<Long, String> secrets = new HashMap<>();
    final Map<Long, Team> teams = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    @Override
    protected String env(String reference) {
      calls.add("env:" + reference);
      String token = environment.get(reference);
      if (token == null) {
        throw new IllegalStateException("Missing Git credential");
      }
      return token;
    }

    @Override
    protected String secret(Project project) {
      calls.add("secret");
      String token = secrets.get(project.teamId);
      if (token == null) {
        throw new CredentialCipher.CredentialException("The stored team credential is missing");
      }
      return token;
    }

    @Override
    protected Team team(Project project) {
      return project == null || project.teamId == null ? null : teams.get(project.teamId);
    }
  }

  private static Project project(boolean privateRepository, String credentialRef, String secret) {
    Project p = new Project();
    p.id = 1L;
    p.repositoryUrl = "https://github.com/example/repository";
    p.privateRepository = privateRepository;
    p.credentialRef = credentialRef;
    if (secret != null) {
      p.teamId = 7L;
    }
    return p;
  }

  private static Fake credentialsWithTeam(Project project) {
    Fake credentials = new Fake();
    if (project.teamId != null) {
      Team team = new Team();
      team.id = project.teamId;
      team.tokenSecret = new byte[] {1};
      credentials.teams.put(team.id, team);
    }
    return credentials;
  }

  @Test
  void teamSecretWinsOverTheEnvironmentReference() {
    Project p = project(true, "CISYS_GIT_ENV", "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.secrets.put(7L, "stored-token");
    credentials.environment.put("CISYS_GIT_ENV", "env-token");

    assertEquals("stored-token", credentials.token(p));
    assertEquals(List.of("secret"), credentials.calls);
  }

  @Test
  void environmentReferenceIsUsedWithoutATeamSecret() {
    Project p = project(true, "CISYS_GIT_ENV", null);
    Fake credentials = credentialsWithTeam(p);
    credentials.environment.put("CISYS_GIT_ENV", "env-token");

    assertEquals("env-token", credentials.token(p));
  }

  @Test
  void administratorReferenceWinsOverTheTeamSecret() {
    Project p = project(true, "CISYS_GIT_ENV", "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.secrets.put(7L, "stored-token");
    credentials.environment.put("CISYS_GIT_ADMIN", "admin-token");

    assertEquals("admin-token", credentials.token(p, "CISYS_GIT_ADMIN"));
    assertEquals(List.of("env:CISYS_GIT_ADMIN"), credentials.calls);
  }

  @Test
  void aClearedTeamSecretFallsBackToTheEnvironmentReference() {
    Project p = project(true, "CISYS_GIT_ENV", null);
    p.teamId = 7L;
    Fake credentials = credentialsWithTeam(p);
    credentials.teams.get(7L).tokenSecret = null;
    credentials.environment.put("CISYS_GIT_ENV", "env-token");

    assertEquals("env-token", credentials.token(p, ProjectCredentials.storedSecret(7L)));
  }

  @Test
  void publicProjectsHaveNoToken() {
    Project p = project(false, "CISYS_GIT_ENV", "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.environment.put("CISYS_GIT_ENV", "env-token");

    assertNull(credentials.token(p));
    assertNull(credentials.token(p, "CISYS_GIT_ADMIN"));
    assertNull(credentials.snapshotRef(p));
  }

  @Test
  void aProjectMadePublicStopsResolvingItsTeamTokenUntilItIsPrivateAgain() {
    Project p = project(true, null, "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.secrets.put(7L, "stored-token");
    assertEquals("stored-token", credentials.token(p));

    p.privateRepository = false;
    assertFalse(credentials.configured(p), "a public project has no configured credential");
    assertNull(credentials.token(p), "a public project never uses its team's token");
    assertNull(credentials.token(p, "CISYS_GIT_ADMIN"));
    assertNull(credentials.snapshotRef(p));
    assertEquals(List.of("secret"), credentials.calls, "the public project read no secret");

    p.privateRepository = true;
    assertEquals("stored-token", credentials.token(p), "a private project resolves it again");
    assertEquals(ProjectCredentials.storedSecret(7L), credentials.snapshotRef(p));
  }

  @Test
  void snapshotUsesTheTeamSentinelAndNeverThePlaintext() {
    Project p = project(true, "CISYS_GIT_ENV", "stored");
    Fake credentials = credentialsWithTeam(p);

    assertEquals(ProjectCredentials.storedSecret(7L), credentials.snapshotRef(p));
    assertEquals("CISYS_GIT_ENV", credentials.snapshotRef(project(true, "CISYS_GIT_ENV", null)));
    assertNull(credentials.snapshotRef(project(true, null, null)));
  }

  @Test
  void aStoredTeamSecretThatCannotBeDecryptedFailsClosed() {
    Project p = project(true, "CISYS_GIT_ENV", "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.environment.put("CISYS_GIT_ENV", "env-token");

    assertThrows(CredentialCipher.CredentialException.class, () -> credentials.token(p));
    assertEquals(List.of("secret"), credentials.calls);
  }

  @Test
  void queuedTeamSecretResolutionStaysBoundToTheQueuedUrl() {
    Project p = project(true, null, "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.secrets.put(7L, "stored-token");
    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.credentialRef = ProjectCredentials.storedSecret(7L);
    build.repositoryUrl = p.repositoryUrl;

    assertEquals("stored-token", credentialsRunner(credentials).tokenFor(build, p));
    p.repositoryUrl = "https://github.com/example/other-repository";
    assertThrows(
        IllegalStateException.class, () -> credentialsRunner(credentials).tokenFor(build, p));
  }

  @Test
  void queuedTeamSecretFailsWhenTheProjectChangesTeams() {
    Project p = project(true, null, "stored");
    Fake credentials = credentialsWithTeam(p);
    Team other = new Team();
    other.id = 8L;
    other.tokenSecret = new byte[] {2};
    credentials.teams.put(other.id, other);
    credentials.secrets.put(7L, "team-a-token");
    credentials.secrets.put(8L, "team-b-token");
    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.credentialRef = ProjectCredentials.storedSecret(7L);
    build.repositoryUrl = p.repositoryUrl;

    p.teamId = 8L;
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> credentialsRunner(credentials).tokenFor(build, p));
    assertEquals("The team changed since the build was queued", error.getMessage());
    assertEquals(List.of(), credentials.calls);
  }

  @Test
  void pullRequestBuildsNeverResolveACredential() {
    BuildRunner runner = new BuildRunner();
    runner.projectCredentials = new Fake();
    Build build = new Build();
    build.trigger = Build.Trigger.PULL_REQUEST;

    assertNull(runner.tokenFor(build, project(true, "CISYS_GIT_ENV", "stored")));
  }

  @Test
  void explicitBuildReferenceWinsOverTheTeamSentinel() {
    Project p = project(true, null, "stored");
    Fake credentials = credentialsWithTeam(p);
    credentials.secrets.put(7L, "stored-token");
    credentials.environment.put("CISYS_GIT_ADMIN", "admin-token");
    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.repositoryUrl = p.repositoryUrl;
    build.credentialRef = "CISYS_GIT_ADMIN";

    assertEquals("admin-token", credentialsRunner(credentials).tokenFor(build, p));
  }

  private static BuildRunner credentialsRunner(ProjectCredentials credentials) {
    BuildRunner runner = new BuildRunner();
    runner.projectCredentials = credentials;
    return runner;
  }
}
