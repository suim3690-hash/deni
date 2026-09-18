package com.deni.backend.child;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileRefreshJobTests {

	private final ChildRepository repository = mock(ChildRepository.class);
	private final ChildService service = mock(ChildService.class);
	private final ProfileRefreshJob job = new ProfileRefreshJob(repository, service);

	@Test
	void processesEveryPageWithoutLoadingAllChildEntities() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		when(repository.findIdsForProfileRefresh(PageRequest.of(0, 100))).thenReturn(
				new SliceImpl<>(List.of(first), PageRequest.of(0, 100), true));
		when(repository.findIdsForProfileRefresh(PageRequest.of(1, 100))).thenReturn(
				new SliceImpl<>(List.of(second), PageRequest.of(1, 100), false));
		when(service.refreshSafetyProfile(first)).thenReturn(true);

		job.refreshProfiles();

		verify(service).refreshSafetyProfile(first);
		verify(service).refreshSafetyProfile(second);
		verify(repository, times(2)).findIdsForProfileRefresh(any());
	}

	@Test
	void failureAndConcurrentUpdateDoNotPreventOtherChildrenFromRefreshing() {
		UUID conflicting = UUID.randomUUID();
		UUID failing = UUID.randomUUID();
		UUID successful = UUID.randomUUID();
		when(repository.findIdsForProfileRefresh(PageRequest.of(0, 100))).thenReturn(new SliceImpl<>(
				List.of(conflicting, failing, successful), PageRequest.of(0, 100), false));
		when(service.refreshSafetyProfile(conflicting)).thenThrow(new OptimisticLockingFailureException("conflict"));
		when(service.refreshSafetyProfile(failing)).thenThrow(new IllegalStateException("test failure"));
		when(service.refreshSafetyProfile(successful)).thenReturn(true);

		assertDoesNotThrow(job::refreshProfiles);
		verify(service).refreshSafetyProfile(successful);
	}

	@Test
	void emptyDatabaseDoesNotInvokeRefreshService() {
		when(repository.findIdsForProfileRefresh(PageRequest.of(0, 100))).thenReturn(new SliceImpl<>(List.of()));
		job.refreshProfiles();
		verifyNoInteractions(service);
	}

	@Test
	void enabledByDefaultRegistersDailyCronTask() {
		new ApplicationContextRunner().withUserConfiguration(SchedulingTestConfiguration.class).run(context -> {
			assertNull(context.getStartupFailure());
			assertNotNull(context.getBean(ProfileRefreshJob.class));
			var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
			assertEquals(1, tasks.size());
			assertEquals("0 5 0 * * *", ((CronTask) tasks.iterator().next().getTask()).getExpression());
			assertEquals("Asia/Seoul", ProfileRefreshJob.class.getDeclaredMethod("refreshProfiles")
					.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).zone());
		});
	}

	@Test
	void disablingJobPreventsTaskRegistration() {
		new ApplicationContextRunner().withUserConfiguration(SchedulingTestConfiguration.class)
				.withPropertyValues("safety.profile-refresh.enabled=false").run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.getBeansOfType(ProfileRefreshJob.class).isEmpty());
					assertTrue(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
				});
	}

	@Test
	void configuredCronReplacesDefaultSchedule() {
		new ApplicationContextRunner().withUserConfiguration(SchedulingTestConfiguration.class)
				.withPropertyValues("safety.profile-refresh.cron=0 0 1 1 1 *").run(context -> {
					assertNull(context.getStartupFailure());
					var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
					assertEquals("0 0 1 1 1 *", ((CronTask) tasks.iterator().next().getTask()).getExpression());
				});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableScheduling
	@Import(ProfileRefreshJob.class)
	static class SchedulingTestConfiguration {
		@Bean ChildRepository childRepository() { return mock(ChildRepository.class); }
		@Bean ChildService childService() { return mock(ChildService.class); }
	}
}
