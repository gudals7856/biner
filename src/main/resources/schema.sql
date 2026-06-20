-- ==========================================
-- 1. 마켓 데이터 도메인 테이블 (Candles)
-- ==========================================
CREATE TABLE IF NOT EXISTS candles (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    open_time TIMESTAMP WITH TIME ZONE NOT NULL,
    close_time TIMESTAMP WITH TIME ZONE NOT NULL,
    open_price NUMERIC(20, 8) NOT NULL,
    high_price NUMERIC(20, 8) NOT NULL,
    low_price NUMERIC(20, 8) NOT NULL,
    close_price NUMERIC(20, 8) NOT NULL,
    volume NUMERIC(20, 8) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_symbol_open_time ON candles (symbol, open_time);

-- ==========================================
-- 2. 주문 집행 및 계좌 도메인 테이블 (Positions)
-- ==========================================
CREATE TABLE IF NOT EXISTS positions (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,      -- 'LONG', 'SHORT', 'NONE'
    entry_price NUMERIC(20, 8) NOT NULL,
    amount NUMERIC(20, 8) NOT NULL,
    leverage INTEGER NOT NULL,
    dca_count INTEGER NOT NULL DEFAULT 0, -- 물타기 횟수 트래킹 용도
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
