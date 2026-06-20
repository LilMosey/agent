package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

    Integer selectNextMessageOrder(@Param("conversationId") Long conversationId);
}
