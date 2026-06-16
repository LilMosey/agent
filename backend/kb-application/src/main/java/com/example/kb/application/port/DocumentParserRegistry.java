package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;

public interface DocumentParserRegistry {

    DocumentParser getParser(FileType fileType);
}
