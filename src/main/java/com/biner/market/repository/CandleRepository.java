package com.biner.market.repository;

import com.biner.market.domain.Candle;
import com.biner.market.domain.CandleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Candle 엔티티에 대한 데이터베이스 연산을 처리하는 Spring Data JPA 리포지토리입니다.
 * 복합 키(CandleId)를 사용합니다.
 */
@Repository
public interface CandleRepository extends JpaRepository<Candle, CandleId> {

    /**
     * 특정 심볼과 타임프레임에 대해, 주어진 시간 범위 내의 캔들 목록을 조회합니다.
     * 복합 키 필드를 기반으로 쿼리를 작성해야 하므로 JPQL을 사용합니다.
     *
     * @param symbol    코인 심볼
     * @param timeframe 시간 간격 (예: "1m", "5m")
     * @param start     조회 시작 시간
     * @param end       조회 종료 시간
     * @return 조건에 맞는 캔들 리스트
     */
    @Query("" +
      "SELECT c " +
      "FROM Candle c " +
      "WHERE c.id.symbol = :symbol " +
      "  AND c.id.timeframe = :timeframe " +
      "  AND c.id.openTime BETWEEN :start " +
      "  AND :end " +
      "ORDER BY c.id.openTime ASC")
    List<Candle> findBySymbolAndTimeframeAndOpenTimeBetween(
            @Param("symbol") String symbol,
            @Param("timeframe") String timeframe,
            @Param("start") Instant start,
            @Param("end") Instant end
    );

    /**
     * 특정 심볼과 타임프레임에 대해, 가장 최근의 캔들 N개를 조회합니다.
     *
     * @param symbol    코인 심볼
     * @param timeframe 시간 간격
     * @return 최신 캔들 리스트
     */
    @Query("" +
      "SELECT c " +
      "FROM Candle c " +
      "WHERE c.id.symbol = :symbol " +
      "  AND c.id.timeframe = :timeframe " +
      "ORDER BY c.id.openTime DESC " +
      "LIMIT 1000")
    List<Candle> findTop1000BySymbolAndTimeframeOrderByOpenTimeDesc(
            @Param("symbol") String symbol,
            @Param("timeframe") String timeframe
    );
}
