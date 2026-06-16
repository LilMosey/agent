package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.ParsedDocument;

public interface DocumentParser {

    boolean supports(FileType fileType);

    ParsedDocument parse(DocumentParseCommand command);
}
