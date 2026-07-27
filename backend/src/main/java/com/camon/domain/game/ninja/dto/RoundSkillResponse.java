package com.camon.domain.game.ninja.dto;

import com.camon.domain.game.ninja.domain.Skill;
import java.util.List;

// GET /api/games/{gameId}/ninja/rounds/{round}/skill 응답.
// gestures는 seq 순서대로 완성해야 하는 손동작 콤보 전체 — 시퀀스 진행 추적은 AI/프론트가 하고
// (Spring은 관여하지 않음), 완성되면 프론트가 skillId로 attack API를 한 번만 호출한다.
public record RoundSkillResponse(
    int round,
    // 판(round) 안에서 몇 번째 교환의 스킬인지 — 판이 이어지는 동안 교환마다 스킬이 바뀌므로,
    // 프론트는 (round, exchange)가 바뀔 때마다 이 응답을 다시 조회한다.
    int exchange,
    Long skillId,
    String skillName,
    String skillDesc,
    List<GestureStepResponse> gestures,
    EffectResponse effect,
    // 바로 다음 교환에 나올 스킬 예고 — 없으면 null.
    NextSkillPreview nextSkill
) {
    public static RoundSkillResponse of(int round, int exchange, Skill skill, NextSkillPreview nextSkill) {
        return new RoundSkillResponse(
            round,
            exchange,
            skill.getId(),
            skill.getName(),
            skill.getSkillDesc(),
            skill.getGestures().stream().map(GestureStepResponse::from).toList(),
            EffectResponse.from(skill.getEffect()),
            nextSkill
        );
    }
}
