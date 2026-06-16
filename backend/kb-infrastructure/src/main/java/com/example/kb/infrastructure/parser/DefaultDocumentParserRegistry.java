package com.example.kb.infrastructure.parser;

import com.example.kb.application.port.DocumentParser;
import com.example.kb.application.port.DocumentParserRegistry;
import com.example.kb.domain.model.FileType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultDocumentParserRegistry implements DocumentParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentParserRegistry.class);

    private final List<DocumentParser> parsers;

    public DefaultDocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
        log.info("文档解析器注册表初始化: parserCount={}", parsers.size());
    }

    @Override
    public DocumentParser getParser(FileType fileType) {
        log.info("选择文档解析器入参: fileType={}, parserCount={}", fileType, parsers.size());
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileType)) {
                log.info("选择文档解析器出参: fileType={}, parser={}", fileType, parser.getClass().getSimpleName());
                return parser;
            } else {
                log.info("选择文档解析器分支: parser 不支持该类型, fileType={}, parser={}", fileType, parser.getClass().getSimpleName());
            }
        }
        log.warn("选择文档解析器分支: 未找到解析器, fileType={}", fileType);
        throw new IllegalArgumentException("未找到文件类型对应的解析器: " + fileType);
    }
}
