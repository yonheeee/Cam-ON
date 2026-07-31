package com.camon.domain.game.ninja.dto;

import com.camon.domain.game.ninja.domain.Skill;
import java.util.List;

// GET /api/games/{gameId}/ninja/rounds/{round}/skill 응답.
// gestures는 seq 순서대로 완성해야 하는 손동작 콤보 전체 — 시퀀스 진행 추적은 AI/프론트가 하고
// (Spring은 관여하지 않음), 완성되면 프론트가 skillId로 attack API를 한 번만 호출한다.
//
// 예전엔 "다음 교환에 나올 스킬 예고"(nextSkill)와 파티클 파라미터(effect)도 함께 실었는데,
// 둘 다 아무도 읽지 않는 필드가 됐다 — nextSkill을 그리던 화면(NinjaGamePanel)이 전용 전투
// 화면으로 교체됐고, 피격 연출은 스킬별 픽셀 이펙트(프론트 skillEffects의 BY_SKILL_ID)가
// 전담하게 되면서 effect 테이블 기반 원형 버스트 폴백이 없어졌다.
public record RoundSkillResponse(
    int round,
    // 판(round) 안에서 몇 번째 교환의 스킬인지 — 판이 이어지는 동안 교환마다 스킬이 바뀌므로,
    // 프론트는 (round, exchange)가 바뀔 때마다 이 응답을 다시 조회한다.
    int exchange,
    Long skillId,
    String skillName,
    String skillDesc,
    List<GestureStepResponse> gestures
) {
    public static RoundSkillResponse of(int round, int exchange, Skill skill) {
        return new RoundSkillResponse(
            round,
            exchange,
            skill.getId(),
            skill.getName(),
            skill.getSkillDesc(),
            skill.getGestures().stream().map(GestureStepResponse::from).toList()
        );
    }
}
