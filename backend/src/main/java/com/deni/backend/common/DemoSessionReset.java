package com.deni.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ends the previous demo session before scheduled command delivery starts. */
@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "deni.demo.reset-on-startup", havingValue = "true")
public class DemoSessionReset implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(DemoSessionReset.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DemoSessionReset(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            int commands = jdbc.update("""
                    UPDATE device_command_delivery
                    SET status='EXPIRED', completed_at=clock_timestamp(), error_code='DEMO_SESSION_RESTARTED'
                    WHERE status IN ('QUEUED','SENT','DELIVERED','UNKNOWN')
                    """);
            int hazards = jdbc.update("""
                    UPDATE hazards SET status='RESOLVED', updated_at=clock_timestamp(), version=version+1
                    WHERE status='ACTIVE'
                    """);
            LOG.info("Demo session reset: {} hazards resolved, {} pending commands expired", hazards, commands);
        });
    }
}
