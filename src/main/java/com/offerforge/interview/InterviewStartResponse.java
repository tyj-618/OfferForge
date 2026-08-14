package com.offerforge.interview;

public record InterviewStartResponse(
        String sessionId,
        String openingMessage,
        InterviewStatusResponse status
) {
}
