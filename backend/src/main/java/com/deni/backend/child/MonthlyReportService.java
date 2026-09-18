package com.deni.backend.child;

import com.deni.backend.common.ApiException;
import com.deni.backend.hazard.HazardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MonthlyReportService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private final ChildRepository childRepository;
	private final HazardService hazardService;
	private final ProfileHistoryRepository historyRepository;
	private final Clock clock;

	@Autowired
	public MonthlyReportService(ChildRepository childRepository, HazardService hazardService,
			ProfileHistoryRepository historyRepository) {
		this(childRepository, hazardService, historyRepository, Clock.system(SERVICE_ZONE));
	}

	MonthlyReportService(ChildRepository childRepository, HazardService hazardService,
			ProfileHistoryRepository historyRepository, Clock clock) {
		this.childRepository = childRepository;
		this.hazardService = hazardService;
		this.historyRepository = historyRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public MonthlyReport getMonthlyReport(UUID childId, String month) {
		YearMonth selectedMonth = validateMonth(month);
		Child child = childRepository.findById(childId)
				.orElseThrow(() -> ApiException.notFound("아이 정보를 찾을 수 없습니다."));
		OffsetDateTime start = selectedMonth.atDay(1).atStartOfDay(SERVICE_ZONE).toOffsetDateTime();
		OffsetDateTime end = selectedMonth.plusMonths(1).atDay(1).atStartOfDay(SERVICE_ZONE).toOffsetDateTime();
		List<HazardService.ObjectDetectionCount> detections = hazardService.getMonthlyDetections(childId, start, end);
		long detectionCount = detections.stream().mapToLong(HazardService.ObjectDetectionCount::count).sum();
		LocalDate referenceDate = selectedMonth.equals(YearMonth.now(clock))
				? LocalDate.now(clock) : selectedMonth.atEndOfMonth();

		List<ProfileHistory> changes = historyRepository
				.findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(childId, start, end)
				.stream().filter(history -> history.getReason() != ProfileChangeReason.REGISTERED).toList();
		List<StageChange> stageChanges = changes.stream().map(this::toStageChange).toList();
		List<CriterionChange> criteriaChanges = changes.stream().flatMap(history -> {
			if (history.getToCriteria().isEmpty()) {
				return List.of(new CriterionChange("지원 범위 밖 전환",
						"백엔드 프로필이 미지원 상태로 변경되어 적용 기준이 없습니다.", history.getChangedAt())).stream();
			}
			return history.getToCriteria().stream().map(criterion -> new CriterionChange(
					criterion.title(), criterion.description(), history.getChangedAt()));
		}).toList();

		// 회피율·면적은 미수집. 변경 이력은 실제 저장 시각 기준으로만 제공한다.
		return new MonthlyReport(reportId(childId, selectedMonth), childId, selectedMonth.toString(),
				child.getName(), stageChanges.isEmpty() ? null : stageChanges.getLast(),
				new Summary(detectionCount, null, null), detections, criteriaChanges,
				nextStagePreview(child.getBirthDate(), referenceDate), null, stageChanges);
	}

	private StageChange toStageChange(ProfileHistory history) {
		return new StageChange(history.getFromStage() == null ? null : history.getFromStage().name(),
				history.getToStage() == null ? null : history.getToStage().name(), history.getChangedAt(),
				history.getFromStatus().name(), history.getToStatus().name(), history.getReason().name());
	}

	public ReportSummary getCurrentSummary(UUID childId) {
		YearMonth month = YearMonth.now(clock);
		// available은 발행 완료가 아니라, 빈 월을 포함해 API로 조회 가능하다는 의미다.
		return new ReportSummary(reportId(childId, month), month.toString(), true);
	}

	private YearMonth validateMonth(String month) {
		try {
			if (month == null || !month.matches("[0-9]{4}-[0-9]{2}")) {
				throw new DateTimeParseException("Invalid month format", month == null ? "" : month, 0);
			}
			YearMonth selected = YearMonth.parse(month);
			if (selected.getYear() < 1 || selected.isAfter(YearMonth.now(clock))) {
				throw ApiException.validation("조회 월을 확인해 주세요.",
						Map.of("month", "현재 월 또는 과거 월을 YYYY-MM 형식으로 입력해 주세요."));
			}
			return selected;
		}
		catch (DateTimeParseException exception) {
			throw ApiException.validation("조회 월을 확인해 주세요.",
					Map.of("month", "YYYY-MM 형식의 유효한 월을 입력해 주세요."));
		}
	}

	private String reportId(UUID childId, YearMonth month) {
		return "report_" + childId + "_" + month;
	}

	private NextStagePreview nextStagePreview(LocalDate birthDate, LocalDate referenceDate) {
		if (birthDate.isAfter(referenceDate)) {
			return new NextStagePreview(null, "조회 월은 등록된 생년월일 이전이므로 다음 단계 안내를 제공하지 않습니다.");
		}
		GrowthStage stage = ChildService.calculateProfile(birthDate, referenceDate).stage();
		if (stage == null) {
			return new NextStagePreview(null, "조회 기준일의 월령은 지원 연령 범위 밖입니다.");
		}
		GrowthStage next = switch (stage) {
			case INFANT -> GrowthStage.TODDLER;
			case TODDLER -> GrowthStage.ACTIVE_CHILD;
			case ACTIVE_CHILD -> null;
		};
		return next == null
				? new NextStagePreview(null, "조회 기준일의 월령은 마지막 지원 성장단계입니다. 96개월부터는 지원 범위 밖입니다.")
				: new NextStagePreview(next.name(), ChildService.criteria(next).getFirst().description());
	}

	public record MonthlyReport(String reportId, UUID childId, String month, String childName,
			StageChange stageChange, Summary summary, List<HazardService.ObjectDetectionCount> detectionsByObject,
			List<CriterionChange> criteriaChanges, NextStagePreview nextStagePreview, String feedback,
			List<StageChange> stageChanges) {
	}

	public record StageChange(String from, String to, OffsetDateTime changedAt,
			String fromStatus, String toStatus, String reason) {
	}

	public record Summary(long detectionCount, Double avoidanceRatePercent, Double safeCleanedAreaSquareMeters) {
	}

	public record CriterionChange(String title, String description, OffsetDateTime changedAt) {
	}

	public record NextStagePreview(String stage, String description) {
	}

	public record ReportSummary(String reportId, String month, boolean available) {
	}
}
