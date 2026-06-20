package com.biner.order.repository;

import com.biner.order.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {
    Optional<Position> findBySymbolAndSideNot(String symbol, Position.PositionSide side);
}
