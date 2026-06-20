package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.ConversationRetrievalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationRetrievalMapper extends BaseMapper<ConversationRetrievalEntity> {

    ConversationRetrievalEntity selectLatestWithReferencesByConversationId(@Param("conversationId") Long conversationId);
}
