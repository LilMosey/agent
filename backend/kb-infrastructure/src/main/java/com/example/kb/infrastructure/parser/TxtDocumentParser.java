package com.example.kb.infrastructure.parser;

import com.example.kb.application.port.DocumentParseCommand;
import com.example.kb.application.port.DocumentParser;
import com.example.kb.domain.model.DocumentSection;
import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class TxtDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TxtDocumentParser.class);

    @Override
    public boolean supports(FileType fileType) {
        return FileType.TEXT == fileType;
    }

    @Override
    public ParsedDocument parse(DocumentParseCommand command) {
        log.info("TXT 文档解析入参: knowledgeBaseId={}, fileId={}, filename={}",
                command.knowledgeBaseId(), command.fileId(), command.filename());
        try {
            String rawText = new String(command.inputStream().readAllBytes(), StandardCharsets.UTF_8);
            String normalizedText = TextNormalizer.normalize(rawText);
            log.info("TXT 文档解析分支: 使用单根章节, fileId={}, rawLength={}, normalizedLength={}",
                    command.fileId(), rawText.length(), normalizedText.length());
            DocumentSection section = new DocumentSection(
                    SectionIdGenerator.rootId(command.fileId()),
                    null,
                    1,
                    command.filename(),
                    normalizedText,
                    0,
                    Map.of("title_path", command.filename())
            );
            ParsedDocument parsedDocument = new ParsedDocument(
                    command.knowledgeBaseId(),
                    command.fileId(),
                    command.filename(),
                    command.fileType(),
                    command.filename(),
                    List.of(section),
                    Map.of("parser", "txt")
            );
            log.info("TXT 文档解析出参: fileId={}, title={}, sectionCount={}",
                    command.fileId(), parsedDocument.title(), parsedDocument.sections().size());
            return parsedDocument;
        } catch (Exception exception) {
            log.error("TXT 文档解析异常: fileId={}, filename={}", command.fileId(), command.filename(), exception);
            throw new IllegalStateException("TXT 文档解析失败: " + exception.getMessage(), exception);
        }
    }
}
