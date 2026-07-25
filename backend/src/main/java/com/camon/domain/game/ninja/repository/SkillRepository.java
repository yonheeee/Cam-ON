package com.camon.domain.game.ninja.repository;

import com.camon.domain.game.ninja.domain.Skill;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SkillRepository extends JpaRepository<Skill, Long> {

    @Query("select s.id from Skill s")
    List<Long> findAllIds();
}
