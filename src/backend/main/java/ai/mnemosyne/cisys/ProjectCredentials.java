/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/**
 * Resolves the Git token of a project in one place. The order is: an administrator's per-build
 * reference, then the project's team token, then the project's {@code credentialRef} environment
 * variable. Only private projects have a token: a public project never resolves its team's token.
 *
 * <p>A stored secret is fail-closed: when it exists but cannot be decrypted, resolution fails
 * instead of silently falling back to a different credential.
 */
@ApplicationScoped
public class ProjectCredentials {
  /** The prefix for a build reference bound to one team's stored token. */
  static final String STORED_SECRET_PREFIX = "@team-secret:";

  @Inject CredentialCipher cipher;

  /** True when a private project has a team token or an environment reference. */
  public boolean configured(Project project) {
    return project != null
        && project.privateRepository
        && (teamSecret(project) != null || project.credentialRef != null);
  }

  /** The reference a new build should snapshot; the token itself is never copied. */
  public String snapshotRef(Project project) {
    if (project == null || !project.privateRepository) {
      return null;
    }
    Team team = team(project);
    return team != null && team.tokenSecret != null ? storedSecret(team.id) : project.credentialRef;
  }

  /** The project's token: the stored secret first, then its environment reference. */
  public String token(Project project) {
    if (project == null || !project.privateRepository) {
      return null;
    }
    return token(project, null, project.repositoryUrl);
  }

  /**
   * The token for a build: an administrator's reference wins, then the project's team token, then
   * its environment reference. A build snapshots a team-bound reference when the secret existed at
   * submit time, so rotating the secret applies to queued builds only while the project still uses
   * that team and repository URL.
   */
  public String token(Project project, String reference) {
    return token(project, reference, project == null ? null : project.repositoryUrl);
  }

  /**
   * Resolves a build credential. A null reference or the team-token sentinel means that the build
   * is asking for the project's current credential, so its queued repository URL must still be this
   * project's current repository. Explicit environment references are build snapshots (or
   * administrator per-build references) and are intentionally not tied to the project row.
   */
  public String token(Project project, String reference, String buildRepositoryUrl) {
    if (project == null) {
      return env(reference);
    }
    if (!project.privateRepository) {
      return null;
    }
    if (reference == null || isStoredSecret(reference)) {
      if (!Objects.equals(
          RepositoryUrls.normalize(buildRepositoryUrl),
          RepositoryUrls.normalize(project.repositoryUrl))) {
        throw new IllegalStateException("Missing Git credential");
      }
      if (isStoredSecret(reference) && !Objects.equals(storedTeamId(reference), project.teamId)) {
        throw new IllegalStateException("The team changed since the build was queued");
      }
      if (teamSecret(project) != null) {
        return secret(project);
      }
      if (project.credentialRef != null) {
        return env(project.credentialRef);
      }
      if (isStoredSecret(reference)) {
        // The build snapshotted a secret that has since been cleared and the project has no
        // environment reference to fall back to.
        throw new IllegalStateException("Missing Git credential");
      }
      return null;
    }
    return env(reference);
  }

  /** Decrypts the project's team's stored secret. Protected so tests can substitute a fake. */
  protected String secret(Project project) {
    Team team = team(project);
    if (team == null || team.tokenSecret == null) {
      throw new CredentialCipher.CredentialException(
          "The stored team credential cannot be decrypted");
    }
    return cipher.decrypt(team.tokenSecret, team.id);
  }

  /** Finds the project's current team. Protected so tests can substitute a fake without a DB. */
  protected Team team(Project project) {
    return project == null || project.teamId == null ? null : Team.findById(project.teamId);
  }

  private byte[] teamSecret(Project project) {
    Team team = team(project);
    return team == null ? null : team.tokenSecret;
  }

  /** Reads an environment credential. Protected so tests can substitute a fake. */
  protected String env(String reference) {
    if (reference == null) {
      return null;
    }
    if (isStoredSecret(reference)) {
      throw new IllegalStateException("The team changed since the build was queued");
    }
    String token = System.getenv(reference);
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("Missing Git credential");
    }
    return token;
  }

  static String storedSecret(Long teamId) {
    if (teamId == null) {
      throw new IllegalArgumentException("team id is required");
    }
    return STORED_SECRET_PREFIX + teamId;
  }

  static boolean isStoredSecret(String reference) {
    return reference != null && reference.startsWith(STORED_SECRET_PREFIX);
  }

  private static Long storedTeamId(String reference) {
    if (!isStoredSecret(reference)) {
      return null;
    }
    try {
      return Long.valueOf(reference.substring(STORED_SECRET_PREFIX.length()));
    } catch (NumberFormatException e) {
      throw new IllegalStateException("The team changed since the build was queued");
    }
  }
}
