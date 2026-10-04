package com.divya.seatReservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL (same major version as docker-compose and production) for integration
 * tests. The races we care about are decided by Postgres row locks, so an in-memory DB
 * would prove nothing. Declared as a bean, so Spring's context cache shares one container
 * across all test classes using this config.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16");
    }
}
