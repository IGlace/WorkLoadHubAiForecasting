package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DialectTest {

    @Test
    void sqliteStoresBooleansAsIntegers() {
        assertEquals(1, Dialect.SQLITE.bool(true));
        assertEquals(0, Dialect.SQLITE.bool(false));
        assertTrue(Dialect.SQLITE.asBoolean(1));
        assertFalse(Dialect.SQLITE.asBoolean(0L));
    }

    @Test
    void postgresqlKeepsBooleans() {
        assertEquals(Boolean.TRUE, Dialect.POSTGRESQL.bool(true));
        assertTrue(Dialect.POSTGRESQL.asBoolean(Boolean.TRUE));
    }

    @Test
    void postgresqlCastsTypedPlaceholders() {
        assertEquals("CAST(? AS uuid)", Dialect.POSTGRESQL.placeholder("uuid"));
        assertEquals("CAST(? AS timestamp)", Dialect.POSTGRESQL.placeholder("timestamp"));
        assertEquals("CAST(? AS date)", Dialect.POSTGRESQL.placeholder("date"));
        assertEquals("CAST(? AS time)", Dialect.POSTGRESQL.placeholder("time"));
        assertEquals("?", Dialect.POSTGRESQL.placeholder("varchar"));
        assertEquals("?", Dialect.SQLITE.placeholder("TEXT"));
    }

    @Test
    void detectsSqlite() {
        assertEquals(Dialect.SQLITE, Dialect.of(DatabaseTestSupport.sqliteInMemory()));
    }
}
