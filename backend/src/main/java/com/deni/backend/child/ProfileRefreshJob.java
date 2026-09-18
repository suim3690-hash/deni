package com.deni.backend.child;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "safety.profile-refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
class ProfileRefreshJob {

	private static final Logger log = LoggerFactory.getLogger(ProfileRefreshJob.class);
	private static final int BATCH_SIZE = 100;
	private final ChildRepository childRepository;
	private final ChildService childService;

	ProfileRefreshJob(ChildRepository childRepository, ChildService childService) {
		this.childRepository = childRepository;
		this.childService = childService;
	}

	@Scheduled(cron = "${safety.profile-refresh.cron:0 5 0 * * *}", zone = "Asia/Seoul")
	void refreshProfiles() {
		int checked = 0;
		int changed = 0;
		int failed = 0;
		int page = 0;
		Slice<UUID> ids;
		do {
			ids = childRepository.findIdsForProfileRefresh(PageRequest.of(page++, BATCH_SIZE));
			for (UUID childId : ids) {
				checked++;
				try {
					// 별도 서비스 프록시를 호출해 아이별 커밋·롤백을 분리한다.
					if (childService.refreshSafetyProfile(childId)) changed++;
				}
				catch (OptimisticLockingFailureException exception) {
					failed++;
					log.warn("Profile refresh skipped concurrent update. childId={}", childId);
				}
				catch (RuntimeException exception) {
					failed++;
					log.error("Profile refresh failed. childId={}", childId, exception);
				}
			}
		} while (ids.hasNext());
		log.info("Profile refresh finished. checked={}, changed={}, failed={}", checked, changed, failed);
	}
}
