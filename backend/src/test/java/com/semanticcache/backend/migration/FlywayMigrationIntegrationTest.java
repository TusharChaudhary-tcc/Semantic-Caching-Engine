package com.semanticcache.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FlywayMigrationIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway migration V1 executes successfully and reaches SUCCESS state")
    void flywayMigrationExecutesSuccessfully() {
        assertNotNull(flyway, "Flyway bean must be present in Spring context");
        MigrationInfo current = flyway.info().current();
        assertNotNull(current, "Current migration info must not be null");
        assertEquals("1", current.getVersion().getVersion(), "Current migration version must be 1");
        assertEquals("init auth schema", current.getDescription(), "Migration description must match V1 script");
        assertTrue(current.getState().isApplied(), "Migration V1 must be applied successfully");
    }

    @Test
    @DisplayName("Database metadata contains users and refresh_tokens tables with expected columns")
    void databaseMetadataContainsAuthTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            // Verify 'users' table exists
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                List<String> tableNames = new ArrayList<>();
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
                }
                assertTrue(tableNames.contains("USERS"), "Database must contain USERS table. Found: " + tableNames);
                assertTrue(tableNames.contains("REFRESH_TOKENS"), "Database must contain REFRESH_TOKENS table. Found: " + tableNames);
            }

            // Verify columns in 'users'
            try (ResultSet rs = metaData.getColumns(null, null, "USERS", "%")) {
                List<String> columns = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
                assertTrue(columns.contains("ID"), "USERS table must have ID column");
                assertTrue(columns.contains("USERNAME"), "USERS table must have USERNAME column");
                assertTrue(columns.contains("PASSWORD_HASH"), "USERS table must have PASSWORD_HASH column");
                assertTrue(columns.contains("ENABLED"), "USERS table must have ENABLED column");
                assertTrue(columns.contains("CREATED_AT"), "USERS table must have CREATED_AT column");
                assertTrue(columns.contains("UPDATED_AT"), "USERS table must have UPDATED_AT column");
            }

            // Verify columns in 'refresh_tokens'
            try (ResultSet rs = metaData.getColumns(null, null, "REFRESH_TOKENS", "%")) {
                List<String> columns = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
                assertTrue(columns.contains("ID"), "REFRESH_TOKENS table must have ID column");
                assertTrue(columns.contains("USER_ID"), "REFRESH_TOKENS table must have USER_ID column");
                assertTrue(columns.contains("TOKEN_HASH"), "REFRESH_TOKENS table must have TOKEN_HASH column");
                assertTrue(columns.contains("FAMILY_ID"), "REFRESH_TOKENS table must have FAMILY_ID column");
                assertTrue(columns.contains("EXPIRES_AT"), "REFRESH_TOKENS table must have EXPIRES_AT column");
                assertTrue(columns.contains("REVOKED_AT"), "REFRESH_TOKENS table must have REVOKED_AT column");
                assertTrue(columns.contains("CREATED_AT"), "REFRESH_TOKENS table must have CREATED_AT column");
            }
        }
    }

    @Test
    @DisplayName("Users and refresh_tokens tables support CRUD, constraints, and cascade delete")
    void crudAndCascadeDeleteWork() {
        String userId = UUID.randomUUID().toString();
        String username = "testuser_" + System.currentTimeMillis();
        String passwordHash = "$2a$12$e80MvQ0g3E8oYh0.exampleHashValueHere";
        Instant now = Instant.now();

        // Insert User
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, username, passwordHash, true, now, now
        );

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId
        );
        assertEquals(1, userCount);

        // Insert RefreshToken referencing User
        String tokenId = UUID.randomUUID().toString();
        String tokenHash = "hash_" + System.currentTimeMillis();
        String familyId = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(3600);

        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at, revoked_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                tokenId, userId, tokenHash, familyId, expiresAt, null, now
        );

        Integer tokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Integer.class, tokenId
        );
        assertEquals(1, tokenCount);

        // Delete User and verify cascade deletion of RefreshToken
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        Integer postDeleteUserCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId
        );
        assertEquals(0, postDeleteUserCount);

        Integer postDeleteTokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Integer.class, tokenId
        );
        assertEquals(0, postDeleteTokenCount, "Refresh token must be cascade-deleted when parent user is deleted");
    }
}
