package com.example.kb.application.port;

import java.util.List;

public interface RagAnswerGenerator {

    AnswerResult generate(AnswerCommand command);

    record AnswerCommand(
            String userQuestion,
            List<ReferenceContext> references
    ) {
    }

    record ReferenceContext(
            Integer referenceNo,
            String fileName,
            String titlePath,
            Integer chunkIndex,
            String content
    ) {
    }

    record AnswerResult(
            String content,
            String provider,
            String model
    ) {
    }
}
