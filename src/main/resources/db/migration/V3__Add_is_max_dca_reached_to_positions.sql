ALTER TABLE positions
ADD COLUMN is_max_dca_reached BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN positions.is_max_dca_reached IS '최대 물타기 횟수에 도달했는지 여부를 나타내는 플래그';