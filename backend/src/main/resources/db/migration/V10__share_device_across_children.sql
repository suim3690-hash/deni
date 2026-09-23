-- 데모 프로필 여러 개가 로봇 한 대를 함께 쓴다.
-- device_children: 이 기기를 쓸 수 있는 아이 목록.
-- devices.child_id: 지금 이 기기의 탐지를 귀속시킬 아이(활성 프로필). 더 이상 유일한 주인이 아니다.
CREATE TABLE device_children (
    device_id VARCHAR(100) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    child_id UUID NOT NULL REFERENCES children(id) ON DELETE RESTRICT,
    linked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (device_id, child_id)
);
CREATE INDEX ix_device_children_child ON device_children(child_id);
INSERT INTO device_children(device_id, child_id, linked_at) SELECT id, child_id, created_at FROM devices;
-- 활성 프로필은 기기마다 하나지만, 한 아이가 여러 기기의 활성 프로필일 수는 있다.
ALTER TABLE devices DROP CONSTRAINT devices_child_id_key;
