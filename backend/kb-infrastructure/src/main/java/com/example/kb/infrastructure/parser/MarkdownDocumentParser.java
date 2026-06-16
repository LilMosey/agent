package com.example.kb.infrastructure.parser;

import com.example.kb.application.port.DocumentParseCommand;
import com.example.kb.application.port.DocumentParser;
import com.example.kb.domain.model.DocumentSection;
import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownDocumentParser.class);
    private static final Pattern ATX_HEADING_PATTERN = Pattern.compile("^ {0,3}(#{1,6})(?:\\s+|$)(.*)$");
    private static final String CODE_FENCE = "```";
    private static final String TITLE_PATH_SEPARATOR = " > ";

    @Override
    public boolean supports(FileType fileType) {
        return FileType.MARKDOWN == fileType;
    }

    @Override
    public ParsedDocument parse(DocumentParseCommand command) {
        log.info("Markdown 文档解析器 parse 入参: knowledgeBaseId={}, fileId={}, filename={}, fileType={}, contentType={}",
                command.knowledgeBaseId(), command.fileId(), command.filename(), command.fileType(), command.contentType());
        try {
            ParsedDocument parsedDocument = doParse(command);
            log.info("Markdown 文档解析器 parse 出参: knowledgeBaseId={}, fileId={}, filename={}, title={}, sectionCount={}",
                    parsedDocument.knowledgeBaseId(), parsedDocument.fileId(), parsedDocument.filename(), parsedDocument.title(),
                    parsedDocument.sections().size());
            return parsedDocument;
        } catch (RuntimeException exception) {
            log.error("Markdown 文档解析器 parse 异常: knowledgeBaseId={}, fileId={}, filename={}",
                    command.knowledgeBaseId(), command.fileId(), command.filename(), exception);
            throw exception;
        } catch (IOException exception) {
            log.error("Markdown 文档解析器 parse 异常: knowledgeBaseId={}, fileId={}, filename={}",
                    command.knowledgeBaseId(), command.fileId(), command.filename(), exception);
            throw new IllegalStateException("Markdown 文档读取失败: " + command.filename(), exception);
        }
    }

    private ParsedDocument doParse(DocumentParseCommand command) throws IOException {
        SectionDraft rootSection = createRootSection(command);
        List<SectionDraft> sectionDrafts = new ArrayList<>();
        sectionDrafts.add(rootSection);

        Map<Integer, SectionDraft> latestSectionsByLevel = new HashMap<>();
        SectionDraft currentSection = rootSection;
        String documentTitle = null;
        boolean inCodeBlock = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(command.inputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (isCodeFence(line)) {
                    inCodeBlock = !inCodeBlock;
                    log.info("Markdown 文档解析器 parse 分支: fenced code block 状态切换, fileId={}, inCodeBlock={}",
                            command.fileId(), inCodeBlock);
                    currentSection.appendLine(line);
                } else if (!inCodeBlock) {
                    Matcher headingMatcher = ATX_HEADING_PATTERN.matcher(line);
                    if (headingMatcher.matches()) {
                        int level = headingMatcher.group(1).length();
                        String title = normalizeHeadingTitle(headingMatcher.group(2));
                        log.info("Markdown 文档解析器 parse 分支: 识别 ATX 标题, fileId={}, level={}, title={}",
                                command.fileId(), level, title);
                        currentSection = createHeadingSection(command, sectionDrafts.size(), level, title, latestSectionsByLevel);
                        sectionDrafts.add(currentSection);
                        latestSectionsByLevel.put(level, currentSection);
                        removeDeeperSections(latestSectionsByLevel, level);
                        if (level == 1 && documentTitle == null) {
                            documentTitle = title;
                            log.info("Markdown 文档解析器 parse 分支: 使用首个一级标题作为文档标题, fileId={}, title={}",
                                    command.fileId(), documentTitle);
                        }
                    } else {
                        currentSection.appendLine(line);
                    }
                } else {
                    currentSection.appendLine(line);
                }
                line = reader.readLine();
            }
        }

        if (documentTitle == null) {
            documentTitle = safeFilename(command.filename());
            log.info("Markdown 文档解析器 parse 分支: 未识别一级标题, 使用文件名作为文档标题, fileId={}, title={}",
                    command.fileId(), documentTitle);
        }

        List<DocumentSection> sections = toSections(sectionDrafts);
        Map<String, String> metadata = Map.of("parser", "markdown");
        return new ParsedDocument(
                command.knowledgeBaseId(),
                command.fileId(),
                command.filename(),
                command.fileType(),
                documentTitle,
                sections,
                metadata
        );
    }

    private SectionDraft createRootSection(DocumentParseCommand command) {
        String filename = safeFilename(command.filename());
        String sectionId = SectionIdGenerator.rootId(command.fileId());
        log.info("Markdown 文档解析器 parse 分支: 创建默认根章节, fileId={}, sectionId={}", command.fileId(), sectionId);
        return new SectionDraft(sectionId, null, 1, filename, 0, filename);
    }

    private SectionDraft createHeadingSection(
            DocumentParseCommand command,
            int orderIndex,
            int level,
            String title,
            Map<Integer, SectionDraft> latestSectionsByLevel
    ) {
        SectionDraft parentSection = findParentSection(latestSectionsByLevel, level);
        String sectionId = SectionIdGenerator.sectionId(command.fileId(), orderIndex);
        String parentId = parentSection == null ? null : parentSection.id;
        String titlePath = parentSection == null || parentSection.titlePath.isBlank()
                ? title
                : parentSection.titlePath + TITLE_PATH_SEPARATOR + title;
        log.info("Markdown 文档解析器 parse 分支: 创建标题章节, fileId={}, sectionId={}, parentId={}, level={}, orderIndex={}",
                command.fileId(), sectionId, parentId, level, orderIndex);
        return new SectionDraft(sectionId, parentId, level, title, orderIndex, titlePath);
    }

    private SectionDraft findParentSection(Map<Integer, SectionDraft> latestSectionsByLevel, int level) {
        int parentLevel = level - 1;
        while (parentLevel >= 1) {
            SectionDraft parentSection = latestSectionsByLevel.get(parentLevel);
            if (parentSection != null) {
                return parentSection;
            }
            parentLevel--;
        }
        return null;
    }

    private void removeDeeperSections(Map<Integer, SectionDraft> latestSectionsByLevel, int level) {
        int deeperLevel = level + 1;
        while (deeperLevel <= 6) {
            latestSectionsByLevel.remove(deeperLevel);
            deeperLevel++;
        }
    }

    private List<DocumentSection> toSections(List<SectionDraft> sectionDrafts) {
        List<DocumentSection> sections = new ArrayList<>(sectionDrafts.size());
        for (SectionDraft sectionDraft : sectionDrafts) {
            Map<String, String> metadata = Map.of("title_path", sectionDraft.titlePath);
            sections.add(new DocumentSection(
                    sectionDraft.id,
                    sectionDraft.parentId,
                    sectionDraft.level,
                    sectionDraft.title,
                    TextNormalizer.normalize(sectionDraft.content.toString()),
                    sectionDraft.orderIndex,
                    metadata
            ));
        }
        return sections;
    }

    private boolean isCodeFence(String line) {
        return line.trim().startsWith(CODE_FENCE);
    }

    private String normalizeHeadingTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        return title.replaceFirst("\\s+#+\\s*$", "").trim();
    }

    private String safeFilename(String filename) {
        return filename == null ? "" : filename;
    }

    private static final class SectionDraft {

        private final String id;
        private final String parentId;
        private final Integer level;
        private final String title;
        private final Integer orderIndex;
        private final String titlePath;
        private final StringBuilder content = new StringBuilder();

        private SectionDraft(String id, String parentId, Integer level, String title, Integer orderIndex, String titlePath) {
            this.id = id;
            this.parentId = parentId;
            this.level = level;
            this.title = title;
            this.orderIndex = orderIndex;
            this.titlePath = titlePath;
        }

        private void appendLine(String line) {
            content.append(line).append('\n');
        }
    }
}
