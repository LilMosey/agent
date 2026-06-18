package com.example.kb.infrastructure.enrichment;

import com.example.kb.application.port.ChunkEnrichmentGenerator;
import org.springframework.stereotype.Component;

@Component
public class ChunkEnrichmentPromptBuilder {

    public String build(ChunkEnrichmentGenerator.GenerateCommand command, ChunkEnrichmentProperties properties) {
        String titlePath = command.titlePath() == null || command.titlePath().isBlank() ? "无" : command.titlePath();
        return """
                你是企业知识库 RAG 索引增强助手。
                请只基于给定 chunk 原文生成摘要和用户可能提问。
                不要补充外部知识。
                必须只返回 JSON，不要输出 Markdown，不要输出解释。

                JSON 格式：
                {
                  "summary": "不超过 %d 个中文字符的摘要",
                  "questions": [
                    {"question": "问题", "type": "specific"},
                    {"question": "问题", "type": "summary"},
                    {"question": "问题", "type": "scenario"}
                  ]
                }

                文件名：%s
                标题路径：%s
                chunk 原文：
                %s
                """.formatted(
                properties.summaryMaxChars(),
                command.filename(),
                titlePath,
                command.chunkContent()
        );
    }
}
