/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest
class ReportPluginTest {
  private static final String PATH = "/api/report-plugins";

  @Inject BuildRunner runner;

  @TempDir Path checkout;

  /** Runs a program named by an admin variable, as a tool like orangu would be. */
  @ApplicationScoped
  static class EchoReport extends ExecuteReport {
    @Override
    public String type() {
      return "echo";
    }

    @Override
    protected List<String> command(Context context) {
      return List.of(context.variables().get("tool"), context.options().get("name"));
    }
  }

  @BeforeEach
  @AfterEach
  void reset() {
    QuarkusTransaction.requiringNew().run(() -> ReportPlugin.deleteAll());
  }

  @Test
  void anonymousCannotRead() {
    given().get(PATH).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotChange() {
    given().get(PATH).then().statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"className\":\"x\"}")
        .put(PATH + "/echo")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminChoosesOnlyAnInstalledClass() {
    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("classes", hasItem(EchoReport.class.getName()))
        .body("classes", hasItem(ListReport.class.getName()));
    given()
        .contentType("application/json")
        .body("{\"className\":\"java.lang.Runtime\",\"variables\":\"\"}")
        .put(PATH + "/echo")
        .then()
        .statusCode(400);
    given()
        .contentType("application/json")
        .body("{\"className\":\"" + EchoReport.class.getName() + "\",\"variables\":\"tool=echo\"}")
        .put(PATH + "/echo")
        .then()
        .statusCode(200)
        .body("variables", equalTo("tool=echo"));
    given().delete(PATH + "/echo").then().statusCode(204);
    given().delete(PATH + "/echo").then().statusCode(404);
  }

  @Test
  void executeReportRunsTheProgramInTheCheckout() throws Exception {
    ReportType.Report r =
        new EchoReport()
            .run(
                new ReportType.Context(
                    checkout, null, Map.of("name", "hello"), Map.of("tool", "echo")));
    assertEquals("hello\n", r.content());
    assertThrows(
        IOException.class,
        () ->
            new EchoReport()
                .run(
                    new ReportType.Context(
                        checkout, null, Map.of("name", "/no/such"), Map.of("tool", "ls"))));
  }

  @Test
  void adminVariablesReachTheReport() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ReportPlugin p = new ReportPlugin();
          p.type = "echo";
          p.className = EchoReport.class.getName();
          p.variables = "tool=echo\nignored line";
          p.persist();
        });
    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = "Plugin-" + java.util.UUID.randomUUID();
              p.repositoryUrl = "https://github.com/octocat/plugin.git";
              p.ownerSubject = "owner-id";
              p.persist();
              Build b = new Build();
              b.projectId = p.id;
              b.number = Build.nextNumber(p.id);
              b.status = Build.Status.PASSED;
              b.branch = "main";
              b.requestedBy = "owner-id";
              b.persist();
              return b.id;
            });
    List<String> log = new ArrayList<>();
    runner.runReports(
        buildId,
        Workflow.parse(
            """
            name: main
            container: alpine:3.23
            jobs:
              - job: Test
                steps:
                  - name: Build
                    run: make
            reports:
              echo:
                - name: Hi
            """),
        checkout,
        log::add);
    assertEquals(List.of("Report Hi done"), log);
    List<BuildReport> stored =
        QuarkusTransaction.requiringNew().call(() -> BuildReport.of(buildId));
    assertEquals(1, stored.size());
    assertEquals("Hi\n", stored.get(0).content);
  }
}
