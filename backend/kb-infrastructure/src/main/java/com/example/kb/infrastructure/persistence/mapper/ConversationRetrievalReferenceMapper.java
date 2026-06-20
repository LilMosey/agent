package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.ConversationRetrievalReferenceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationRetrievalReferenceMapper extends BaseMapper<ConversationRetrievalReferenceEntity> {

    int insertBatch(@Param("entities") List<ConversationRetrievalReferenceEntity> entities);
}
