/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Read-only JGit operations shared by the REST API and, later, by the workflow support. Keeping the
 * Git access here means callers never have to talk to JGit directly.
 */
@ApplicationScoped
public class GitRepositoryService {
  static final int TIMEOUT_SECONDS = 10;

  @Inject RepositoryUrlValidator repositoryUrls;
  @Inject ProjectCredentials projectCredentials;

  /** Test-only: accept file repository URLs, which the request path never produces. */
  @ConfigProperty(name = "cisys.poll.allow-local", defaultValue = "false")
  boolean allowLocal;

  /**
   * Branches of the project's repository. Private projects need a credential, so they are not read
   * without one.
   */
  public List<String> listBranches(Project project) {
    if (project.privateRepository && !projectCredentials.configured(project)) {
      return List.of();
    }
    return listBranches(project.repositoryUrl, token(project));
  }

  /** Branches of an HTTPS repository on an allowed host. */
  public List<String> listBranches(String repositoryUrl) {
    return listBranches(repositoryUrl, null);
  }

  /** Branches of an HTTPS repository on an allowed host, with an already resolved token. */
  List<String> listBranches(String repositoryUrl, String token) {
    String cloneUrl = repositoryUrl == null ? null : RepositoryUrls.cloneUrl(repositoryUrl);
    repositoryUrls.validate(cloneUrl);
    try {
      return branchNames(remoteBranchRefs(cloneUrl, token));
    } catch (BranchLookupException e) {
      throw e;
    } catch (GitAPIException | RuntimeException e) {
      throw new BranchLookupException("Unable to read branches from the repository", e);
    }
  }

  /**
   * Checks that a candidate token can read the repository. It uses the same URL validation, host
   * allowlist, resolver and TLS configuration as a real fetch, and never stores anything.
   */
  public void verifyToken(String repositoryUrl, String token) {
    String cloneUrl = repositoryUrl == null ? null : RepositoryUrls.cloneUrl(repositoryUrl);
    repositoryUrls.validate(cloneUrl);
    try {
      remoteBranchRefs(cloneUrl, token);
    } catch (GitAPIException | RuntimeException e) {
      throw new BranchLookupException("The token could not access the repository", e);
    }
  }

  /**
   * The remote refs used for branch discovery. Overridden in tests so they never reach the network.
   */
  protected Collection<String> remoteBranchRefs(String repositoryUrl) throws GitAPIException {
    return remoteBranchRefs(repositoryUrl, null);
  }

  /** The remote refs used for branch discovery, using the resolved token when one is set. */
  protected Collection<String> remoteBranchRefs(String repositoryUrl, String token)
      throws GitAPIException {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("cisys-branches-");
      try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
        boolean verifyTls = repositoryUrls == null || repositoryUrls.verifyTls(repositoryUrl);
        configureTransport(git.getRepository().getConfig(), verifyTls);
        var command =
            git.lsRemote().setRemote(repositoryUrl).setHeads(true).setTimeout(TIMEOUT_SECONDS);
        if (token != null) {
          command.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", token));
        }
        return command.call().stream().map(Ref::getName).toList();
      }
    } catch (IOException e) {
      throw new GitAPIException("Unable to prepare the repository", e) {};
    } finally {
      if (dir != null) {
        try {
          FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
        } catch (IOException ignored) {
          // The temporary directory is not part of the result.
        }
      }
    }
  }

  /**
   * The current heads of a project's pull requests, as number to commit. The provider is read from
   * the project, so a stored build identity is never re-derived from a changed repository URL.
   */
  public Map<Integer, String> listPullRequests(Project project) {
    if (project.privateRepository && !projectCredentials.configured(project)) {
      return Map.of();
    }
    String url = project.repositoryUrl;
    boolean local = allowLocal && url != null && url.startsWith("file:");
    String cloneUrl = local ? url : RepositoryUrls.cloneUrl(url);
    if (local) {
      if (!Files.isDirectory(Path.of(URI.create(url)))) {
        throw new BranchLookupException("Local repository does not exist: " + url, null);
      }
    } else {
      // The host rule runs before any provider detection, so an unsupported host is skipped.
      repositoryUrls.validate(cloneUrl);
    }
    PullRequestProvider provider = provider(project);
    if (provider == null) {
      return Map.of();
    }
    try {
      Map<Integer, String> heads = new TreeMap<>();
      for (Map.Entry<String, String> ref :
          remoteRefs(cloneUrl, token(project), local || repositoryUrls.verifyTls(url)).entrySet()) {
        Integer number = provider.number(ref.getKey());
        if (number != null) {
          heads.put(number, ref.getValue());
        }
      }
      return heads;
    } catch (BranchLookupException e) {
      throw e;
    } catch (GitAPIException | RuntimeException e) {
      throw new BranchLookupException("Unable to read pull requests from the repository", e);
    }
  }

  /** The provider stored when the feature was enabled, or one detected from the URL. */
  static PullRequestProvider provider(Project project) {
    if (project.pullRequestProvider != null) {
      try {
        return PullRequestProvider.valueOf(project.pullRequestProvider);
      } catch (IllegalArgumentException ignored) {
        // A value written by an older version is treated as unset.
      }
    }
    return PullRequestProvider.from(project.repositoryUrl);
  }

  /**
   * Every remote ref name and commit. JGit has no ref-prefix filter for {@code ls-remote}, so all
   * refs are listed and the caller filters them. Overridden in tests so no test reaches the
   * network.
   */
  protected Map<String, String> remoteRefs(String repositoryUrl, String token, boolean verifyTls)
      throws GitAPIException {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("cisys-refs-");
      try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
        configureTransport(git.getRepository().getConfig(), verifyTls);
        var command = git.lsRemote().setRemote(repositoryUrl).setTimeout(TIMEOUT_SECONDS);
        if (token != null) {
          command.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", token));
        }
        Map<String, String> refs = new TreeMap<>();
        for (Ref ref : command.call()) {
          if (ref.getObjectId() != null) {
            refs.put(ref.getName(), ref.getObjectId().name());
          }
        }
        return refs;
      }
    } catch (IOException e) {
      throw new GitAPIException("Unable to prepare the repository", e) {};
    } finally {
      if (dir != null) {
        try {
          FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
        } catch (IOException ignored) {
          // The temporary directory is not part of the result.
        }
      }
    }
  }

  /** The project's resolved token; overridden in tests so branch discovery never reads the env. */
  protected String token(Project project) {
    try {
      return projectCredentials.token(project);
    } catch (CredentialCipher.CredentialException e) {
      throw new BranchLookupException(e.getMessage(), null);
    } catch (IllegalStateException e) {
      throw new BranchLookupException("Missing Git credential", null);
    }
  }

  /** Never follow redirects; certificate verification is off only for hosts an admin allowed. */
  static void configureTransport(StoredConfig cfg, boolean verifyTls) throws IOException {
    cfg.setString("http", null, "followRedirects", "false");
    // Written both ways so a reused repository cannot keep an earlier "false".
    cfg.setString("http", null, "sslVerify", verifyTls ? "true" : "false");
    cfg.save();
  }

  /** Keeps only local branch refs, without duplicates, sorted. */
  static List<String> branchNames(Collection<String> refs) {
    TreeSet<String> names = new TreeSet<>();
    for (String ref : refs) {
      if (ref == null || !ref.startsWith("refs/heads/")) {
        continue;
      }
      String name = ref.substring("refs/heads/".length());
      if (!name.isBlank()) {
        names.add(name);
      }
    }
    return List.copyOf(names);
  }

  /** The remote repository could not be read. */
  public static class BranchLookupException extends RuntimeException {
    public BranchLookupException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
