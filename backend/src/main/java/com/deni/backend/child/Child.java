package com.deni.backend.child;

import com.deni.backend.common.IdempotencyGuard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "children")
class Child {

	@Id
	private UUID id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "birth_date", nullable = false)
	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "profile_status", nullable = false, length = 20)
	private ProfileStatus profileStatus;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private GrowthStage stage;

	@Column(name = "profile_applied_at")
	private OffsetDateTime profileAppliedAt;

	@Column(name = "registration_idempotency_key", nullable = false, unique = true)
	private UUID registrationIdempotencyKey;

	@Column(name = "registration_input_hash", length = 64, updatable = false)
	private String registrationInputHash;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Version
	@Column(nullable = false)
	private long version;

	protected Child() {
	}

	Child(UUID id, String name, LocalDate birthDate, ProfileStatus profileStatus, GrowthStage stage,
			OffsetDateTime profileAppliedAt, UUID registrationIdempotencyKey, OffsetDateTime now) {
		this.id = id;
		this.name = name;
		this.birthDate = birthDate;
		this.profileStatus = profileStatus;
		this.stage = stage;
		this.profileAppliedAt = profileAppliedAt;
		this.registrationIdempotencyKey = registrationIdempotencyKey;
		this.registrationInputHash = IdempotencyGuard.fingerprint(name, birthDate.toString());
		this.createdAt = now;
		this.updatedAt = now;
	}

	void update(String name, LocalDate birthDate, ProfileStatus nextStatus, GrowthStage nextStage,
			OffsetDateTime now) {
		this.name = name;
		this.birthDate = birthDate;
		applyProfile(nextStatus, nextStage, now);
		this.updatedAt = now;
	}

	void applyProfile(ProfileStatus nextStatus, GrowthStage nextStage, OffsetDateTime now) {
		if (profileStatus == nextStatus && stage == nextStage) {
			return;
		}
		this.profileStatus = nextStatus;
		this.stage = nextStage;
		this.profileAppliedAt = nextStatus == ProfileStatus.APPLIED ? now : null;
		this.updatedAt = now;
	}

	boolean hasRegistrationInput(String name, LocalDate birthDate) {
		if (registrationInputHash != null) {
			return registrationInputHash.equals(IdempotencyGuard.fingerprint(name, birthDate.toString()));
		}
		// V4 이전 원본 입력은 미수집. 기존 아이만 현재 저장 값과 비교한다.
		return this.name.equals(name) && this.birthDate.equals(birthDate);
	}

	UUID getId() {
		return id;
	}

	String getName() {
		return name;
	}

	LocalDate getBirthDate() {
		return birthDate;
	}

	ProfileStatus getProfileStatus() {
		return profileStatus;
	}

	GrowthStage getStage() {
		return stage;
	}

	OffsetDateTime getProfileAppliedAt() {
		return profileAppliedAt;
	}
}

enum ProfileStatus {
	APPLIED,
	UNSUPPORTED
}

enum GrowthStage {
	INFANT,
	TODDLER,
	ACTIVE_CHILD
}
