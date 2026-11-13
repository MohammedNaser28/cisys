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
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;

@Path("/api/images")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ImageResource {
  @Inject SecurityIdentity identity;
  @Inject BuildRunner runner;

  public record Selection(List<String> images) {}

  public record Pull(String name) {}

  public record LocalImage(String name, String id, String size, String created, boolean allowed) {}

  /** The images allowed for builds, for the build form. */
  @GET
  public List<String> allowed() {
    if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin")))
      throw new ForbiddenException();
    return names();
  }

  /** The images present in the local container runtime, marked with whether each is allowed. */
  @GET
  @Path("/local")
  public List<LocalImage> local() {
    requireAdmin();
    Set<String> allowed = new HashSet<>(names());
    List<BuildRunner.LocalImage> local;
    try {
      local = runner.images();
    } catch (Exception e) {
      throw failure(unreachable());
    }
    return local.stream()
        .map(i ->
            new LocalImage(i.name(), i.id(), i.size(), i.created(), allowed.contains(i.name())))
        .toList();
  }

  /** Pulls an image into the local container runtime. */
  @POST
  @Path("/local")
  public List<LocalImage> pull(Pull input) {
    requireAdmin();
    String name = validName(input == null ? null : input.name());
    Optional<String> failed;
    try {
      failed = runner.pull(name);
    } catch (Exception e) {
      throw failure(unreachable());
    }
    if (failed.isPresent()) throw failure("Unable to pull " + name + ": " + failed.get());
    return local();
  }

  /** Removes an image from the local container runtime, and from the allowed images. */
  @DELETE
  @Path("/local")
  public List<LocalImage> remove(@QueryParam("name") String name) {
    requireAdmin();
    validName(name);
    Optional<String> failed;
    try {
      failed = runner.remove(name);
    } catch (Exception e) {
      throw failure(unreachable());
    }
    if (failed.isPresent()) throw failure("Unable to remove " + name + ": " + failed.get());
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> AllowedImage.deleteById(name));
    return local();
  }

  /** Replaces the set of allowed images. */
  @PUT
  @Transactional
  public List<String> update(Selection input) {
    requireAdmin();
    if (input == null || input.images() == null || input.images().size() > 500)
      throw new BadRequestException("A list of images is required");
    Set<String> wanted = new TreeSet<>();
    for (String name : input.images()) wanted.add(validName(name));
    AllowedImage.delete("name not in ?1", wanted.isEmpty() ? Set.of("") : wanted);
    for (String name : wanted) {
      if (AllowedImage.findById(name) == null) {
        AllowedImage image = new AllowedImage();
        image.name = name;
        image.persist();
      }
    }
    return names();
  }

  private String unreachable() {
    return "Unable to run '"
        + runner.runtime()
        + "' on the backend host. Install podman or docker.";
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) throw new ForbiddenException();
  }

  private static String validName(String name) {
    if (name == null || !name.matches(AllowedImage.PATTERN))
      throw new WebApplicationException(
          Response.status(400)
              .type(MediaType.APPLICATION_JSON)
              .entity(Map.of("error", "Invalid image name"))
              .build());
    return name;
  }

  private static WebApplicationException failure(String message) {
    return new WebApplicationException(
        Response.status(502)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private static List<String> names() {
    return AllowedImage.<AllowedImage>listAll().stream().map(i -> i.name).sorted().toList();
  }
}
