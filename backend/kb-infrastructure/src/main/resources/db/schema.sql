CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_ext VARCHAR(32) NOT NULL,
    content_type VARCHAR(255) NULL,
    file_size BIGINT NOT NULL,
    checksum_sha256 VARCHAR(128) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_object_key VARCHAR(1024) NOT NULL,
    file_type VARCHAR(64) NOT NULL,
    file_status VARCHAR(64) NOT NULL,
    chunk_strategy VARCHAR(64) NOT NULL DEFAULT 'RECURSIVE',
    chunk_size INT NOT NULL DEFAULT 1000,
    chunk_overlap INT NOT NULL DEFAULT 150,
    parse_error VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_kb_filename (knowledge_base_id, original_filename),
    KEY idx_knowledge_base_id (knowledge_base_id),
    KEY idx_file_status (file_status)
);

CREATE TABLE IF NOT EXISTS knowledge_file_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    section_id VARCHAR(128) NULL,
    parent_section_id VARCHAR(128) NULL,
    chunk_index INT NOT NULL,
    chunk_strategy VARCHAR(64) NOT NULL,
    chunk_size INT NOT NULL,
    chunk_overlap INT NOT NULL,
    title_path VARCHAR(1024) NULL,
    content_preview VARCHAR(512) NULL,
    content_hash VARCHAR(128) NOT NULL,
    content_size INT NOT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_object_key VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_chunk_file_id (file_id),
    KEY idx_chunk_kb_file (knowledge_base_id, file_id),
    KEY idx_chunk_strategy (chunk_strategy)
);

CREATE TABLE IF NOT EXISTS knowledge_file_chunk_enrichment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    enrichment_strategy VARCHAR(64) NOT NULL,
    summary VARCHAR(1024) NULL,
    questions_json JSON NULL,
    embedding_text_bucket VARCHAR(255) NOT NULL,
    embedding_text_object_key VARCHAR(1024) NOT NULL,
    llm_provider VARCHAR(64) NULL,
    llm_model VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    status VARCHAR(64) NOT NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_enrichment_chunk_strategy (chunk_id, enrichment_strategy),
    KEY idx_enrichment_file_id (file_id),
    KEY idx_enrichment_kb_file (knowledge_base_id, file_id),
    KEY idx_enrichment_status (status)
);

CREATE TABLE IF NOT EXISTS knowledge_file_index_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    status VARCHAR(64) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 3,
    error_message VARCHAR(2048) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_index_task_status (status),
    KEY idx_index_task_file_id (file_id)
);

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    KEY idx_conversation_deleted_updated (deleted_at, updated_at)
);

CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    message_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_message_conversation_id (conversation_id),
    KEY idx_message_conversation_order (conversation_id, message_order)
);

CREATE TABLE IF NOT EXISTS conversation_retrieval (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    query_text TEXT NOT NULL,
    action VARCHAR(64) NOT NULL,
    knowledge_base_ids_json JSON NULL,
    query_intent VARCHAR(64) NOT NULL,
    confidence DECIMAL(5, 4) NOT NULL DEFAULT 0,
    reason VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_retrieval_conversation_id (conversation_id),
    KEY idx_retrieval_message_id (message_id)
);

CREATE TABLE IF NOT EXISTS conversation_retrieval_reference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_retrieval_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    title_path VARCHAR(1024) NULL,
    score DECIMAL(10, 6) NOT NULL DEFAULT 0,
    content_preview VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_reference_retrieval_id (conversation_retrieval_id),
    KEY idx_reference_chunk_id (chunk_id),
    KEY idx_reference_file_id (file_id)
);
