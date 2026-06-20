package com.example.kb.api.dto;

import com.example.kb.application.port.RagRouter;
import com.example.kb.application.service.RagChatService;
import com.example.kb.domain.model.Conversation;
import com.example.kb.domain.model.ConversationMessage;
import com.example.kb.domain.model.MessageRole;
import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ConversationDtos {

    private ConversationDtos() {
    }

    public record ConversationResponse(
            Long id,
            String title,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {
        public static ConversationResponse from(Conversation conversation) {
            return new ConversationResponse(
                    conversation.id(),
                    conversation.title(),
                    conversation.createdAt(),
                    conversation.updatedAt(),
                    conversation.deletedAt()
            );
        }
    }

    public record UpdateConversationRequest(
            @NotBlank(message = "会话名称不能为空。")
            @Size(max = 255, message = "会话名称不能超过 255 个字符。")
            String title
    ) {
    }

    public record ConversationMessageResponse(
            Long id,
            Long conversationId,
            MessageRole role,
            String content,
            Integer messageOrder,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static ConversationMessageResponse from(ConversationMessage message) {
            return new ConversationMessageResponse(
                    message.id(),
                    message.conversationId(),
                    message.role(),
                    message.content(),
                    message.messageOrder(),
                    message.createdAt(),
                    message.updatedAt()
            );
        }
    }

    public record SendMessageRequest(
            @NotBlank(message = "消息内容不能为空。")
            @Size(max = 4000, message = "消息内容不能超过 4000 个字符。")
            String content
    ) {
    }

    public record SendMessageResponse(
            Long messageId,
            String content,
            RouterResponse router,
            List<ReferenceResponse> references
    ) {
        public static SendMessageResponse from(RagChatService.SendMessageResult result) {
            return new SendMessageResponse(
                    result.assistantMessage().id(),
                    result.assistantMessage().content(),
                    RouterResponse.from(result.router()),
                    result.references().stream()
                            .map(ReferenceResponse::from)
                            .toList()
            );
        }
    }

    public record RouterResponse(
            RagRouterAction action,
            List<Long> knowledgeBaseIds,
            QueryIntent queryIntent,
            BigDecimal confidence,
            String reason
    ) {
        public static RouterResponse from(RagRouter.RouteResult result) {
            return new RouterResponse(
                    result.action(),
                    result.knowledgeBaseIds(),
                    result.queryIntent(),
                    result.confidence(),
                    result.reason()
            );
        }
    }

    public record ReferenceResponse(
            Integer referenceNo,
            Long knowledgeBaseId,
            Long fileId,
            String fileName,
            Long chunkId,
            Integer chunkIndex,
            String titlePath,
            BigDecimal score,
            String contentPreview
    ) {
        public static ReferenceResponse from(RagChatService.ReferenceResult result) {
            return new ReferenceResponse(
                    result.referenceNo(),
                    result.knowledgeBaseId(),
                    result.fileId(),
                    result.fileName(),
                    result.chunkId(),
                    result.chunkIndex(),
                    result.titlePath(),
                    result.score(),
                    result.contentPreview()
            );
        }
    }
}
