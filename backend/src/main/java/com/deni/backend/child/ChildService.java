package com.deni.backend.child;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChildService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final ChildRepository childRepository;
	private final ProfileHistoryRepository historyRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final Clock clock;

	@Autowired
	public ChildService(ChildRepository childRepository, ProfileHistoryRepository historyRepository,
			IdempotencyGuard idempotencyGuard) {
		this(childRepository, historyRepository, idempotencyGuard, Clock.system(SERVICE_ZONE));
	}

	ChildService(ChildRepository childRepository, ProfileHistoryRepository historyRepository,
			IdempotencyGuard idempotencyGuard, Clock clock) {
		this.childRepository = childRepository;
		this.historyRepository = historyRepository;
		this.idempotencyGuard = idempotencyGuard;
		this.clock = clock;
	}

	@Transactional
	public ChildResult register(String name, LocalDate birthDate, UUID idempotencyKey) {
		String normalizedName = normalizeName(name);
		OffsetDateTime now = OffsetDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		validateBirthDate(birthDate, today);
		if (idempotencyKey == null) {
			throw ApiException.validation("등록 요청 식별키를 입력해 주세요.",
					Map.of("Idempotency-Key", "UUID 식별키는 필수입니다."));
		}
		idempotencyGuard.lock("child-registration", idempotencyKey.toString());

		Child existing = childRepository.findByRegistrationIdempotencyKey(idempotencyKey).orElse(null);
		if (existing != null) {
			if (!existing.hasRegistrationInput(normalizedName, birthDate)) {
				throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
						"동일한 Idempotency-Key를 다른 등록 정보에 사용할 수 없습니다.");
			}
			refreshProfile(existing, now);
			return toResult(existing, today);
		}

		CalculatedProfile profile = calculateProfile(birthDate, today);
		Child child = new Child(UUID.randomUUID(), normalizedName, birthDate, profile.status(), profile.stage(),
				profile.status() == ProfileStatus.APPLIED ? now : null, idempotencyKey, now);
		child = childRepository.saveAndFlush(child);
		historyRepository.save(new ProfileHistory(child.getId(), null, null, profile.status(), profile.stage(),
				ProfileChangeReason.REGISTERED, now, List.of(), criteria(profile.stage())));
		return toResult(child, today);
	}

	@Transactional
	public ChildResult update(UUID childId, String name, LocalDate birthDate) {
		if (name == null && birthDate == null) {
			throw ApiException.validation("수정할 이름 또는 생년월일을 전달해 주세요.", Map.of());
		}

		Child child = findChild(childId);
		String nextName = name == null ? child.getName() : normalizeName(name);
		LocalDate nextBirthDate = birthDate == null ? child.getBirthDate() : birthDate;
		OffsetDateTime now = OffsetDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		validateBirthDate(nextBirthDate, today);

		CalculatedProfile profile = calculateProfile(nextBirthDate, today);
		ProfileChangeReason reason = child.getBirthDate().equals(nextBirthDate)
				? ProfileChangeReason.AGE_CHANGED : ProfileChangeReason.BIRTH_DATE_UPDATED;
		recordProfileChange(child, profile, reason, now);
		child.update(nextName, nextBirthDate, profile.status(), profile.stage(), now);
		return toResult(child, today);
	}

	/** 기기 연결 시 존재 여부만 확인하며 프로필을 갱신하지 않는다. */
	@Transactional(readOnly = true)
	public void requireRegisteredChild(UUID childId) {
		findChild(childId);
	}

	@Transactional
	public SafetyProfileResult getSafetyProfile(UUID childId) {
		Child child = findChild(childId);
		CalculatedProfile calculated = refreshProfile(child, OffsetDateTime.now(clock));

		return new SafetyProfileResult(child.getId(), calculated.status(), calculated.stage(),
				stageLabel(calculated.stage()), calculated.ageMonths(), criteria(calculated.stage()),
				child.getProfileAppliedAt());
	}

	@Transactional
	public DashboardChildState getDashboardChild(UUID childId) {
		Child child = findChild(childId);
		CalculatedProfile calculated = refreshProfile(child, OffsetDateTime.now(clock));

		return new DashboardChildState(child.getId(), child.getName(), calculated.status(), calculated.stage(),
				calculated.ageMonths());
	}

	/** 스케줄러가 아이 한 명을 독립 트랜잭션으로 갱신한다. 실제 변경 여부를 반환한다. */
	@Transactional
	public boolean refreshSafetyProfile(UUID childId) {
		Child child = findChild(childId);
		ProfileStatus previousStatus = child.getProfileStatus();
		GrowthStage previousStage = child.getStage();
		CalculatedProfile calculated = refreshProfile(child, OffsetDateTime.now(clock));
		return previousStatus != calculated.status() || previousStage != calculated.stage();
	}

	private CalculatedProfile refreshProfile(Child child, OffsetDateTime now) {
		CalculatedProfile calculated = calculateProfile(child.getBirthDate(), now.toLocalDate());
		recordProfileChange(child, calculated, ProfileChangeReason.AGE_CHANGED, now);
		child.applyProfile(calculated.status(), calculated.stage(), now);
		return calculated;
	}

	private void recordProfileChange(Child child, CalculatedProfile next, ProfileChangeReason reason,
			OffsetDateTime now) {
		if (child.getProfileStatus() == next.status() && child.getStage() == next.stage()) return;
		historyRepository.save(new ProfileHistory(child.getId(), child.getProfileStatus(), child.getStage(),
				next.status(), next.stage(), reason, now, previousCriteria(child), criteria(next.stage())));
	}

	private List<SafetyCriterion> previousCriteria(Child child) {
		// 저장 이력 없는 기존 아이의 이전 기준 문구는 알 수 없으므로 추측하지 않는다.
		return historyRepository.findTopByChildIdOrderByChangedAtDescIdDesc(child.getId())
				.filter(history -> history.getToStatus() == child.getProfileStatus()
						&& history.getToStage() == child.getStage())
				.map(ProfileHistory::getToCriteria).orElse(List.of());
	}

	private Child findChild(UUID childId) {
		return childRepository.findById(childId)
				.orElseThrow(() -> ApiException.notFound("아이 정보를 찾을 수 없습니다."));
	}

	private ChildResult toResult(Child child, LocalDate today) {
		CalculatedProfile calculated = calculateProfile(child.getBirthDate(), today);
		return new ChildResult(child.getId(), child.getName(), child.getBirthDate(),
				new ProfileSummary(calculated.status(), calculated.stage(), calculated.ageMonths(),
						child.getProfileAppliedAt()));
	}

	private String normalizeName(String name) {
		String normalized = name == null ? "" : name.trim();
		if (normalized.isEmpty()) {
			throw ApiException.validation("아이 이름을 입력해 주세요.", Map.of("name", "이름은 필수입니다."));
		}
		if (normalized.length() > 50) {
			throw ApiException.validation("아이 이름은 50자 이하로 입력해 주세요.",
					Map.of("name", "이름은 50자 이하여야 합니다."));
		}
		return normalized;
	}

	private void validateBirthDate(LocalDate birthDate, LocalDate today) {
		if (birthDate == null) {
			throw ApiException.validation("생년월일을 입력해 주세요.", Map.of("birthDate", "생년월일은 필수입니다."));
		}
		if (birthDate.isAfter(today)) {
			throw ApiException.validation("미래 날짜는 생년월일로 사용할 수 없습니다.",
					Map.of("birthDate", "미래 날짜는 입력할 수 없습니다."));
		}
	}

	static CalculatedProfile calculateProfile(LocalDate birthDate, LocalDate today) {
		int ageMonths = (today.getYear() - birthDate.getYear()) * 12
				+ today.getMonthValue() - birthDate.getMonthValue();
		if (today.getDayOfMonth() < birthDate.getDayOfMonth()) {
			ageMonths--;
		}

		GrowthStage stage = ageMonths < 12 ? GrowthStage.INFANT
				: ageMonths < 36 ? GrowthStage.TODDLER
				: ageMonths < 96 ? GrowthStage.ACTIVE_CHILD
				: null;
		ProfileStatus status = stage == null ? ProfileStatus.UNSUPPORTED : ProfileStatus.APPLIED;
		return new CalculatedProfile(status, stage, ageMonths);
	}

	private String stageLabel(GrowthStage stage) {
		if (stage == null) {
			return null;
		}
		return switch (stage) {
			case INFANT -> "바닥 탐색 시기";
			case TODDLER -> "걸음마 시기";
			case ACTIVE_CHILD -> "유아 활동기";
		};
	}

	static List<SafetyCriterion> criteria(GrowthStage stage) {
		if (stage == null) {
			return List.of();
		}
		return switch (stage) {
			case INFANT -> List.of(new SafetyCriterion("CHOKING", "바닥 이물질·삼킴 위험 탐지 강화",
					"바닥에 떨어진 작은 물체와 삼킴 위험 물건을 중심으로 집중 모니터링합니다."));
			case TODDLER -> List.of(new SafetyCriterion("MOVEMENT_HAZARD", "모서리·문턱·전선 등 이동 위험 탐지 강화",
					"가구 모서리, 바닥 문턱, 콘센트와 전선 걸림 위험을 집중 모니터링합니다."));
			case ACTIVE_CHILD -> List.of(new SafetyCriterion("WIDE_AREA_HAZARD", "활동 반경에 따른 광범위 위험 탐지",
					"집 전체 활동 반경에서 낙상과 충돌 위험을 폭넓게 모니터링합니다."));
		};
	}

	public record ChildResult(UUID childId, String name, LocalDate birthDate, ProfileSummary safetyProfile) {
	}

	public record ProfileSummary(ProfileStatus status, GrowthStage stage, int ageMonths,
			OffsetDateTime appliedAt) {
	}

	public record SafetyProfileResult(UUID childId, ProfileStatus status, GrowthStage stage, String stageLabel,
			int ageMonths, List<SafetyCriterion> criteria, OffsetDateTime appliedAt) {
	}

	public record SafetyCriterion(String code, String title, String description) {
	}

	public record DashboardChildState(UUID childId, String name, ProfileStatus status, GrowthStage stage,
			int ageMonths) {
	}

	record CalculatedProfile(ProfileStatus status, GrowthStage stage, int ageMonths) {
	}
}
