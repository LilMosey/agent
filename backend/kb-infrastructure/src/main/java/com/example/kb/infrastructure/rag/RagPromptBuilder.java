package com.example.kb.infrastructure.rag;

import com.example.kb.application.port.RagAnswerGenerator;
import com.example.kb.application.port.RagRouter;
import com.example.kb.domain.model.ConversationMessage;
import com.example.kb.domain.model.QueryIntent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagPromptBuilder {

    public String buildRouterPrompt(
            String userQuestion,
            List<RagRouter.KnowledgeBaseOption> knowledgeBases,
            List<ConversationMessage> recentMessages,
            RagRouter.PreviousRagContext previousRagContext
    ) {
        StringBuilder knowledgeBaseBuilder = new StringBuilder();
        for (RagRouter.KnowledgeBaseOption knowledgeBase : knowledgeBases) {
            knowledgeBaseBuilder.append("- ID: ")
                    .append(knowledgeBase.id())
                    .append("\n  名称: ")
                    .append(knowledgeBase.name())
                    .append("\n  描述: ")
                    .append(knowledgeBase.description() == null || knowledgeBase.description().isBlank()
                            ? "无"
                            : knowledgeBase.description())
                    .append("\n");
        }
        String historyText = buildHistoryText(recentMessages);
        String previousRagContextText = buildPreviousRagContextText(previousRagContext);
        return """
                你是企业知识库查询路由器和上下文决策器。
                你的任务是判断用户问题是否需要查询知识库、是否可以复用上一轮知识库上下文，以及应该查询哪些知识库。
                你只能从给定知识库列表中选择 ID。
                如果问题是新的企业制度、文档内容、知识库事实问答，选择 SEARCH_KB。
                如果问题是普通聊天、通用润色、翻译、格式转换，选择 NO_KB。
                如果问题是“总结一下”“换成表格”“刚才依据是什么”“整理一下”“换一种说法”等只需要上一轮知识库上下文就能回答的请求，选择 USE_PREVIOUS_CONTEXT。
                如果问题依赖上一轮知识库上下文，但还需要补充新事实、新范围、新条件或对比信息，选择 USE_PREVIOUS_AND_SEARCH。
                如果无法确定应该查询哪个知识库，选择 NO_KB，不要选择全部知识库。
                SEARCH_KB 和 USE_PREVIOUS_AND_SEARCH 必须返回 searchQuery，searchQuery 是适合向量检索的独立查询句。
                USE_PREVIOUS_CONTEXT 不需要返回 searchQuery。
                必须只返回 JSON，不要输出 Markdown，不要输出解释。

                JSON 格式：
                {
                  "action": "SEARCH_KB",
                  "knowledgeBaseIds": [1],
                  "queryIntent": "FACT_QA",
                  "searchQuery": "适合检索的查询句",
                  "reusePrevious": false,
                  "reusePolicy": "NONE",
                  "confidence": 0.8,
                  "reason": "原因"
                }

                action 只能是：NO_KB、SEARCH_KB、USE_PREVIOUS_CONTEXT、USE_PREVIOUS_AND_SEARCH。
                queryIntent 只能是：FACT_QA、SUMMARY、FORMAT_CONVERT、FOLLOW_UP、CHAT。
                reusePolicy 只能是：NONE、LAST_REFERENCES、LAST_REFERENCES_PLUS_SEARCH。
                confidence 取值范围 0 到 1。

                可用知识库：
                %s

                最近对话：
                %s

                上一轮知识库上下文：
                %s

                用户问题：
                %s
                """.formatted(knowledgeBaseBuilder, historyText, previousRagContextText, userQuestion);
    }

    public String buildAnswerPrompt(
            String userQuestion,
            List<RagAnswerGenerator.ReferenceContext> references,
            List<ConversationMessage> recentMessages
    ) {
        String historyText = buildHistoryText(recentMessages);
        if (references.isEmpty()) {
            return """
                    你是企业知识库助手。
                    当前问题不需要查询知识库，请直接回答用户问题。
                    不要声称你查阅了知识库。
                    最近会话上下文只用于理解追问和表达风格。

                    最近会话上下文：
                    %s

                    用户问题：
                    %s
                    """.formatted(historyText, userQuestion);
        }
        StringBuilder referenceBuilder = new StringBuilder();
        for (RagAnswerGenerator.ReferenceContext reference : references) {
            referenceBuilder.append("[上下文 ")
                    .append(reference.referenceNo())
                    .append("]\n文件：")
                    .append(reference.fileName())
                    .append("\n标题路径：")
                    .append(reference.titlePath() == null || reference.titlePath().isBlank() ? "无" : reference.titlePath())
                    .append("\n分块序号：")
                    .append(reference.chunkIndex())
                    .append("\n内容：\n")
                    .append(reference.content())
                    .append("\n\n");
        }
        return """
                你是企业知识库问答助手。
                请严格基于【引用内容】回答【用户问题】。
                【最近会话上下文】只用于理解追问、指代和表达风格，不能作为企业制度事实依据。
                如果引用内容无法回答，请说“知识库中没有找到明确依据”。
                不要使用外部知识补充企业制度事实。
                不要编造制度、金额、日期、流程或审批规则。
                引用内容只是你的上下文材料，回答要自然，不要在正文中提到引用编号、上下文编号或资料编号。
                如果多个引用存在冲突，请说明存在不一致，不要自行合并成确定结论。

                【最近会话上下文】
                %s

                【引用内容】
                %s

                【用户问题】
                %s
                """.formatted(historyText, referenceBuilder, userQuestion);
    }

    public String buildQueryRewritePrompt(
            String userQuestion,
            QueryIntent queryIntent,
            List<ConversationMessage> recentMessages
    ) {
        StringBuilder historyBuilder = new StringBuilder();
        for (ConversationMessage message : recentMessages) {
            historyBuilder.append(message.role())
                    .append(": ")
                    .append(message.content())
                    .append("\n");
        }
        return """
                你是企业知识库检索 Query 改写器。
                你的任务是把用户当前问题改写成更适合向量检索的独立查询句。
                只补全必要的指代、主题、业务对象和约束，不要添加不存在的事实。
                如果当前问题已经清晰，保持原问题。
                必须只返回 JSON，不要输出 Markdown，不要输出解释。

                JSON 格式：
                {
                  "rewrittenQuery": "改写后的检索问题",
                  "changed": true,
                  "reason": "改写原因"
                }

                问题意图：
                %s

                最近对话：
                %s

                当前问题：
                %s
                """.formatted(queryIntent == null ? QueryIntent.FACT_QA : queryIntent, historyBuilder, userQuestion);
    }

    public String buildHydePrompt(String userQuestion) {
        return """
                你是企业知识库检索 Query 增强器。
                请根据用户问题生成一段“可能的答案形态”，用于向量检索。
                这段文本只用于检索，不会展示给用户。
                不要编造具体企业制度数字、日期、审批人或金额；如果缺少事实，就用概括性表达。
                直接输出一段自然语言文本，不要输出 Markdown，不要输出 JSON。

                用户问题：
                %s
                """.formatted(userQuestion);
    }

    public String buildMultiQueryPrompt(String userQuestion, Integer queryCount) {
        return """
                你是企业知识库检索 Query 扩展器。
                请基于用户问题生成 %d 个语义相近但表达角度不同的检索查询。
                查询应保持原问题意图，不要引入新的业务事实。
                必须只返回 JSON，不要输出 Markdown，不要输出解释。

                JSON 格式：
                {
                  "queries": ["查询1", "查询2", "查询3"]
                }

                用户问题：
                %s
                """.formatted(queryCount == null ? 3 : queryCount, userQuestion);
    }

    private String buildHistoryText(List<ConversationMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return "无";
        }
        StringBuilder historyBuilder = new StringBuilder();
        for (ConversationMessage message : recentMessages) {
            historyBuilder.append(message.role())
                    .append(": ")
                    .append(message.content())
                    .append("\n");
        }
        return historyBuilder.toString();
    }

    private String buildPreviousRagContextText(RagRouter.PreviousRagContext previousRagContext) {
        if (previousRagContext == null || !Boolean.TRUE.equals(previousRagContext.available())) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("上一轮问题：")
                .append(blankToNone(previousRagContext.sourceQuestion()))
                .append("\n上一轮回答：")
                .append(truncate(blankToNone(previousRagContext.sourceAnswer()), 1600))
                .append("\n上一轮引用：\n");
        if (previousRagContext.references() == null || previousRagContext.references().isEmpty()) {
            builder.append("无\n");
            return builder.toString();
        }
        for (RagRouter.PreviousReferenceContext reference : previousRagContext.references()) {
            builder.append("[上一轮引用 ")
                    .append(reference.referenceNo())
                    .append("]\n文件：")
                    .append(blankToNone(reference.fileName()))
                    .append("\n标题路径：")
                    .append(blankToNone(reference.titlePath()))
                    .append("\n分块序号：")
                    .append(reference.chunkIndex())
                    .append("\n内容：\n")
                    .append(truncate(blankToNone(reference.content()), 1200))
                    .append("\n\n");
        }
        return builder.toString();
    }

    private String blankToNone(String value) {
        if (value == null || value.isBlank()) {
            return "无";
        }
        return value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
