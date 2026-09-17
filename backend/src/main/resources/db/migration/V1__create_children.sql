CREATE TABLE children (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    birth_date DATE NOT NULL,
    profile_status VARCHAR(20) NOT NULL,
    stage VARCHAR(30),
    profile_applied_at TIMESTAMPTZ,
    registration_idempotency_key UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_children_name_not_blank
        CHECK (char_length(btrim(name)) BETWEEN 1 AND 50),
    CONSTRAINT ck_children_profile_status
        CHECK (profile_status IN ('APPLIED', 'UNSUPPORTED')),
    CONSTRAINT ck_children_stage
        CHECK (stage IS NULL OR stage IN ('INFANT', 'TODDLER', 'ACTIVE_CHILD')),
    CONSTRAINT ck_children_profile_consistency
        CHECK (
            (profile_status = 'APPLIED' AND stage IS NOT NULL AND profile_applied_at IS NOT NULL)
            OR
            (profile_status = 'UNSUPPORTED' AND stage IS NULL AND profile_applied_at IS NULL)
        )
);

CREATE INDEX ix_children_created_at ON children (created_at DESC);
