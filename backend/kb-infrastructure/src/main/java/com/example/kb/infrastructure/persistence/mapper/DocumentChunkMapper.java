package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.DocumentChunkEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    int insertBatch(@Param("entities") List<DocumentChunkEntity> entities);

    List<DocumentChunkEntity> selectByIds(@Param("ids") List<Long> ids);
}
