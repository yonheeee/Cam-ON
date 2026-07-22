package com.plaiground.domain.game.ninja.repository;

import com.plaiground.domain.game.ninja.domain.SkillGesture;
import com.plaiground.domain.game.ninja.domain.SkillGestureId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillGestureRepository extends JpaRepository<SkillGesture, SkillGestureId> {
}
