package com.deni.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class DemoSessionResetTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);

    @Test
    void expiresOldCommandsAndResolvesHazardsInOneTransaction() {
        when(manager.getTransaction(any())).thenReturn(status);
        new DemoSessionReset(jdbc, manager).afterPropertiesSet();
        var order = inOrder(jdbc, manager);
        order.verify(manager).getTransaction(any());
        order.verify(jdbc).update(contains("SET status='EXPIRED'"));
        order.verify(jdbc).update(contains("UPDATE hazards SET status='RESOLVED'"));
        order.verify(manager).commit(status);
        verify(manager, never()).rollback(any());
    }

    @Test
    void failedResetRollsBackAndPreventsStartup() {
        when(manager.getTransaction(any())).thenReturn(status);
        doThrow(new IllegalStateException("database unavailable"))
                .when(jdbc).update(contains("UPDATE hazards"));
        assertThrows(IllegalStateException.class,
                () -> new DemoSessionReset(jdbc, manager).afterPropertiesSet());
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
