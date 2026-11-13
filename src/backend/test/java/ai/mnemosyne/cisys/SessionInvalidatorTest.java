/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionInvalidatorTest {
  private HttpServer keycloak;
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private int tokenStatus = 200;
  private int logoutStatus = 204;

  @BeforeEach
  void start() throws IOException {
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keycloak.createContext(
        "/realms/cisys/protocol/openid-connect/token",
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          calls.add("token " + form);
          byte[] body =
              "{\"access_token\":\"abc\",\"expires_in\":60}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(tokenStatus, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    keycloak.createContext(
        "/admin/realms/cisys/logout-all",
        exchange -> {
          calls.add(
              exchange.getRequestMethod()
                  + " logout-all "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(logoutStatus, -1);
          exchange.close();
        });
    keycloak.start();
  }

  @AfterEach
  void stop() {
    keycloak.stop(0);
  }

  private SessionInvalidator invalidator() {
    SessionInvalidator invalidator = new SessionInvalidator();
    invalidator.enabled = true;
    invalidator.keycloakUrl = "http://127.0.0.1:" + keycloak.getAddress().getPort();
    invalidator.realm = "cisys";
    invalidator.clientId = "cisys-backend";
    invalidator.clientSecret = "s&cret";
    return invalidator;
  }

  @Test
  void logsOutAllSessionsWithTheServiceAccount() {
    assertTrue(invalidator().invalidate());
    assertEquals(
        List.of(
            "token grant_type=client_credentials&client_id=cisys-backend&client_secret=s%26cret",
            "POST logout-all Bearer abc"),
        calls);
  }

  @Test
  void shutdownEventInvalidates() {
    invalidator().onShutdown(new ShutdownEvent());
    assertEquals(2, calls.size());
  }

  @Test
  void doesNothingWhenDisabled() {
    SessionInvalidator invalidator = invalidator();
    invalidator.enabled = false;
    invalidator.onShutdown(new ShutdownEvent());
    assertTrue(calls.isEmpty());
  }

  @Test
  void onlyDevelopmentStandardShutdownIsLiveReload() {
    assertTrue(SessionInvalidator.isLiveReload(LaunchMode.DEVELOPMENT, true));
    assertFalse(SessionInvalidator.isLiveReload(LaunchMode.DEVELOPMENT, false));
    assertFalse(SessionInvalidator.isLiveReload(LaunchMode.NORMAL, true));
    assertFalse(SessionInvalidator.isLiveReload(LaunchMode.NORMAL, false));
  }

  @Test
  void refusedClientDoesNotAttemptLogout() {
    tokenStatus = 401;
    assertFalse(invalidator().invalidate());
    assertEquals(1, calls.size());
  }

  @Test
  void failedLogoutIsReported() {
    logoutStatus = 403;
    assertFalse(invalidator().invalidate());
  }

  @Test
  void unreachableKeycloakDoesNotThrow() {
    SessionInvalidator invalidator = invalidator();
    keycloak.stop(0);
    assertFalse(invalidator.invalidate());
  }
}
