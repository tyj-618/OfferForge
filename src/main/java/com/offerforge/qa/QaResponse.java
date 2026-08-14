package com.offerforge.qa;

import java.util.List;

public record QaResponse(String question, String answer, List<Long> referencedKnowledgeIds, String requestId) {
}
