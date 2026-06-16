package com.example.kb.domain.service;

import com.example.kb.domain.model.FileType;

import java.util.Locale;

public class FileTypePolicy {

    public FileType detect(String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "docx" -> FileType.WORD;
            case "doc" -> throw new IllegalArgumentException("当前版本仅支持 DOCX，不支持旧版 DOC 文件，请另存为 DOCX 后上传。");
            case "md", "markdown" -> FileType.MARKDOWN;
            case "txt" -> FileType.TEXT;
            case "pdf" -> throw new IllegalArgumentException("当前版本不支持上传 PDF 文件。");
            default -> throw new IllegalArgumentException("仅支持上传 DOCX、Markdown、TXT 文件。");
        };
    }

    public String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空。");
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("文件缺少扩展名。");
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
