package com.example.kb.application.port;

import com.example.kb.domain.model.ChunkEnrichmentQuestion;

import java.util.List;

public interface ChunkEnrichmentGenerator {

    GenerateResult generate(GenerateCommand command);

    record GenerateCommand(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            String filename,
            String titlePath,
            String chunkContent
    ) {
    }

    record GenerateResult(
            String summary,
            List<ChunkEnrichmentQuestion> questions,
            String llmProvider,
            String llmModel,
            String promptVersion
    ) {
    }
}
