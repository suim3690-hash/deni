-- 미래 시각이 들어간 위험 기록은 생활공간 위험의 30초 재감지 규칙을 그 시각이 지날 때까지 무력화한다.
-- 억제 조회가 updated_at >= now() - 30초를 쓰므로, 미래 행이 항상 조건을 만족해 새 활성 위험이 생기지 않는다.
-- 기존 행을 현재 시각 이전으로 당기고, 이후 입력도 같은 기준으로 맞춘다. 날짜는 유지되므로 월간 집계는 바뀌지 않는다.
UPDATE hazards
SET detected_at = LEAST(detected_at, clock_timestamp() - INTERVAL '1 minute'),
    updated_at = LEAST(updated_at, clock_timestamp() - INTERVAL '1 minute'),
    acknowledged_at = LEAST(acknowledged_at, clock_timestamp() - INTERVAL '1 minute')
WHERE detected_at > clock_timestamp()
   OR updated_at > clock_timestamp()
   OR acknowledged_at > clock_timestamp();

-- 거부하지 않고 맞추는 이유: 기기·서버 시계가 조금 앞설 수 있으며, 그때 탐지 저장이 실패하면 안 된다.
CREATE FUNCTION clamp_hazard_times() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.detected_at > clock_timestamp() THEN NEW.detected_at := clock_timestamp(); END IF;
    IF NEW.updated_at > clock_timestamp() THEN NEW.updated_at := clock_timestamp(); END IF;
    IF NEW.acknowledged_at > clock_timestamp() THEN NEW.acknowledged_at := clock_timestamp(); END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_clamp_hazard_times BEFORE INSERT OR UPDATE ON hazards
    FOR EACH ROW EXECUTE FUNCTION clamp_hazard_times();
