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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manages users through the Keycloak Admin REST API with the {@code cisys-backend} service account,
 * which needs the realm-management roles manage-users, view-users and view-realm.
 */
@ApplicationScoped
public class KeycloakUsers implements UserDirectory {
  private static final Logger LOG = Logger.getLogger(KeycloakUsers.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  // Users in one role are read in one request; a realm with more is not expected.
  private static final int ROLE_LIMIT = 10_000;

  @ConfigProperty(name = "cisys.keycloak.url")
  String keycloakUrl;

  @ConfigProperty(name = "cisys.keycloak.realm", defaultValue = "cisys")
  String realm;

  @ConfigProperty(name = "cisys.keycloak.admin-client-id")
  String clientId;

  @ConfigProperty(name = "cisys.keycloak.admin-client-secret")
  String clientSecret;

  @Inject ObjectMapper json;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private String token;
  private Instant tokenExpires = Instant.MIN;

  @Override
  public List<User> search(String search, int first, int max) {
    return users(
        send("GET", "/users?briefRepresentation=true" + searchQuery(search) + page(first, max)));
  }

  @Override
  public int count(String search) {
    String query = search == null || search.isBlank() ? "" : "?search=" + encode(search.strip());
    return send("GET", "/users/count" + query).asInt();
  }

  @Override
  public Optional<User> find(String id) {
    HttpResponse<String> response = call("GET", "/users/" + encode(id), null);
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    return Optional.of(user(body(response)));
  }

  @Override
  public List<User> inRole(String role) {
    return users(
        send(
            "GET",
            "/roles/" + encode(role) + "/users?briefRepresentation=true" + page(0, ROLE_LIMIT)));
  }

  @Override
  public String role(String id) {
    Set<String> names = new HashSet<>();
    send("GET", "/users/" + encode(id) + "/role-mappings/realm")
        .forEach(r -> names.add(r.path("name").asString()));
    String role = null;
    for (String r : ROLES) {
      if (names.contains(r)) {
        role = r;
      }
    }
    return role;
  }

  @Override
  public String create(User user, String password) {
    ObjectNode body = representation(user, false);
    body.put("enabled", true);
    body.putArray("credentials").add(credential(password));
    HttpResponse<String> response = call("POST", "/users", body);
    if (response.statusCode() == 409) {
      throw new ConflictException(conflict(response));
    }
    body(response);
    String location = response.headers().firstValue("Location").orElse("");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  @Override
  public void update(User user) {
    ObjectNode body = representation(user, true);
    body.put("enabled", user.enabled());
    HttpResponse<String> response = call("PUT", "/users/" + encode(user.id()), body);
    if (response.statusCode() == 409) {
      throw new ConflictException(conflict(response));
    }
    body(response);
  }

  @Override
  public void setRole(String id, String role) {
    String path = "/users/" + encode(id) + "/role-mappings/realm";
    ArrayNode remove = json.createArrayNode();
    send("GET", path)
        .forEach(r -> {
          String name = r.path("name").asString();
          if (ROLES.contains(name) && !name.equals(role)) {
            remove.add(r);
          }
        });
    if (!remove.isEmpty()) {
      body(call("DELETE", path, remove));
    }
    ArrayNode add = json.createArrayNode().add(send("GET", "/roles/" + encode(role)));
    body(call("POST", path, add));
  }

  @Override
  public void setPassword(String id, String password) {
    body(call("PUT", "/users/" + encode(id) + "/reset-password", credential(password)));
  }

  @Override
  public void delete(String id) {
    HttpResponse<String> response = call("DELETE", "/users/" + encode(id), null);
    if (response.statusCode() != 404) {
      body(response);
    }
  }

  /**
   * The username is always sent, as Keycloak's user profile validates it on updates too. Empty
   * fields are left out of a new user and sent as empty strings to clear them on an update.
   */
  private ObjectNode representation(User user, boolean clearEmpty) {
    ObjectNode body = json.createObjectNode();
    body.put("username", user.username());
    field(body, "email", user.email(), clearEmpty);
    field(body, "firstName", user.firstName(), clearEmpty);
    field(body, "lastName", user.lastName(), clearEmpty);
    return body;
  }

  private static void field(ObjectNode body, String name, String value, boolean clearEmpty) {
    if (value != null) {
      body.put(name, value);
    } else if (clearEmpty) {
      body.put(name, "");
    }
  }

  private ObjectNode credential(String password) {
    return json.createObjectNode()
        .put("type", "password")
        .put("value", password)
        .put("temporary", true);
  }

  private List<User> users(JsonNode array) {
    List<User> users = new ArrayList<>();
    array.forEach(u -> users.add(user(u)));
    return users;
  }

  private static User user(JsonNode u) {
    return new User(
        u.path("id").asString(),
        u.path("username").asString(),
        text(u, "email"),
        text(u, "firstName"),
        text(u, "lastName"),
        u.path("enabled").asBoolean(true));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /** The subject of the user whose username matches, or null. */
  public String subjectOf(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    String wanted = username.trim();
    for (User u : search(wanted, 0, 10_000)) {
      if (wanted.equalsIgnoreCase(u.username())) {
        return u.id();
      }
    }
    return null;
  }

  /** The username of the user with the given subject, or "unknown" when they do not exist. */
  public String usernameOf(String subject) {
    if (subject == null || subject.isBlank()) {
      return "unknown";
    }
    return find(subject).map(User::username).orElse("unknown");
  }

  private JsonNode send(String method, String path) {
    return body(call(method, path, null));
  }

  /** The parsed body of a successful response; other responses throw. */
  private JsonNode body(HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 400) {
      throw new RejectedException(message(response, "Keycloak refused the request"));
    }
    if (status / 100 != 2) {
      LOG.warnf("Keycloak answered HTTP %d to %s", status, response.uri().getPath());
      throw new UnavailableException("Keycloak answered HTTP " + status);
    }
    try {
      String body = response.body();
      return body == null || body.isBlank() ? json.nullNode() : json.readTree(body);
    } catch (JacksonException e) {
      throw new UnavailableException("Keycloak sent an unreadable answer");
    }
  }

  private String conflict(HttpResponse<String> response) {
    return message(response, "A user with this username or email already exists");
  }

  /** Keycloak's own error text, which is meant to be shown, such as a password policy failure. */
  private String message(HttpResponse<String> response, String fallback) {
    try {
      JsonNode body = json.readTree(response.body());
      for (String field : List.of("errorMessage", "error_description", "error")) {
        String value = text(body, field);
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    } catch (RuntimeException e) {
      // Not JSON: use the fallback.
    }
    return fallback;
  }

  private HttpResponse<String> call(String method, String path, JsonNode body) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(keycloakUrl + "/admin/realms/" + realm + path))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token())
              .header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      // A restarted Keycloak no longer accepts the cached token; fetch a new one next time.
      if (response.statusCode() == 401) {
        forgetToken();
      }
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("Interrupted");
    } catch (IOException | JacksonException e) {
      LOG.warnf("Keycloak is unreachable: %s", e.toString());
      throw new UnavailableException("Keycloak is unreachable");
    }
  }

  private synchronized void forgetToken() {
    token = null;
  }

  private synchronized String token() throws IOException, InterruptedException {
    if (token != null && Instant.now().isBefore(tokenExpires)) {
      return token;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + encode(clientId)
            + "&client_secret="
            + encode(clientSecret);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode body = response.statusCode() == 200 ? json.readTree(response.body()) : null;
    if (body == null || !body.hasNonNull("access_token")) {
      LOG.warnf(
          "Keycloak refused client '%s' (HTTP %d). It is defined in contrib/keycloak/realm.json, which is only imported into a new realm.",
          clientId, response.statusCode());
      throw new UnavailableException("Keycloak refused the service account");
    }
    token = body.get("access_token").asString();
    tokenExpires = Instant.now().plusSeconds(Math.max(0, body.path("expires_in").asLong(60) - 10));
    return token;
  }

  private static String searchQuery(String search) {
    return search == null || search.isBlank() ? "" : "&search=" + encode(search.strip());
  }

  private static String page(int first, int max) {
    return "&first=" + first + "&max=" + max;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
