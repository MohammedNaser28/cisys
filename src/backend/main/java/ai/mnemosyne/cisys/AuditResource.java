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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The admin-only audit log. There is deliberately no write endpoint: events are produced by the
 * audited actions themselves, and the table is append-only from the application's point of view.
 */
@Path("/api/audit")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {
  static final int DEFAULT_PAGE = 50;
  static final int MAX_PAGE = 200;
  static final int MIN_SEARCH = 2;

  @Inject SecurityIdentity identity;

  /** One event. */
  public record EventView(
      Long id,
      Instant occurredAt,
      String action,
      AuditOutcome outcome,
      String actorSubject,
      String actorUsername,
      List<String> actorRoles,
      Long projectId,
      String projectName,
      AuditTargetType targetType,
      String targetId,
      String targetName,
      String summary,
      Map<String, Object> details,
      String requestId,
      String httpMethod,
      String path,
      Integer statusCode) {}

  /** One page of events and how many match in all. */
  public record Page(List<EventView> events, int total) {}

  /**
   * Events matching every supplied filter, newest first; {@code first}/{@code max} page the result.
   * {@code first} must be zero or more and {@code max} must be between 1 and {@value #MAX_PAGE};
   * invalid values are rejected with {@code 400}, never clamped. A non-blank {@code search} must be
   * at least {@value #MIN_SEARCH} characters.
   */
  @GET
  public Page list(
      @QueryParam("search") String search,
      @QueryParam("action") String action,
      @QueryParam("actor") String actor,
      @QueryParam("outcome") String outcome,
      @QueryParam("project") String project,
      @QueryParam("targetType") String targetType,
      @QueryParam("target") String target,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("first") String first,
      @QueryParam("max") String max) {
    requireAdmin();
    int offset =
        first == null || first.isBlank() ? 0 : integer(first, "first", 0, Integer.MAX_VALUE);
    int size = max == null || max.isBlank() ? DEFAULT_PAGE : integer(max, "max", 1, MAX_PAGE);
    Long projectId = project == null || project.isBlank() ? null : longValue(project, "project");
    if (hasText(search) && search.strip().length() < MIN_SEARCH) {
      throw new BadRequestException("search must be at least " + MIN_SEARCH + " characters");
    }
    StringBuilder where = new StringBuilder("1 = 1");
    Map<String, Object> params = new LinkedHashMap<>();
    if (hasText(search)) {
      where.append(
          " and (lower(summary) like :search escape '\\' or lower(action) like :search escape '\\'"
              + " or lower(coalesce(actorUsername, '')) like :search escape '\\'"
              + " or lower(coalesce(targetName, '')) like :search escape '\\'"
              + " or lower(coalesce(projectName, '')) like :search escape '\\')");
      params.put("search", like(search));
    }
    if (hasText(action)) {
      where.append(" and action = :action");
      params.put("action", action.strip());
    }
    if (hasText(actor)) {
      where.append(
          " and (actorSubject = :actorExact"
              + " or lower(coalesce(actorUsername, '')) like :actorLike escape '\\')");
      params.put("actorExact", actor.strip());
      params.put("actorLike", like(actor));
    }
    if (hasText(outcome)) {
      where.append(" and outcome = :outcome");
      params.put("outcome", enumValue(AuditOutcome.class, outcome));
    }
    if (projectId != null) {
      where.append(" and projectId = :project");
      params.put("project", projectId);
    }
    if (hasText(targetType)) {
      where.append(" and targetType = :targetType");
      params.put("targetType", enumValue(AuditTargetType.class, targetType));
    }
    if (hasText(target)) {
      where.append(
          " and (targetId = :targetExact"
              + " or lower(coalesce(targetName, '')) like :targetLike escape '\\')");
      params.put("targetExact", target.strip());
      params.put("targetLike", like(target));
    }
    if (hasText(from)) {
      where.append(" and occurredAt >= :from");
      params.put("from", instant(from, "from"));
    }
    if (hasText(to)) {
      where.append(" and occurredAt <= :to");
      params.put("to", instant(to, "to"));
    }
    String query = where + " order by occurredAt desc, id desc";
    long total = AuditEvent.count(where.toString(), params);
    int last = (int) Math.min(Integer.MAX_VALUE, (long) offset + size - 1);
    List<EventView> events =
        AuditEvent.<AuditEvent>find(query, params).range(offset, last).list().stream()
            .map(this::view)
            .toList();
    return new Page(events, (int) Math.min(Integer.MAX_VALUE, total));
  }

  /** One event, including its redacted details. */
  @GET
  @Path("/{id}")
  public EventView detail(@PathParam("id") Long id) {
    requireAdmin();
    AuditEvent event = AuditEvent.findById(id);
    if (event == null) {
      throw new NotFoundException();
    }
    return view(event);
  }

  private EventView view(AuditEvent e) {
    List<String> roles = new ArrayList<>();
    if (e.actorRoles != null && !e.actorRoles.isBlank()) {
      for (String role : e.actorRoles.split(",")) {
        if (!role.isBlank()) {
          roles.add(role.strip());
        }
      }
    }
    return new EventView(
        e.id,
        e.occurredAt,
        e.action,
        e.outcome,
        e.actorSubject,
        e.actorUsername,
        roles,
        e.projectId,
        e.projectName,
        e.targetType,
        e.targetId,
        e.targetName,
        e.summary,
        e.details == null ? Map.of() : e.details,
        e.requestId,
        e.httpMethod,
        e.path,
        e.statusCode);
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) {
      throw new jakarta.ws.rs.ForbiddenException();
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String like(String value) {
    String escaped =
        value
            .strip()
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static int integer(String value, String name, int min, int max) {
    int parsed;
    try {
      parsed = Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      throw new BadRequestException(name + " must be an integer");
    }
    if (parsed < min || parsed > max) {
      throw new BadRequestException(name + " must be between " + min + " and " + max);
    }
    return parsed;
  }

  private static long longValue(String value, String name) {
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      throw new BadRequestException(name + " must be a number");
    }
  }

  private static Instant instant(String value, String name) {
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new BadRequestException(name + " must be an ISO-8601 instant");
    }
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown " + type.getSimpleName() + " '" + value + "'");
    }
  }
}
