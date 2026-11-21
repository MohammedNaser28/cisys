/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR
 *   DISTRIBUTION OF THIS FILE CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Applies the schema that upstream/main shipped to a real, empty PostgreSQL database, then applies
 * this branch's {@code R__schema.sql} on top of it. This is the upgrade path Flyway takes for an
 * existing installation: the repeatable migration is rerun because its content changed.
 *
 * <p>The fixture {@code schema/upstream-main-R__schema.sql} is a verbatim copy of {@code
 * src/backend/main/resources/db/migration/R__schema.sql} at upstream/main, so this test fails when
 * a column the new code depends on is only declared inside a {@code CREATE TABLE IF NOT EXISTS} and
 * an existing database therefore never receives it.
 */
@QuarkusTest
class SchemaUpgradeTest {
  private static final String CURRENT_SCHEMA = "db/migration/R__schema.sql";
  private static final String UPSTREAM_SCHEMA = "schema/upstream-main-R__schema.sql";

  @ConfigProperty(name = "quarkus.datasource.jdbc.url")
  String jdbcUrl;

  @ConfigProperty(name = "quarkus.datasource.username")
  String username;

  @ConfigProperty(name = "quarkus.datasource.password")
  String password;

  @Test
  @Timeout(120)
  void anUpstreamDatabaseUpgradesInPlaceAndTheMigrationStaysRepeatable() throws Exception {
    String database = "cisys_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection server = connectTo("postgres")) {
      try (Statement statement = server.createStatement()) {
        statement.execute("CREATE DATABASE " + database);
      }
    }
    try {
      try (Connection upgraded = connectTo(database)) {
        run(upgraded, UPSTREAM_SCHEMA);
        assertFalse(
            columnExists(upgraded, "project", "teamid"),
            "upstream/main must not have project.teamid; the fixture would be wrong");
        run(upgraded, CURRENT_SCHEMA);
      }
      try (Connection upgraded = connectTo(database)) {
        assertTrue(columnExists(upgraded, "project", "teamid"), "the upgrade adds project.teamid");
        assertEquals(1, columnCount(upgraded, "project", "teamid"), "teamid is declared once");
        assertEquals(1, teamForeignKeyCount(upgraded), "project.teamid has exactly one FK to team");

        // A second run is what every later deployment does; it must change nothing.
        run(upgraded, CURRENT_SCHEMA);
        assertEquals(
            1, columnCount(upgraded, "project", "teamid"), "teamid is still declared once");
        assertEquals(1, teamForeignKeyCount(upgraded), "the FK is still declared once");
      }
    } finally {
      try (Connection server = connectTo("postgres");
          Statement statement = server.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  private Connection connectTo(String database) throws SQLException {
    String url = jdbcUrl.replaceFirst("/[^/?]*([?].*)?$", "/" + database + "$1");
    return DriverManager.getConnection(url, username, password);
  }

  private static void run(Connection connection, String resource) throws IOException, SQLException {
    try (InputStream input =
        SchemaUpgradeTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Missing " + resource + " on the test classpath");
      }
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      try (Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    return columnCount(connection, table, column) == 1;
  }

  private static int columnCount(Connection connection, String table, String column)
      throws SQLException {
    String sql =
        "select count(*) from information_schema.columns"
            + " where table_schema = 'public' and table_name = ? and column_name = ?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static int teamForeignKeyCount(Connection connection) throws SQLException {
    String sql =
        "select count(*) from pg_constraint c"
            + " join pg_class child on child.oid = c.conrelid"
            + " join pg_class parent on parent.oid = c.confrelid"
            + " where c.contype = 'f' and child.relname = 'project' and parent.relname = 'team'";
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }
}
