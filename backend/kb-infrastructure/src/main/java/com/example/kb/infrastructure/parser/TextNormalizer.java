package com.example.kb.infrastructure.parser;

public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        String normalized = normalizeLineBreaks(text);
        String withoutControlChars = removeControlChars(normalized);
        return trimAndCollapseBlankLines(withoutControlChars);
    }

    public static String normalizeLineBreaks(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String removeControlChars(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (!Character.isISOControl(current) || current == '\n' || current == '\t') {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    public static String trimAndCollapseBlankLines(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\n{3,}", "\n\n");
    }
}
