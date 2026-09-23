-- V10이 이 제약을 지웠지만 되돌린다. 한 아이는 device_children에서 기기 하나에만 연결되므로
-- 그 아이가 두 기기의 활성 프로필이 될 수는 없다. 서비스 로직을 우회해도 DB가 막도록 남겨 둔다.
ALTER TABLE devices ADD CONSTRAINT devices_child_id_key UNIQUE (child_id);
