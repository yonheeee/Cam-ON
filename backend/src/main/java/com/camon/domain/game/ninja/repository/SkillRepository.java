package com.camon.domain.game.ninja.repository;

import com.camon.domain.game.ninja.domain.Skill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SkillRepository extends JpaRepository<Skill, Long> {

    @Query("select s.id from Skill s")
    List<Long> findAllIds();

    /** 시드가 스킬 단위로 멱등하게 동작하려면 이름으로 존재 여부를 볼 수 있어야 한다. */
    Optional<Skill> findByName(String name);
}
