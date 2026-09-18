package com.deni.backend.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 같은 저장 키의 요청을 DB 트랜잭션 종료까지 직렬화한다. JVM이 여러 개여도 같은 DB 잠금을 사용한다. */
@Component
public class IdempotencyGuard {

	private final JdbcTemplate jdbcTemplate;

	public IdempotencyGuard(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lock(String scope, String key) {
		long lockId = ByteBuffer.wrap(HexFormat.of().parseHex(fingerprint(scope, key))).getLong();
		jdbcTemplate.queryForObject("SELECT CAST(pg_advisory_xact_lock(?) AS text)", String.class, lockId);
	}

	/** NULL과 빈 문자열, 필드 경계를 구분하는 정규화 입력 지문. 원문을 DB에 중복 저장하지 않는다. */
	public static String fingerprint(String... fields) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String field : fields) {
				byte[] value = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : value.length).array());
				if (value != null) digest.update(value);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
