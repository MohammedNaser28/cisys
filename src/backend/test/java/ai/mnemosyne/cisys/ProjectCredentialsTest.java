/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectCredentialsTest {
  /** Substitutes the environment and the cipher so the resolution order is observable. */
  static class Fake extends ProjectCredentials {
    final Map<String, String> environment = new HashMap<>();
    final Map<Long, String> secrets = new HashMap<>();
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
      String token = secrets.get(project.id);
      if (token == null) {
        throw new CredentialCipher.CredentialException("The stored project credential is missing");
      }
      return token;
    }
  }

  private static Project project(boolean privateRepository, String credentialRef, String secret) {
    Project p = new Project();
    p.id = 1L;
    p.repositoryUrl = "https://github.com/example/repository";
    p.privateRepository = privateRepository;
    p.credentialRef = credentialRef;
    p.credentialSecret = secret == null ? null : new byte[] {1};
    return p;
  }

  @Test
  void storedSecretWinsOverTheEnvironmentReference() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "stored-token");
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project p = project(true, "CISYS_GIT_ENV", "stored");

    assertEquals("stored-token", credentials.token(p));
    assertEquals(List.of("secret"), credentials.calls);
  }

  @Test
  void environmentReferenceIsUsedWithoutASecret() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project p = project(true, "CISYS_GIT_ENV", null);

    assertEquals("env-token", credentials.token(p));
  }

  @Test
  void administratorReferenceWinsOverTheStoredSecret() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "stored-token");
    credentials.environment.put("CISYS_GIT_ADMIN", "admin-token");
    Project p = project(true, "CISYS_GIT_ENV", "stored");

    assertEquals("admin-token", credentials.token(p, "CISYS_GIT_ADMIN"));
    assertEquals(List.of("env:CISYS_GIT_ADMIN"), credentials.calls);
  }

  @Test
  void aClearedSecretFallsBackToTheEnvironmentReference() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project p = project(true, "CISYS_GIT_ENV", null);

    assertEquals("env-token", credentials.token(p, ProjectCredentials.STORED_SECRET));
  }

  @Test
  void aStoredSecretThatCannotBeDecryptedFailsClosed() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project p = project(true, "CISYS_GIT_ENV", "stored");

    assertThrows(CredentialCipher.CredentialException.class, () -> credentials.token(p));
    assertEquals(List.of("secret"), credentials.calls, "the environment must not be tried");
  }

  @Test
  void publicProjectsHaveNoToken() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project p = project(false, "CISYS_GIT_ENV", "stored");

    assertNull(credentials.token(p));
    assertNull(credentials.token(p, "CISYS_GIT_ADMIN"));
    assertNull(credentials.snapshotRef(p));
  }

  @Test
  void snapshotUsesTheSentinelForASecretAndTheReferenceOtherwise() {
    Fake credentials = new Fake();
    assertEquals(
        ProjectCredentials.STORED_SECRET,
        credentials.snapshotRef(project(true, "CISYS_GIT_ENV", "stored")));
    assertEquals("CISYS_GIT_ENV", credentials.snapshotRef(project(true, "CISYS_GIT_ENV", null)));
    assertNull(credentials.snapshotRef(project(true, null, null)));
  }

  @Test
  void aClearedSecretWithoutAnEnvironmentReferenceIsReportedAsMissing() {
    Project p = project(true, null, null);
    assertThrows(
        IllegalStateException.class, () -> new Fake().token(p, ProjectCredentials.STORED_SECRET));
  }

  @Test
  void aMissingEnvironmentReferenceIsReportedAsMissing() {
    Project p = project(true, "CISYS_GIT_MISSING", null);
    assertThrows(IllegalStateException.class, () -> new Fake().token(p));
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
  void manualBuildsResolveTheirSnapshotReference() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "stored-token");
    credentials.environment.put("CISYS_GIT_ADMIN", "admin-token");
    BuildRunner runner = new BuildRunner();
    runner.projectCredentials = credentials;
    Project p = project(true, "CISYS_GIT_ENV", "stored");

    Build manual = new Build();
    manual.trigger = Build.Trigger.MANUAL;
    manual.repositoryUrl = p.repositoryUrl;
    manual.credentialRef = ProjectCredentials.STORED_SECRET;
    assertEquals("stored-token", runner.tokenFor(manual, p));

    manual.credentialRef = "CISYS_GIT_ADMIN";
    assertEquals("admin-token", runner.tokenFor(manual, p));
  }

  @Test
  void aNullBuildReferenceUsesAStoredSecretOnlyForTheQueuedUrl() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "stored-token");
    Project project = project(true, null, "stored");

    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.repositoryUrl = project.repositoryUrl;
    assertEquals("stored-token", credentialsRunner(credentials).tokenFor(build, project));

    project.repositoryUrl = "https://github.com/example/other-repository";
    assertThrows(
        IllegalStateException.class, () -> credentialsRunner(credentials).tokenFor(build, project));
  }

  @Test
  void aNullBuildReferenceUsesAProjectEnvironmentOnlyForTheQueuedUrl() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "env-token");
    Project project = project(true, "CISYS_GIT_ENV", null);

    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.repositoryUrl = project.repositoryUrl;
    assertEquals("env-token", credentialsRunner(credentials).tokenFor(build, project));

    project.repositoryUrl = "https://github.com/example/other-repository";
    assertThrows(
        IllegalStateException.class, () -> credentialsRunner(credentials).tokenFor(build, project));
  }

  @Test
  void rotatingAStoredSecretAppliesToAQueuedBuildForTheSameUrl() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "rotated-token");
    Project project = project(true, null, "stored");

    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.credentialRef = ProjectCredentials.STORED_SECRET;
    build.repositoryUrl = project.repositoryUrl;

    assertEquals("rotated-token", credentialsRunner(credentials).tokenFor(build, project));
  }

  @Test
  void snapshottedAndAdministratorEnvironmentReferencesAreNotProjectBound() {
    Fake credentials = new Fake();
    credentials.environment.put("CISYS_GIT_ENV", "snapshot-token");
    credentials.environment.put("CISYS_GIT_ADMIN", "admin-token");
    Project project = project(true, "CISYS_GIT_ENV", null);
    BuildRunner runner = credentialsRunner(credentials);

    Build snapshot = new Build();
    snapshot.trigger = Build.Trigger.POLL;
    snapshot.credentialRef = "CISYS_GIT_ENV";
    snapshot.repositoryUrl = "https://github.com/example/old-repository";
    assertEquals("snapshot-token", runner.tokenFor(snapshot, project));

    Build admin = new Build();
    admin.trigger = Build.Trigger.MANUAL;
    admin.credentialRef = "CISYS_GIT_ADMIN";
    admin.repositoryUrl = snapshot.repositoryUrl;
    assertEquals("admin-token", runner.tokenFor(admin, project));
  }

  @Test
  void storedSecretIsNotSentToARepositoryThatChangedAfterQueueing() {
    Fake credentials = new Fake();
    credentials.secrets.put(1L, "stored-token");
    Build build = new Build();
    build.trigger = Build.Trigger.MANUAL;
    build.credentialRef = ProjectCredentials.STORED_SECRET;
    build.repositoryUrl = "https://github.com/example/old";
    Project project = project(true, null, "stored");
    project.repositoryUrl = "https://github.com/example/new";

    assertThrows(
        IllegalStateException.class, () -> credentialsRunner(credentials).tokenFor(build, project));
  }

  private static BuildRunner credentialsRunner(ProjectCredentials credentials) {
    BuildRunner runner = new BuildRunner();
    runner.projectCredentials = credentials;
    return runner;
  }
}
