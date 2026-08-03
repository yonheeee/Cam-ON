package com.camon.domain.course.ws.payload;

import java.time.Instant;

/**
 * 게임 하나가 끝나고 다음 게임이 열리기까지의 인터미션이 시작됐다.
 *
 * <p>다음에 무엇을 하는지는 서버만 안다 — 코스 항목을 순서대로 훑되 지금 인원으로 못 하는
 * 게임은 건너뛰기 때문에, "다음 칸"이 곧 "다음에 열릴 게임"이 아니다. 그래서 프론트가 코스를
 * 보고 추측하지 않고 이 이벤트로 받는다.
 *
 * <p>{@code nextGameDescription}은 MySQL {@code games.description}에서 온다. 게임 설명을
 * 프론트에 하드코딩하지 않기 위한 것으로, 문구를 고칠 때 배포 없이 DB만 바꾸면 된다.
 *
 * @param finishedSessionSeq  방금 끝난 세션 번호. 스킵 요청이 "어느 인터미션인지" 특정하는 데 쓴다.
 * @param nextSessionSeq      다음에 열릴 코스 칸. 다음이 없으면(코스 종료 예정) null.
 * @param nextGameId          다음 게임 id. 코스 종료 예정이면 null.
 * @param nextGameName        다음 게임 이름(games.name). 코스 종료 예정이면 null.
 * @param nextGameDescription 다음 게임 룰 설명(games.description). 코스 종료 예정이면 null.
 * @param nextRoundCount      다음 게임의 라운드 수. 코스 종료 예정이면 null.
 * @param resumesAt           이 시각에 다음 게임이 자동으로 열린다(방장이 먼저 스킵할 수 있다).
 * @param skippable           방장이 기다림을 건너뛸 수 있는가(코스 종료 예정이면 false).
 */
public record CourseIntermissionPayload(
    int finishedSessionSeq,
    Integer nextSessionSeq,
    Long nextGameId,
    String nextGameName,
    String nextGameDescription,
    Integer nextRoundCount,
    Instant resumesAt,
    boolean skippable
) {
}
