package com.deni.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdempotencyGuardTests {

	@Test
	void fingerprintIsStableAndSeparatesNullEmptyAndFieldBoundaries() {
		assertEquals(IdempotencyGuard.fingerprint("김튼튼", "2025-09-18"),
				IdempotencyGuard.fingerprint("김튼튼", "2025-09-18"));
		assertTrue(IdempotencyGuard.fingerprint("김튼튼").matches("[0-9a-f]{64}"));
		assertNotEquals(IdempotencyGuard.fingerprint((String) null), IdempotencyGuard.fingerprint(""));
		assertNotEquals(IdempotencyGuard.fingerprint("ab", "c"), IdempotencyGuard.fingerprint("a", "bc"));
		assertNotEquals(IdempotencyGuard.fingerprint("child-registration", "key"),
				IdempotencyGuard.fingerprint("hazard-detection", "key"));
	}

	@Test
	void locksDerivedKeyWithBoundSqlParameter() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		new IdempotencyGuard(jdbc).lock("scope", "key");
		long expected = ByteBuffer.wrap(HexFormat.of().parseHex(IdempotencyGuard.fingerprint("scope", "key"))).getLong();
		verify(jdbc).queryForObject("SELECT CAST(pg_advisory_xact_lock(?) AS text)", String.class, expected);
	}
}
