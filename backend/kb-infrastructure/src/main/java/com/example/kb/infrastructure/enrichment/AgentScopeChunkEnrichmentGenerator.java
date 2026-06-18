package com.example.kb.infrastructure.enrichment;

import com.example.kb.application.port.ChunkEnrichmentGenerator;
import com.example.kb.domain.model.ChunkEnrichmentQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class AgentScopeChunkEnrichmentGenerator implements ChunkEnrichmentGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChunkEnrichmentGenerator.class);

    private final ChunkEnrichmentProperties properties;
    private final ChunkEnrichmentPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final OpenAIChatModel chatModel;

    public AgentScopeChunkEnrichmentGenerator(
            ChunkEnrichmentProperties properties,
            ChunkEnrichmentPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.chatModel = OpenAIChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.model())
                .stream(false)
                .build();
    }

    @Override
    public GenerateResult generate(GenerateCommand command) {
        log.info("AgentScope enrichment 生成入参: knowledgeBaseId={}, fileId={}, chunkId={}, provider={}, model={}",
                command.knowledgeBaseId(), command.fileId(), command.chunkId(), properties.provider(), properties.model());
        try {
            String prompt = promptBuilder.build(command, properties);
            Msg message = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(prompt)
                    .build();
            GenerateOptions generateOptions = GenerateOptions.builder()
                    .apiKey(properties.apiKey())
                    .baseUrl(properties.baseUrl())
                    .modelName(properties.model())
                    .stream(Boolean.FALSE)
                    .temperature(0.2D)
                    .build();
            Flux<ChatResponse> responseFlux = chatModel.stream(List.of(message), List.of(), generateOptions);
            List<ChatResponse> responses = responseFlux.collectList().block();
            String responseText = extractText(responses);
            LlmChunkEnrichmentResponse response = objectMapper.readValue(cleanJson(responseText), LlmChunkEnrichmentResponse.class);
            String summary = validateSummary(response.summary());
            List<ChunkEnrichmentQuestion> questions = validateQuestions(response.questions());
            log.info("AgentScope enrichment 生成出参: chunkId={}, summaryLength={}, questionCount={}",
                    command.chunkId(), summary.length(), questions.size());
            return new GenerateResult(
                    summary,
                    questions,
                    properties.provider(),
                    properties.model(),
                    properties.promptVersion()
            );
        } catch (Exception exception) {
            log.error("AgentScope enrichment 生成异常: knowledgeBaseId={}, fileId={}, chunkId={}",
                    command.knowledgeBaseId(), command.fileId(), command.chunkId(), exception);
            throw new IllegalStateException("AgentScope 生成 chunk enrichment 失败: " + exception.getMessage(), exception);
        }
    }

    private String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("LLM 返回为空。");
        }
        StringBuilder builder = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            for (ContentBlock contentBlock : response.getContent()) {
                if (contentBlock instanceof TextBlock textBlock) {
                    builder.append(textBlock.getText());
                } else {
                    builder.append(contentBlock);
                }
            }
        }
        String text = builder.toString().trim();
        if (text.isBlank()) {
            throw new IllegalStateException("LLM 文本内容为空。");
        }
        return text;
    }

    private String cleanJson(String responseText) {
        String text = responseText.trim();
        if (text.startsWith("```json")) {
            text = text.substring("```json".length()).trim();
        } else if (text.startsWith("```")) {
            text = text.substring("```".length()).trim();
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - "```".length()).trim();
        }
        return text;
    }

    private String validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("LLM summary 为空。");
        }
        String normalized = summary.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= properties.summaryMaxChars()) {
            return normalized;
        }
        log.warn("AgentScope enrichment 分支: summary 超长，执行截断, beforeLength={}, maxLength={}",
                normalized.length(), properties.summaryMaxChars());
        return normalized.substring(0, properties.summaryMaxChars());
    }

    private List<ChunkEnrichmentQuestion> validateQuestions(List<LlmChunkEnrichmentResponse.Question> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("LLM questions 为空。");
        }
        List<ChunkEnrichmentQuestion> results = new ArrayList<>();
        for (LlmChunkEnrichmentResponse.Question question : questions) {
            if (question.question() == null || question.question().isBlank()) {
                log.warn("AgentScope enrichment 分支: 跳过空问题");
                continue;
            }
            String type = question.type() == null || question.type().isBlank() ? "specific" : question.type();
            results.add(new ChunkEnrichmentQuestion(question.question().trim(), type.trim()));
            if (results.size() >= properties.maxQuestions()) {
                break;
            }
        }
        if (results.isEmpty()) {
            throw new IllegalStateException("LLM 有返回 questions，但没有有效问题。");
        }
        return results;
    }
}
