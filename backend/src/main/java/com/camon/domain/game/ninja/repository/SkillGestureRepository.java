package com.camon.domain.game.ninja.repository;

import com.camon.domain.game.ninja.domain.SkillGesture;
import com.camon.domain.game.ninja.domain.SkillGestureId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillGestureRepository extends JpaRepository<SkillGesture, SkillGestureId> {
}
