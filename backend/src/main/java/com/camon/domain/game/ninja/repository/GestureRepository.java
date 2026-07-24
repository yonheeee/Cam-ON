package com.camon.domain.game.ninja.repository;

import com.camon.domain.game.ninja.domain.Gesture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GestureRepository extends JpaRepository<Gesture, Long> {
}
