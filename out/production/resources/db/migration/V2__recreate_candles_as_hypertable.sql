-- 기존 테이블이 존재할 경우 안전하게 삭제합니다.
DROP TABLE IF EXISTS public.candles;

-- 1. 새로운 구조로 테이블을 생성합니다.
CREATE TABLE public.candles (
    open_time timestamptz NOT NULL,
    symbol varchar(20) NOT NULL,
    timeframe varchar(10) NOT NULL, -- 타임프레임 컬럼 추가
    close_time timestamptz NOT NULL,
    open_price numeric(20, 8) NOT NULL,
    high_price numeric(20, 8) NOT NULL,
    low_price numeric(20, 8) NOT NULL,
    close_price numeric(20, 8) NOT NULL,
    volume numeric(20, 8) NOT NULL,

    -- open_time, symbol, timeframe을 복합 기본 키로 설정합니다.
    CONSTRAINT candles_pkey PRIMARY KEY (open_time, symbol, timeframe)
);

-- 2. TimescaleDB 하이퍼테이블로 변환합니다.
-- 'open_time' 컬럼을 기준으로 자동 파티셔닝(분할)을 설정합니다.
SELECT create_hypertable('public.candles', 'open_time');

-- 3. (선택 사항) 데이터 조회 성능 향상을 위해 인덱스를 추가할 수 있습니다.
-- 예: 특정 심볼과 타임프레임으로 데이터를 자주 조회하는 경우
CREATE INDEX IF NOT EXISTS idx_symbol_timeframe_open_time ON public.candles (symbol, timeframe, open_time DESC);
