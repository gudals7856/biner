package com.biner.market.repository;

import com.biner.market.domain.Candle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CandleRepository extends JpaRepository<Candle, Long> {
    List<Candle> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(String symbol, Instant start, Instant end);
    List<Candle> findTop1000BySymbolOrderByOpenTimeDesc(String symbol);
}
