package com.offerforge.interview;

import java.util.List;

public record InterviewEndResponse(
        String sessionId,
        int askedCount,
        double averageScore,
        List<QuestionRecord> questions
) {
}
