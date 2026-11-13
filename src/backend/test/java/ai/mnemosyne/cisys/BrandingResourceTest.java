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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BrandingResourceTest {
  private static final String BRAND =
      "{\"name\":\"My CI\",\"headerColor\":\"#123456\",\"headingColor\":\"#345678\",\"buttonColor\":\"#567890\"}";

  @Test
  void anonymousCannotReadBranding() {
    given().get("/api/branding").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  void memberCannotUpdate() {
    given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotUpdate() {
    given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminCanSaveBranding() {
    given()
        .contentType("application/json")
        .body(BRAND)
        .put("/api/branding")
        .then()
        .statusCode(200)
        .body("name", equalTo("My CI"));
    given().get("/api/branding").then().statusCode(200).body("headerColor", equalTo("#123456"));
    given()
        .get("/api/branding/public")
        .then()
        .statusCode(200)
        .body("name", equalTo("My CI"))
        .body("buttonColor", equalTo("#567890"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void servesTheLogoAsAnImage() {
    given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(200);
    given().get("/api/branding/logo").then().statusCode(404);
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};
    String withLogo =
        BRAND.replace(
            "}",
            ",\"logo\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\"}");
    given()
        .contentType("application/json")
        .body(withLogo)
        .put("/api/branding")
        .then()
        .statusCode(200);
    byte[] served =
        given()
            .get("/api/branding/logo")
            .then()
            .statusCode(200)
            .contentType("image/png")
            .extract()
            .asByteArray();
    assertArrayEquals(png, served);
    given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(200);
  }

  @Test
  void anonymousCanRequestTheLogo() {
    given().get("/api/branding/logo").then().statusCode(anyOf(is(200), is(404)));
  }

  @Test
  void anonymousCanReadNameAndColorsOnly() {
    given()
        .get("/api/branding/public")
        .then()
        .statusCode(200)
        .body("name", notNullValue())
        .body("logo", nullValue())
        .body("background", nullValue());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidColors() {
    given()
        .contentType("application/json")
        .body(BRAND.replace("#123456", "red"))
        .put("/api/branding")
        .then()
        .statusCode(400);
  }
}
