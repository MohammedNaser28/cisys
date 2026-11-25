/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KeycloakUsersTest {
  private HttpServer keycloak;
  private String lastPath;
  private int tokenStatus = 200;
  private String listResponse = "[{\"id\":\"sub-123\",\"username\":\"ahmed\"}]";
  private String userResponse = "{\"id\":\"sub-123\",\"username\":\"ahmed\"}";

  @BeforeEach
  void start() throws IOException {
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keycloak.createContext(
        "/realms/cisys/protocol/openid-connect/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"abc\",\"expires_in\":60}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(tokenStatus, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    keycloak.createContext(
        "/admin/realms/cisys/users",
        exchange -> {
          String path = exchange.getRequestURI().toString();
          if (path.equals("/admin/realms/cisys/users")
              || path.startsWith("/admin/realms/cisys/users?")) {
            lastPath = path;
            byte[] b = listResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(b);
            }
            return;
          }
          if (path.equals("/admin/realms/cisys/users/sub-123")) {
            lastPath = path;
            byte[] b = userResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(b);
            }
            return;
          }
          // unknown subject → 404
          exchange.sendResponseHeaders(404, 0);
          exchange.close();
        });
    keycloak.start();
  }

  @AfterEach
  void stop() {
    keycloak.stop(0);
  }

  private KeycloakUsers users() {
    KeycloakUsers users = new KeycloakUsers();
    users.keycloakUrl = "http://127.0.0.1:" + keycloak.getAddress().getPort();
    users.realm = "cisys";
    users.clientId = "cisys-backend";
    users.clientSecret = "secret";
    users.json = new ObjectMapper();
    return users;
  }

  @Test
  void resolvesUsernameToSubject() {
    assertEquals("sub-123", users().subjectOf("ahmed"));
    assertEquals(
        "/admin/realms/cisys/users?briefRepresentation=true&search=ahmed&first=0&max=10000",
        lastPath);
  }

  @Test
  void unknownUsernameResolvesToNull() {
    listResponse = "[]";
    assertNull(users().subjectOf("ghost"));
  }

  @Test
  void resolvesSubjectToUsername() {
    assertEquals("ahmed", users().usernameOf("sub-123"));
    assertEquals("/admin/realms/cisys/users/sub-123", lastPath);
  }

  @Test
  void unknownSubjectResolvesToUnknown() {
    assertEquals("unknown", users().usernameOf("no-such-subject"));
  }

  @Test
  void refusedClientFailsLoudly() {
    tokenStatus = 401;
    assertThrows(UserDirectory.UnavailableException.class, () -> users().subjectOf("ahmed"));
  }
}
