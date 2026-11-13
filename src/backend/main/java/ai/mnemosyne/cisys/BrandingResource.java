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
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Base64;

@Path("/api/branding")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BrandingResource {
  @Inject SecurityIdentity identity;
  @Inject EntityManager em;

  /**
   * The name and colors only, readable without signing in so the Keycloak login pages can use them.
   */
  public record PublicBranding(
      String name, String headerColor, String headingColor, String buttonColor) {}

  public record Input(
      String name,
      String headerColor,
      String headingColor,
      String buttonColor,
      String logo,
      String background) {}

  @GET
  public Branding get() {
    requireRole();
    Branding b = em.find(Branding.class, 1L);
    return b == null ? new Branding() : b;
  }

  @GET
  @Path("/public")
  @PermitAll
  public PublicBranding publicBranding() {
    Branding b = em.find(Branding.class, 1L);
    if (b == null) b = new Branding();
    return new PublicBranding(b.name, b.headerColor, b.headingColor, b.buttonColor);
  }

  /** The logo as an image, readable without signing in so the Keycloak login pages can show it. */
  @GET
  @Path("/logo")
  @PermitAll
  @Produces({"image/png", "image/jpeg"})
  public Response logo() {
    Branding b = em.find(Branding.class, 1L);
    if (b == null || b.logo == null) throw new NotFoundException();
    String type = b.logo.substring("data:".length(), b.logo.indexOf(';'));
    byte[] bytes = Base64.getDecoder().decode(b.logo.substring(b.logo.indexOf(',') + 1));
    return Response.ok(bytes, type).header("Cache-Control", "no-cache").build();
  }

  @PUT
  @Transactional
  public Branding update(Input input) {
    if (!identity.hasRole("admin")) throw new ForbiddenException();
    if (input == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().trim().length() > 80)
      throw new BadRequestException("A name of up to 80 characters is required");
    validateColor(input.headerColor());
    validateColor(input.headingColor());
    validateColor(input.buttonColor());
    validateImage(input.logo());
    validateImage(input.background());
    Branding b = em.find(Branding.class, 1L);
    if (b == null) {
      b = new Branding();
      em.persist(b);
    }
    b.name = input.name().trim();
    b.headerColor = input.headerColor();
    b.headingColor = input.headingColor();
    b.buttonColor = input.buttonColor();
    b.logo = emptyToNull(input.logo());
    b.background = emptyToNull(input.background());
    em.flush();
    return b;
  }

  private void requireRole() {
    if (!(identity.hasRole("admin") || identity.hasRole("lead") || identity.hasRole("member")))
      throw new ForbiddenException();
  }

  private static void validateColor(String color) {
    if (color == null || !color.matches("#[0-9a-fA-F]{6}"))
      throw new BadRequestException("Colors must use #RRGGBB format");
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static void validateImage(String value) {
    if (value == null || value.isBlank()) return;
    if (value.length() > 340000
        || !(value.startsWith("data:image/png;base64,")
            || value.startsWith("data:image/jpeg;base64,")))
      throw new BadRequestException("Images must be PNG or JPEG and at most 250 KB");
    try {
      byte[] bytes = Base64.getDecoder().decode(value.substring(value.indexOf(',') + 1));
      if (bytes.length > 250000 || bytes.length < 8) throw new IllegalArgumentException();
      boolean png =
          value.startsWith("data:image/png")
              && bytes[0] == (byte) 0x89
              && bytes[1] == 0x50
              && bytes[2] == 0x4e
              && bytes[3] == 0x47;
      boolean jpeg =
          value.startsWith("data:image/jpeg")
              && bytes[0] == (byte) 0xff
              && bytes[1] == (byte) 0xd8
              && bytes[2] == (byte) 0xff;
      if (!png && !jpeg) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Invalid image");
    }
  }
}
