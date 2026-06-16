package com.example.kb.domain.service;

import com.example.kb.domain.model.FileType;

public class FileSignaturePolicy {

    private static final byte ZIP_HEADER_FIRST = 0x50;
    private static final byte ZIP_HEADER_SECOND = 0x4B;
    private static final byte OLE2_HEADER_FIRST = (byte) 0xD0;
    private static final byte OLE2_HEADER_SECOND = (byte) 0xCF;

    public void validate(FileType fileType, byte[] content) {
        if (fileType == FileType.WORD) {
            validateDocx(content);
        }
    }

    private void validateDocx(byte[] content) {
        if (content == null || content.length < 2) {
            throw new IllegalArgumentException("DOCX 文件内容为空或格式不完整。");
        }
        if (content[0] == ZIP_HEADER_FIRST && content[1] == ZIP_HEADER_SECOND) {
            return;
        }
        if (content[0] == OLE2_HEADER_FIRST && content[1] == OLE2_HEADER_SECOND) {
            throw new IllegalArgumentException("当前版本仅支持真正的 DOCX 文件。该文件内容仍是旧版 DOC 格式，请在 Word/WPS 中另存为 DOCX 后上传。");
        }
        throw new IllegalArgumentException("DOCX 文件格式不正确，请确认文件是 Word/WPS 另存为的 DOCX 文件。");
    }
}
