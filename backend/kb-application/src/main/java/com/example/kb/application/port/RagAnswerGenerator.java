package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationMessage;

import java.util.List;

public interface RagAnswerGenerator {

    AnswerResult generate(AnswerCommand command);

    AnswerResult generateStream(AnswerCommand command, AnswerDeltaConsumer answerDeltaConsumer);

    interface AnswerDeltaConsumer {

        void onDelta(String delta);
    }

    record AnswerCommand(
            String userQuestion,
            List<ReferenceContext> references,
            List<ConversationMessage> recentMessages
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
