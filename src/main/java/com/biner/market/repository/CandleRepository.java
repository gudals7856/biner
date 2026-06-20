package com.biner.market.repository;

import com.biner.market.domain.Candle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Candle 엔티티에 대한 데이터베이스 연산을 처리하는 Spring Data JPA 리포지토리입니다.
 */
@Repository
public interface CandleRepository extends JpaRepository<Candle, Long> {
    
    /**
     * 특정 심볼(코인)에 대해, 주어진 시작 시간과 종료 시간 사이에 해당하는 캔들 목록을 조회합니다.
     * 결과는 캔들의 시작 시간(openTime)을 기준으로 오름차순(과거 -> 최신) 정렬되어 반환됩니다.
     * 주로 백테스트 기간 동안의 데이터를 가져오거나, 특정 기간의 차트를 그릴 때 사용됩니다.
     *
     * @param symbol 코인 심볼
     * @param start 조회 시작 시간
     * @param end 조회 종료 시간
     * @return 조건에 맞는 캔들 리스트
     */
    List<Candle> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(String symbol, Instant start, Instant end);
    
    /**
     * 특정 심볼(코인)에 대해, 가장 최근에 생성된 캔들 최대 1000개를 조회합니다.
     * 결과는 시작 시간(openTime)을 기준으로 내림차순(최신 -> 과거) 정렬되어 반환됩니다.
     * 실시간 전략 실행 시 이동평균선(MA), RSI 등의 기술적 지표를 계산하기 위한 최신 데이터를 가져올 때 사용됩니다.
     * 1000개로 제한한 이유는 대부분의 보조지표 계산에 충분한 양이며, 메모리와 DB 부하를 방지하기 위함입니다.
     *
     * @param symbol 코인 심볼
     * @return 최신 캔들 리스트 (최대 1000개)
     */
    List<Candle> findTop1000BySymbolOrderByOpenTimeDesc(String symbol);
}
