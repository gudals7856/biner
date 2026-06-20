package com.biner.order.repository;

import com.biner.order.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Position 엔티티에 대한 데이터베이스 연산을 처리하는 Spring Data JPA 리포지토리입니다.
 * JpaRepository를 상속받아 기본적인 CRUD(Create, Read, Update, Delete) 기능을 자동으로 제공받습니다.
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, Long> { // Position 엔티티를 다루고, 기본 키(PK)의 타입은 Long입니다.
    
    /**
     * 코인 심볼(symbol)과 포지션 상태(status)를 기준으로 포지션 정보를 조회합니다.
     * Spring Data JPA의 쿼리 메서드 기능을 이용하여, 메서드 이름만으로 자동으로 SQL 쿼리를 생성합니다.
     * "findBy + {필드이름} + And + {필드이름}" 형식입니다.
     * 
     * @param symbol 조회할 코인의 심볼 (예: "BTCUSDT")
     * @param status 조회할 포지션의 상태 (예: Position.PositionStatus.OPEN)
     * @return 조회된 포지션 객체를 Optional로 감싸서 반환합니다. 결과가 없을 수도 있으므로 Null-safe한 처리를 위해 Optional을 사용합니다.
     */
    Optional<Position> findBySymbolAndStatus(String symbol, Position.PositionStatus status);
}
