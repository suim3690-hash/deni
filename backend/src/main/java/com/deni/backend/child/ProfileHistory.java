package com.deni.backend.child;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 백엔드 프로필 등록·변경 기록. 실제 기기에 전달·적용되었다는 의미는 아니다. */
@Entity
@Table(name = "profile_history")
class ProfileHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "child_id", nullable = false, updatable = false)
	private UUID childId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 20, updatable = false)
	private ProfileStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_stage", length = 30, updatable = false)
	private GrowthStage fromStage;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 20, updatable = false)
	private ProfileStatus toStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_stage", length = 30, updatable = false)
	private GrowthStage toStage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30, updatable = false)
	private ProfileChangeReason reason;

	@Column(name = "changed_at", nullable = false, updatable = false)
	private OffsetDateTime changedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "from_criteria", nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<ChildService.SafetyCriterion> fromCriteria;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "to_criteria", nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<ChildService.SafetyCriterion> toCriteria;

	protected ProfileHistory() {
	}

	ProfileHistory(UUID childId, ProfileStatus fromStatus, GrowthStage fromStage, ProfileStatus toStatus,
			GrowthStage toStage, ProfileChangeReason reason, OffsetDateTime changedAt,
			List<ChildService.SafetyCriterion> fromCriteria, List<ChildService.SafetyCriterion> toCriteria) {
		this.childId = childId;
		this.fromStatus = fromStatus;
		this.fromStage = fromStage;
		this.toStatus = toStatus;
		this.toStage = toStage;
		this.reason = reason;
		this.changedAt = changedAt;
		this.fromCriteria = List.copyOf(fromCriteria);
		this.toCriteria = List.copyOf(toCriteria);
	}

	UUID getChildId() { return childId; }
	ProfileStatus getFromStatus() { return fromStatus; }
	GrowthStage getFromStage() { return fromStage; }
	ProfileStatus getToStatus() { return toStatus; }
	GrowthStage getToStage() { return toStage; }
	ProfileChangeReason getReason() { return reason; }
	OffsetDateTime getChangedAt() { return changedAt; }
	List<ChildService.SafetyCriterion> getFromCriteria() { return List.copyOf(fromCriteria); }
	List<ChildService.SafetyCriterion> getToCriteria() { return List.copyOf(toCriteria); }
}

enum ProfileChangeReason {
	REGISTERED,
	AGE_CHANGED,
	BIRTH_DATE_UPDATED
}
