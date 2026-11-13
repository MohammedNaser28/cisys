/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ImageResourceTest {
  private static final String SELECTION =
      "{\"images\":[\"alpine:3.23\",\"docker.io/library/fedora:44\"]}";

  @Test
  void anonymousCannotReadImages() {
    given().get("/api/images").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  void memberCanReadAllowedButNotAvailable() {
    given().get("/api/images").then().statusCode(200);
    given().get("/api/images/local").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotManageImages() {
    given()
        .contentType("application/json")
        .body(SELECTION)
        .put("/api/images")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"name\":\"alpine:3.23\"}")
        .post("/api/images/local")
        .then()
        .statusCode(403);
    given().queryParam("name", "alpine:3.23").delete("/api/images/local").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidPullAndRemoveNames() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"--all\"}")
        .post("/api/images/local")
        .then()
        .statusCode(400);
    given().queryParam("name", "-a").delete("/api/images/local").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminCanReplaceAllowedImages() {
    given()
        .contentType("application/json")
        .body(SELECTION)
        .put("/api/images")
        .then()
        .statusCode(200)
        .body("$", contains("alpine:3.23", "docker.io/library/fedora:44"));
    given()
        .contentType("application/json")
        .body("{\"images\":[\"alpine:3.23\"]}")
        .put("/api/images")
        .then()
        .statusCode(200)
        .body("$", contains("alpine:3.23"));
    given().get("/api/images").then().statusCode(200).body("$", contains("alpine:3.23"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidImageNames() {
    given()
        .contentType("application/json")
        .body("{\"images\":[\"--privileged\"]}")
        .put("/api/images")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void listsLocalImagesWhenARuntimeIsPresent() {
    given().get("/api/images/local").then().statusCode(anyOf(is(200), is(502)));
  }
}
