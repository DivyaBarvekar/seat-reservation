package com.divya.seatReservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)  // skipped, not failed, on machines without Docker
class SeatReservationApplicationTests {

	@Test
	void contextLoads() {
	}

}
