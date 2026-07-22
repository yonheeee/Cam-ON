package com.plaiground.domain.game.ninja.repository;

import com.plaiground.domain.game.ninja.domain.Gesture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GestureRepository extends JpaRepository<Gesture, Long> {
}
