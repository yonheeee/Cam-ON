package com.camon.dev;

import com.camon.domain.game.ninja.domain.Effect;
import com.camon.domain.game.ninja.domain.Gesture;
import com.camon.domain.game.ninja.domain.Skill;
import com.camon.domain.game.ninja.domain.SkillGesture;
import com.camon.domain.game.ninja.domain.SkillGestureId;
import com.camon.domain.game.ninja.repository.EffectRepository;
import com.camon.domain.game.ninja.repository.GestureRepository;
import com.camon.domain.game.ninja.repository.SkillGestureRepository;
import com.camon.domain.game.ninja.repository.SkillRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 게임 콘텐츠(손동작/이펙트/스킬) 시드 데이터 — 아직 정식 관리 도구/마이그레이션이 없어서
// 앱 시작 시 없는 것만 채워 넣는다. hand-gesture-recognition-mediapipe/app.py의 SKILLS
// 프로토타입 데이터를 그대로 옮김. dev 패키지 밖(RoomRepository/인증)과 달리 이건 "가짜"가
// 아니라 진짜 게임 콘텐츠라, 나중에 정식 시드 스크립트로 옮기는 정도로 대체하면 됨.
//
// 항목 단위로 멱등하다(DevGameCatalogSeeder와 같은 방식). 예전엔 skill 테이블이 비어있을
// 때만 통째로 돌려서, 스킬을 새로 추가해도 이미 데이터가 있는 DB에는 영원히 들어가지 않았다.
@Slf4j
@Component
public class DevNinjaDataSeeder implements ApplicationRunner {

    private final GestureRepository gestureRepository;
    private final EffectRepository effectRepository;
    private final SkillRepository skillRepository;
    private final SkillGestureRepository skillGestureRepository;

    public DevNinjaDataSeeder(
        GestureRepository gestureRepository,
        EffectRepository effectRepository,
        SkillRepository skillRepository,
        SkillGestureRepository skillGestureRepository
    ) {
        this.gestureRepository = gestureRepository;
        this.effectRepository = effectRepository;
        this.skillRepository = skillRepository;
        this.skillGestureRepository = skillGestureRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Gesture> gestures = seedGestures();
        Effect effect = seedEffect();

        seedSkill(gestures, effect, "뇌절", 20, "Horse", "snake");
        seedSkill(gestures, effect, "봉선화의 술", 30, "snake", "mouse", "cow");
        seedSkill(gestures, effect, "수룡탄의 술", 30, "sailor_moon", "cow", "rabbit");
        seedSkill(gestures, effect, "냥냥펀치", 15, "rabbit", "cat");
        seedSkill(gestures, effect, "바람의 상처", 45, "spider", "Horse", "mouse", "sailor_moon");
        // 최고난도 콤보(6단, 안 겹치는 손동작 6종) + 한 방 탈락(데미지 = INITIAL_HP).
        // 30초 교환 안에 6단을 다 잡으려면 손동작 하나당 유지(0.7초)와 전환을 거의 실수 없이
        // 붙여야 해서, 성공하면 판이 즉시 끝나는 보상이 성립한다.
        //
        // 주의: 예전엔 콤보가 2개(말→쥐)뿐이라 "판을 빨리 끝내는 테스트용"으로 쓰였다. 6단이 된
        // 지금은 그 용도로 쓰기 어렵다 — 진행 확인이 급하면 아마테라스 대신 냥냥펀치(2단)의
        // damage를 임시로 올리는 편이 낫다.
        seedSkill(gestures, effect, "아마테라스", 100,
            "spider", "sailor_moon", "cow", "rabbit", "Horse", "snake");
        // 콤보 4개짜리 고데미지 — 바람의 상처(45)와 같은 길이지만 손동작 구성이 겹치지 않게 잡았다.
        seedSkill(gestures, effect, "나선환", 50, "cat", "mouse", "rabbit", "snake");
    }

    // 손동작은 name이 곧 분류기 라벨과의 문자열 계약이라 그 값으로 존재 여부를 본다.
    //
    // girl_V(브이)는 분류기는 인식하지만 어떤 스킬 콤보에도 넣지 않는다 — 손모양 이미지가 없어서
    // (프론트 gestureImages.FILE_BY_GESTURE에 항목 없음) 인술 카드에 그림 대신 한글 글자만 뜬다.
    // 나머지 8종과 섞이면 그 칸만 튀어서 따라하기 어렵다. 이미지가 생기면 콤보에 넣어도 된다.
    private Map<String, Gesture> seedGestures() {
        Map<String, Gesture> existing = gestureRepository.findAll().stream()
            .collect(Collectors.toMap(Gesture::getName, g -> g));
        Stream.of(
            gesture("snake", "뱀"), gesture("girl_V", "브이"), gesture("mouse", "쥐"),
            gesture("Horse", "말"), gesture("cow", "소"), gesture("rabbit", "토끼"),
            gesture("cat", "고양이"), gesture("spider", "거미"), gesture("sailor_moon", "세일러문")
        ).filter(g -> !existing.containsKey(g.getName()))
            .forEach(g -> {
                log.info("[Seed] gesture : '{}' 없음 → 추가", g.getName());
                existing.put(g.getName(), gestureRepository.save(g));
            });
        return existing;
    }

    // 지금은 이펙트가 한 종류뿐이라(스킬별 연출은 프론트의 Pixel*Effect가 담당) 첫 행을 재사용한다.
    private Effect seedEffect() {
        return effectRepository.findAll().stream().findFirst().orElseGet(() ->
            effectRepository.save(Effect.builder()
                .name("골드 버스트").color("#ffd700")
                .particleCount(28).life(20).radiusMin(2).radiusMax(5).speedMin(1).speedMax(3)
                .build())
        );
    }

    // 스킬 단위로 멱등하다 — 예전엔 skill 테이블이 비어있을 때만 통째로 돌려서, 스킬을 새로
    // 추가해도 이미 데이터가 있는 DB(로컬/배포)엔 영원히 들어가지 않았다.
    //
    // 이미 있는 스킬은 damage를 덮어쓰지 않는다(운영에서 밸런스를 조정해 뒀을 수 있다). 반면
    // 콤보는 여기 선언과 다르면 맞춘다 — 콤보는 "무슨 손동작을 해야 하는가"라는 게임 콘텐츠라
    // 코드와 DB가 갈라지면 아무도 모르게 다른 게임이 되고, 마이그레이션 도구가 없는 지금은
    // 코드를 고쳐도 반영될 경로가 없다.
    private void seedSkill(Map<String, Gesture> gestures, Effect effect, String name, int damage, String... sequence) {
        Skill existing = skillRepository.findByName(name).orElse(null);
        if (existing != null) {
            reconcileCombo(existing, gestures, sequence);
            return;
        }
        log.info("[Seed] skill : '{}' 없음 → 추가 (damage={}, 콤보 {}단)", name, damage, sequence.length);
        Skill skill = skillRepository.save(Skill.builder().name(name).damage(damage).effect(effect).build());
        saveCombo(skill, gestures, sequence);
    }

    private void reconcileCombo(Skill skill, Map<String, Gesture> gestures, String[] sequence) {
        List<String> current = skill.getGestures().stream()
            .map(skillGesture -> skillGesture.getGesture().getName())
            .toList();
        if (current.equals(List.of(sequence))) {
            return;
        }
        log.info("[Seed] skill : '{}' 콤보가 선언과 다름 {} → {} — 맞춤",
            skill.getName(), current, List.of(sequence));
        skillGestureRepository.deleteAll(skill.getGestures());
        // 지운 행이 flush되기 전에 같은 (skill_id, seq) PK로 insert하면 충돌한다.
        skillGestureRepository.flush();
        skill.getGestures().clear();
        saveCombo(skill, gestures, sequence);
    }

    private void saveCombo(Skill skill, Map<String, Gesture> gestures, String[] sequence) {
        int seq = 1;
        for (String gestureName : sequence) {
            skillGestureRepository.save(SkillGesture.builder()
                .id(new SkillGestureId(skill.getId(), seq++))
                .skill(skill)
                .gesture(gestures.get(gestureName))
                .build());
        }
    }

    private static Gesture gesture(String name, String labelKr) {
        return Gesture.builder().name(name).labelKr(labelKr).build();
    }
}
