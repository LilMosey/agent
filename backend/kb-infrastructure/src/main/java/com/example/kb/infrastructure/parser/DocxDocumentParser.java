package com.example.kb.infrastructure.parser;

import com.example.kb.application.port.DocumentParseCommand;
import com.example.kb.application.port.DocumentParser;
import com.example.kb.domain.model.DocumentSection;
import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.ParsedDocument;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxDocumentParser.class);
    private static final Pattern ENGLISH_HEADING_PATTERN = Pattern.compile("^heading([1-6])$");
    private static final Pattern CHINESE_HEADING_PATTERN = Pattern.compile("^标题([1-6])$");

    @Override
    public boolean supports(FileType fileType) {
        return FileType.WORD == fileType;
    }

    @Override
    public ParsedDocument parse(DocumentParseCommand command) {
        log.info("DOCX 文档解析入参: knowledgeBaseId={}, fileId={}, filename={}",
                command.knowledgeBaseId(), command.fileId(), command.filename());
        try (XWPFDocument document = new XWPFDocument(command.inputStream())) {
            ParseState state = new ParseState(command);
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (BodyElementType.PARAGRAPH == bodyElement.getElementType()) {
                    state.handleParagraph((XWPFParagraph) bodyElement);
                } else if (BodyElementType.TABLE == bodyElement.getElementType()) {
                    state.handleTable((XWPFTable) bodyElement);
                }
            }

            List<DocumentSection> sections = state.toSections();
            ParsedDocument parsedDocument = new ParsedDocument(
                    command.knowledgeBaseId(),
                    command.fileId(),
                    command.filename(),
                    command.fileType(),
                    state.documentTitle(),
                    sections,
                    Map.of("parser", "docx")
            );
            log.info("DOCX 文档解析出参: fileId={}, paragraphCount={}, headingParagraphCount={}, tableCount={}, sectionCount={}",
                    command.fileId(), state.paragraphCount, state.headingParagraphCount, state.tableCount, sections.size());
            return parsedDocument;
        } catch (Exception exception) {
            log.error("DOCX 文档解析异常: fileId={}, filename={}", command.fileId(), command.filename(), exception);
            throw new IllegalStateException("DOCX 文档解析失败: " + exception.getMessage(), exception);
        }
    }

    private static int headingLevel(XWPFParagraph paragraph) {
        int styleLevel = headingLevel(paragraph.getStyle());
        if (styleLevel > 0) {
            return styleLevel;
        }
        return headingLevel(paragraph.getStyleID());
    }

    private static int headingLevel(String styleValue) {
        if (styleValue == null || styleValue.isBlank()) {
            return 0;
        }
        String normalized = styleValue.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
        Matcher englishMatcher = ENGLISH_HEADING_PATTERN.matcher(normalized);
        if (englishMatcher.matches()) {
            return Integer.parseInt(englishMatcher.group(1));
        }
        Matcher chineseMatcher = CHINESE_HEADING_PATTERN.matcher(normalized);
        if (chineseMatcher.matches()) {
            return Integer.parseInt(chineseMatcher.group(1));
        }
        return 0;
    }

    private static String paragraphText(XWPFParagraph paragraph) {
        return TextNormalizer.normalize(paragraph.getText());
    }

    private static String tableText(XWPFTable table) {
        List<String> lines = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cellTexts = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cellTexts.add(TextNormalizer.normalize(cell.getText()).replace('\n', ' '));
            }
            String line = TextNormalizer.normalize(String.join(" | ", cellTexts));
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static final class ParseState {

        private final DocumentParseCommand command;
        private final List<MutableSection> sections = new ArrayList<>();
        private final MutableSection[] headingStack = new MutableSection[6];
        private MutableSection currentSection;
        private String documentTitle;
        private int paragraphCount;
        private int headingParagraphCount;
        private int tableCount;

        private ParseState(DocumentParseCommand command) {
            this.command = command;
        }

        private void handleParagraph(XWPFParagraph paragraph) {
            paragraphCount++;
            String text = paragraphText(paragraph);
            if (text.isBlank()) {
                return;
            }

            int headingLevel = headingLevel(paragraph);
            if (headingLevel > 0) {
                headingParagraphCount++;
                startHeadingSection(headingLevel, text);
                return;
            }

            ensureCurrentSection().append(text);
        }

        private void handleTable(XWPFTable table) {
            tableCount++;
            String text = tableText(table);
            if (!text.isBlank()) {
                ensureCurrentSection().append(text);
            }
        }

        private List<DocumentSection> toSections() {
            if (sections.isEmpty()) {
                createDefaultRootSection();
            }
            List<DocumentSection> result = new ArrayList<>(sections.size());
            for (MutableSection section : sections) {
                result.add(section.toDocumentSection());
            }
            return result;
        }

        private String documentTitle() {
            if (documentTitle == null || documentTitle.isBlank()) {
                return command.filename();
            }
            return documentTitle;
        }

        private MutableSection ensureCurrentSection() {
            if (currentSection == null) {
                currentSection = createDefaultRootSection();
            }
            return currentSection;
        }

        private MutableSection createDefaultRootSection() {
            MutableSection section = new MutableSection(
                    SectionIdGenerator.rootId(command.fileId()),
                    null,
                    1,
                    command.filename(),
                    sections.size(),
                    command.filename()
            );
            sections.add(section);
            for (int index = 0; index < headingStack.length; index++) {
                headingStack[index] = null;
            }
            currentSection = section;
            return section;
        }

        private void startHeadingSection(int level, String title) {
            MutableSection parent = findParent(level);
            String titlePath = titlePath(parent, title);
            MutableSection section = new MutableSection(
                    SectionIdGenerator.sectionId(command.fileId(), sections.size()),
                    parent == null ? null : parent.id,
                    level,
                    title,
                    sections.size(),
                    titlePath
            );
            sections.add(section);
            headingStack[level - 1] = section;
            if (level == 1 && (documentTitle == null || documentTitle.isBlank())) {
                documentTitle = title;
            }
            for (int index = level; index < headingStack.length; index++) {
                headingStack[index] = null;
            }
            currentSection = section;
        }

        private MutableSection findParent(int level) {
            for (int index = level - 2; index >= 0; index--) {
                if (headingStack[index] != null) {
                    return headingStack[index];
                }
            }
            return null;
        }

        private String titlePath(MutableSection parent, String title) {
            if (parent == null) {
                return title;
            }
            return parent.titlePath + " / " + title;
        }
    }

    private static final class MutableSection {

        private final String id;
        private final String parentId;
        private final Integer level;
        private final String title;
        private final Integer orderIndex;
        private final String titlePath;
        private final StringBuilder content = new StringBuilder();

        private MutableSection(String id, String parentId, Integer level, String title, Integer orderIndex, String titlePath) {
            this.id = id;
            this.parentId = parentId;
            this.level = level;
            this.title = title;
            this.orderIndex = orderIndex;
            this.titlePath = titlePath;
        }

        private void append(String text) {
            String normalizedText = TextNormalizer.normalize(text);
            if (normalizedText.isBlank()) {
                return;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(normalizedText);
        }

        private DocumentSection toDocumentSection() {
            return new DocumentSection(
                    id,
                    parentId,
                    level,
                    title,
                    TextNormalizer.normalize(content.toString()),
                    orderIndex,
                    Map.of("title_path", titlePath)
            );
        }
    }
}
