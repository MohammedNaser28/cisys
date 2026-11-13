/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/projects/{projectId}/branches")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class BranchResource {
  @Inject SecurityIdentity identity;
  @Inject GitRepositoryService repositories;

  public record Branches(List<String> branches, String reason) {}

  private Project visible(Long id) {
    if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin")))
      throw new ForbiddenException();
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject() == null)
      throw new NotAuthorizedException("Bearer");
    Project p = Project.findById(id);
    if (p == null
        || (p.privateRepository
            && !identity.hasRole("admin")
            && !jwt.getSubject().equals(p.ownerSubject))) throw new NotFoundException();
    return p;
  }

  @GET
  public Response list(@PathParam("projectId") Long projectId) {
    Project project = visible(projectId);
    if (project.privateRepository) {
      return Response.ok(new Branches(List.of(), "private_repository")).build();
    }
    try {
      return Response.ok(new Branches(repositories.listBranches(project), null)).build();
    } catch (IllegalArgumentException e) {
      return error(400, e.getMessage());
    } catch (GitRepositoryService.BranchLookupException e) {
      return error(502, e.getMessage());
    }
  }

  private static Response error(int status, String message) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("error", message))
        .build();
  }
}
