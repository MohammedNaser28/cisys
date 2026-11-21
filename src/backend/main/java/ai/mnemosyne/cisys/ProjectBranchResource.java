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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * Branches of a project page. Public projects read the repository. Private projects read it too
 * when the project has a stored token or credential reference; otherwise they use the branches that
 * already have builds. Anonymous callers and users without access get a 404, just like the rest of
 * the private page.
 */
@Path("/api/project-pages/{name}/branches")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@PublicRateLimited
public class ProjectBranchResource {
  @Inject ProjectAccess access;
  @Inject GitRepositoryService repositories;
  @Inject ProjectCredentials credentials;
  @Inject EntityManager entityManager;

  /** The branch names, or none and the reason. */
  public record Branches(List<String> branches, String reason) {}

  /** Lists the branches; 400 when the repository is not allowed, 502 when it cannot be read. */
  @GET
  public Response list(@PathParam("name") String name) {
    Project project = access.visible(name);
    if (project.privateRepository && !credentials.configured(project)) {
      return Response.ok(new Branches(buildBranches(project), null)).build();
    }
    try {
      return Response.ok(new Branches(repositories.listBranches(project), null)).build();
    } catch (IllegalArgumentException e) {
      return error(400, e.getMessage());
    } catch (GitRepositoryService.BranchLookupException e) {
      return error(502, e.getMessage());
    }
  }

  private List<String> buildBranches(Project project) {
    return entityManager
        .createQuery(
            "select distinct b.branch from Build b where b.projectId = :projectId"
                + " and b.branch is not null and b.branch <> '' order by b.branch",
            String.class)
        .setParameter("projectId", project.id)
        .getResultList();
  }

  private static Response error(int status, String message) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("error", message))
        .build();
  }
}
