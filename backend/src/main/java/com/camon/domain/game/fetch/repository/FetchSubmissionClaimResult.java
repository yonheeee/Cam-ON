package com.camon.domain.game.fetch.repository;

public record FetchSubmissionClaimResult(
    FetchSubmissionStatus status,
    int rank,
    int submissionCount,
    int participantCount,
    long deadlineAt
) {

    public boolean allParticipantsSubmitted() {
        return status == FetchSubmissionStatus.SUCCESS
            && submissionCount == participantCount;
    }
}
