/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.arc.ClientProxy;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The report types an admin configured, and the classes that can run them. */
@Path("/api/report-plugins")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportPluginResource {
  static final int MAX_VARIABLES = 4096;

  @Inject SecurityIdentity identity;
  @Inject AuditService audit;
  @Inject Instance<ReportType> reportTypes;

  /** A configured type. */
  public record Input(String className, String variables) {}

  /** A configured type. */
  public record View(String type, String className, String variables) {}

  /** The configured types, and the class names an admin may choose from. */
  public record Overview(List<View> plugins, List<String> classes) {}

  /** The configured types and the installable classes. */
  @GET
  public Overview list() {
    requireAdmin();
    return new Overview(
        ReportPlugin.<ReportPlugin>list("order by type").stream()
            .map(p -> new View(p.type, p.className, p.variables))
            .toList(),
        classes());
  }

  /** Sets the class and variables of a report type; the class must be an installed report type. */
  @PUT
  @Path("/{type}")
  @Transactional
  @Audited(
      action = AuditAction.REPORT_PLUGIN_SET,
      target = AuditTargetType.REPORT_PLUGIN,
      summary = "Configure a report type")
  public View set(@PathParam("type") String type, Input input) {
    requireAdmin();
    if (type == null || !type.matches(Workflow.REPORT_TYPE)) {
      throw error(400, "Not a valid report type");
    }
    String className = input == null || input.className() == null ? "" : input.className().strip();
    if (!classes().contains(className)) {
      throw error(400, "Not an installed report type class");
    }
    String variables = input.variables() == null ? "" : input.variables();
    if (variables.length() > MAX_VARIABLES) {
      throw error(400, "At most " + MAX_VARIABLES + " characters of variables are allowed");
    }
    ReportPlugin p = ReportPlugin.find("type", type).firstResult();
    if (p == null) {
      p = new ReportPlugin();
      p.type = type;
    }
    p.className = className;
    p.variables = variables;
    p.persist();
    audit.success(
        AuditAction.REPORT_PLUGIN_SET,
        AuditTargetType.REPORT_PLUGIN,
        p.id,
        type,
        (Long) null,
        (String) null,
        "Configured report type " + type,
        AuditDetails.of("type", type, "class", className));
    return new View(p.type, p.className, p.variables);
  }

  /** Removes a type's configuration. */
  @DELETE
  @Path("/{type}")
  @Transactional
  @Audited(
      action = AuditAction.REPORT_PLUGIN_DELETE,
      target = AuditTargetType.REPORT_PLUGIN,
      summary = "Remove a report type")
  public Response delete(@PathParam("type") String type) {
    requireAdmin();
    ReportPlugin p = ReportPlugin.find("type", type).firstResult();
    if (p == null) {
      throw new NotFoundException();
    }
    Long id = p.id;
    p.delete();
    audit.success(
        AuditAction.REPORT_PLUGIN_DELETE,
        AuditTargetType.REPORT_PLUGIN,
        id,
        type,
        (Long) null,
        (String) null,
        "Removed report type " + type,
        AuditDetails.of("type", type));
    return Response.noContent().build();
  }

  /** The class names of the installed report types. */
  List<String> classes() {
    List<String> names = new ArrayList<>();
    for (ReportType t : reportTypes) {
      names.add(ClientProxy.unwrap(t).getClass().getName());
    }
    names.sort(null);
    return names;
  }

  private static WebApplicationException error(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
  }
}
