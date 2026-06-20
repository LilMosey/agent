package com.example.kb.api.controller;

import com.example.kb.api.dto.ApiResponse;
import com.example.kb.api.dto.ConversationDtos;
import com.example.kb.application.service.ConversationService;
import com.example.kb.application.service.RagChatService;
import com.example.kb.domain.model.Conversation;
import com.example.kb.domain.model.ConversationMessage;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationService conversationService;
    private final RagChatService ragChatService;

    public ConversationController(ConversationService conversationService, RagChatService ragChatService) {
        this.conversationService = conversationService;
        this.ragChatService = ragChatService;
    }

    @PostMapping
    public ApiResponse<ConversationDtos.ConversationResponse> create() {
        log.info("创建会话接口入参: none");
        Conversation conversation = conversationService.createConversation();
        ConversationDtos.ConversationResponse response = ConversationDtos.ConversationResponse.from(conversation);
        log.info("创建会话接口出参: id={}, title={}", response.id(), response.title());
        return ApiResponse.ok(response);
    }

    @GetMapping
    public ApiResponse<List<ConversationDtos.ConversationResponse>> list() {
        log.info("查询会话列表接口入参: none");
        List<ConversationDtos.ConversationResponse> responses = conversationService.listConversations().stream()
                .map(ConversationDtos.ConversationResponse::from)
                .toList();
        log.info("查询会话列表接口出参: count={}", responses.size());
        return ApiResponse.ok(responses);
    }

    @PutMapping("/{conversationId}")
    public ApiResponse<ConversationDtos.ConversationResponse> update(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody ConversationDtos.UpdateConversationRequest request
    ) {
        log.info("修改会话名称接口入参: conversationId={}, title={}", conversationId, request.title());
        Conversation conversation = conversationService.updateTitle(conversationId, request.title());
        ConversationDtos.ConversationResponse response = ConversationDtos.ConversationResponse.from(conversation);
        log.info("修改会话名称接口出参: conversationId={}, title={}", response.id(), response.title());
        return ApiResponse.ok(response);
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(@PathVariable("conversationId") Long conversationId) {
        log.info("删除会话接口入参: conversationId={}", conversationId);
        conversationService.deleteConversation(conversationId);
        log.info("删除会话接口出参: conversationId={}, deleted=true", conversationId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<ConversationDtos.ConversationMessageResponse>> listMessages(
            @PathVariable("conversationId") Long conversationId
    ) {
        log.info("查询会话消息接口入参: conversationId={}", conversationId);
        List<ConversationMessage> messages = conversationService.listMessages(conversationId);
        List<ConversationDtos.ConversationMessageResponse> responses = messages.stream()
                .map(ConversationDtos.ConversationMessageResponse::from)
                .toList();
        log.info("查询会话消息接口出参: conversationId={}, count={}", conversationId, responses.size());
        return ApiResponse.ok(responses);
    }

    @PostMapping("/{conversationId}/messages")
    public ApiResponse<ConversationDtos.SendMessageResponse> sendMessage(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody ConversationDtos.SendMessageRequest request
    ) {
        log.info("发送会话消息接口入参: conversationId={}, contentLength={}", conversationId, request.content().length());
        RagChatService.SendMessageResult result = ragChatService.sendMessage(conversationId, request.content());
        ConversationDtos.SendMessageResponse response = ConversationDtos.SendMessageResponse.from(result);
        log.info("发送会话消息接口出参: conversationId={}, messageId={}, referenceCount={}",
                conversationId, response.messageId(), response.references().size());
        return ApiResponse.ok(response);
    }
}
