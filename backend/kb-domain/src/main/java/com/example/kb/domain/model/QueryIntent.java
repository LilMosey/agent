package com.example.kb.domain.model;

/**
 * Router 对用户问题的意图分类。
 *
 * <p>第一版查询链路不依赖它做复杂分支，主要用于保存 Router 判断结果、排查日志和后续扩展。
 * 后续接入 Query 改写、上下文复用、混合检索或不同 Prompt 模板时，可以根据该字段选择不同策略。</p>
 *
 * <ul>
 *     <li>FACT_QA：事实问答，通常需要查询知识库。</li>
 *     <li>SUMMARY：总结类请求，后续可以复用上一轮引用内容。</li>
 *     <li>FORMAT_CONVERT：格式转换请求，后续可以复用上一轮回答或引用内容。</li>
 *     <li>FOLLOW_UP：多轮追问，后续通常需要结合历史消息做 Query 改写。</li>
 *     <li>CHAT：普通聊天，通常不需要查询知识库。</li>
 * </ul>
 */
public enum QueryIntent {
    FACT_QA,
    SUMMARY,
    FORMAT_CONVERT,
    FOLLOW_UP,
    CHAT
}
