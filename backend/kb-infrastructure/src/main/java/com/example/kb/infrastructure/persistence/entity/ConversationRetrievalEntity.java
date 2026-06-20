package com.example.kb.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("conversation_retrieval")
public class ConversationRetrievalEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long messageId;
    private String queryText;
    private String action;
    private String knowledgeBaseIdsJson;
    private String queryIntent;
    private BigDecimal confidence;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getKnowledgeBaseIdsJson() { return knowledgeBaseIdsJson; }
    public void setKnowledgeBaseIdsJson(String knowledgeBaseIdsJson) { this.knowledgeBaseIdsJson = knowledgeBaseIdsJson; }
    public String getQueryIntent() { return queryIntent; }
    public void setQueryIntent(String queryIntent) { this.queryIntent = queryIntent; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
