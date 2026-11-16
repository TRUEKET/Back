package com.trueket.backend.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real PostgreSQL for native SQL tests. No external credentials or shared database. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgreSqlIntegrationSupport {
    private static final EmbeddedPostgres DATABASE = start();

    private static EmbeddedPostgres start() {
        try {
            var database = EmbeddedPostgres.builder().setPort(0).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { database.close(); } catch (java.io.IOException ignored) { }
            }, "test-postgres-shutdown"));
            return database;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("일회용 PostgreSQL 시작 실패: 테스트 로그의 initdb 오류를 확인하세요", e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
