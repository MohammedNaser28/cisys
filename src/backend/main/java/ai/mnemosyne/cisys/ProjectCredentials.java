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
 * reference, then the project's stored encrypted secret, then the project's {@code credentialRef}
 * environment variable. Only private projects have a token.
 *
 * <p>A stored secret is fail-closed: when it exists but cannot be decrypted, resolution fails
 * instead of silently falling back to a different credential.
 */
@ApplicationScoped
public class ProjectCredentials {
  /**
   * The build reference that means "use the project's stored secret". It cannot collide with a
   * credential reference, which must match {@code CISYS_GIT_[A-Z0-9_]+}.
   */
  static final String STORED_SECRET = "@project-secret";

  @Inject CredentialCipher cipher;

  /** True when the project has a stored secret or an environment reference. */
  public boolean configured(Project project) {
    return project != null && (project.credentialSecret != null || project.credentialRef != null);
  }

  /** The reference a new build should snapshot; the token itself is never copied. */
  public String snapshotRef(Project project) {
    if (project == null || !project.privateRepository) {
      return null;
    }
    return project.credentialSecret != null ? STORED_SECRET : project.credentialRef;
  }

  /** The project's token: the stored secret first, then its environment reference. */
  public String token(Project project) {
    if (project == null || !project.privateRepository) {
      return null;
    }
    return token(project, null, project.repositoryUrl);
  }

  /**
   * The token for a build: an administrator's reference wins, then the project's stored secret,
   * then its environment reference. A build snapshots {@link #STORED_SECRET} when the secret
   * existed at submit time, so rotating the secret applies to queued builds and clearing it falls
   * back to the environment reference, or fails as missing when the project has neither.
   */
  public String token(Project project, String reference) {
    return token(project, reference, project == null ? null : project.repositoryUrl);
  }

  /**
   * Resolves a build credential. A null reference or the stored-secret sentinel means that the
   * build is asking for the project's current credential, so its queued repository URL must still
   * be this project's current repository. Explicit environment references are build snapshots (or
   * administrator per-build references) and are intentionally not tied to the project row.
   */
  public String token(Project project, String reference, String buildRepositoryUrl) {
    if (project == null) {
      return env(reference);
    }
    if (!project.privateRepository) {
      return null;
    }
    if (reference == null || STORED_SECRET.equals(reference)) {
      if (!Objects.equals(
          RepositoryUrls.normalize(buildRepositoryUrl),
          RepositoryUrls.normalize(project.repositoryUrl))) {
        throw new IllegalStateException("Missing Git credential");
      }
      if (project.credentialSecret != null) {
        return secret(project);
      }
      if (project.credentialRef != null) {
        return env(project.credentialRef);
      }
      if (STORED_SECRET.equals(reference)) {
        // The build snapshotted a secret that has since been cleared and the project has no
        // environment reference to fall back to.
        throw new IllegalStateException("Missing Git credential");
      }
      return null;
    }
    return env(reference);
  }

  /** Decrypts the project's stored secret. Protected so tests can substitute a fake. */
  protected String secret(Project project) {
    return cipher.decrypt(project.credentialSecret, project.id);
  }

  /** Reads an environment credential. Protected so tests can substitute a fake. */
  protected String env(String reference) {
    if (reference == null) {
      return null;
    }
    if (STORED_SECRET.equals(reference)) {
      // The sentinel names the project's stored secret, never an environment variable; reject it
      // here so no future caller can turn it into a System.getenv lookup.
      throw new IllegalStateException("Missing Git credential");
    }
    String token = System.getenv(reference);
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("Missing Git credential");
    }
    return token;
  }
}
