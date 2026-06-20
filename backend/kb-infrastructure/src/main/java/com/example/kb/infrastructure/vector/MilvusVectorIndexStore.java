package com.example.kb.infrastructure.vector;

import com.example.kb.application.port.VectorIndexCleaner;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.port.VectorIndexWriter;
import com.example.kb.application.port.KeywordIndexSearcher;
import com.example.kb.infrastructure.embedding.EmbeddingProperties;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MilvusVectorIndexStore implements VectorIndexWriter, VectorIndexCleaner, VectorIndexSearcher, KeywordIndexSearcher {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorIndexStore.class);
    private static final int INSERT_BATCH_SIZE = 100;
    private static final int CONTENT_TEXT_MAX_LENGTH = 30000;
    private static final String VECTOR_FIELD = "vector";
    private static final String CONTENT_TEXT_FIELD = "content_text";
    private static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    private final MilvusProperties milvusProperties;
    private final EmbeddingProperties embeddingProperties;
    private final MilvusClientV2 milvusClient;

    public MilvusVectorIndexStore(MilvusProperties milvusProperties, EmbeddingProperties embeddingProperties) {
        this.milvusProperties = milvusProperties;
        this.embeddingProperties = embeddingProperties;
        this.milvusClient = connectWithRetry();
    }

    @Override
    public void upsertChunks(UpsertChunksCommand command) {
        log.info("Milvus 向量写入入参: knowledgeBaseId={}, fileId={}, count={}",
                command.knowledgeBaseId(), command.fileId(), command.chunks().size());
        if (command.chunks().isEmpty()) {
            log.info("Milvus 向量写入分支: 空列表, fileId={}", command.fileId());
            return;
        }
        ensureCollection();
        deleteByFileId(command.fileId());
        int insertedRows = 0;
        int batchCount = 0;
        List<JsonObject> rows = buildRows(command);
        for (int fromIndex = 0; fromIndex < rows.size(); fromIndex += INSERT_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + INSERT_BATCH_SIZE, rows.size());
            List<JsonObject> batchRows = rows.subList(fromIndex, toIndex);
            InsertReq insertReq = InsertReq.builder()
                    .databaseName(milvusProperties.database())
                    .collectionName(milvusProperties.collectionName())
                    .data(batchRows)
                    .build();
            milvusClient.insert(insertReq);
            insertedRows += batchRows.size();
            batchCount++;
        }
        log.info("Milvus 向量写入出参: fileId={}, insertedRows={}, batchSize={}, batchCount={}",
                command.fileId(), insertedRows, INSERT_BATCH_SIZE, batchCount);
    }

    @Override
    public void deleteByFileId(Long fileId) {
        log.info("删除向量索引入参: fileId={}", fileId);
        if (!hasCollection()) {
            log.info("删除向量索引分支: collection 不存在, fileId={}, collection={}",
                    fileId, milvusProperties.collectionName());
            return;
        }
        DeleteReq deleteReq = DeleteReq.builder()
                .databaseName(milvusProperties.database())
                .collectionName(milvusProperties.collectionName())
                .filter("file_id == " + fileId)
                .build();
        milvusClient.delete(deleteReq);
        log.info("删除向量索引出参: fileId={}, collection={}", fileId, milvusProperties.collectionName());
    }

    @Override
    public SearchResult search(SearchCommand command) {
        log.info("Milvus 向量检索入参: knowledgeBaseIds={}, topK={}, vectorDimension={}",
                command.knowledgeBaseIds(), command.topK(), command.queryVector().size());
        if (command.knowledgeBaseIds().isEmpty()) {
            log.info("Milvus 向量检索分支: knowledgeBaseIds 为空");
            return new SearchResult(List.of());
        }
        if (!hasCollection()) {
            log.warn("Milvus 向量检索分支: collection 不存在, collection={}", milvusProperties.collectionName());
            return new SearchResult(List.of());
        }
        validateVectorDimension(command.queryVector());
        String filter = buildKnowledgeBaseFilter(command.knowledgeBaseIds());
        List<BaseVector> queryVectors = List.of(new FloatVec(command.queryVector()));
        SearchReq searchReq = SearchReq.builder()
                .databaseName(milvusProperties.database())
                .collectionName(milvusProperties.collectionName())
                .annsField(VECTOR_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(command.topK())
                .filter(filter)
                .data(queryVectors)
                .outputFields(List.of("knowledge_base_id", "file_id", "chunk_id", "chunk_index"))
                .build();
        SearchResp searchResp = milvusClient.search(searchReq);
        List<SearchHit> hits = convertSearchHits(searchResp);
        log.info("Milvus 向量检索出参: knowledgeBaseIds={}, filter={}, hitCount={}",
                command.knowledgeBaseIds(), filter, hits.size());
        return new SearchResult(hits);
    }

    @Override
    public KeywordSearchResult search(KeywordSearchCommand command) {
        log.info("Milvus BM25 检索入参: knowledgeBaseIds={}, topK={}, queryLength={}",
                command.knowledgeBaseIds(), command.topK(), command.queryText().length());
        if (command.knowledgeBaseIds().isEmpty()) {
            log.info("Milvus BM25 检索分支: knowledgeBaseIds 为空");
            return new KeywordSearchResult(List.of());
        }
        if (!hasCollection()) {
            log.warn("Milvus BM25 检索分支: collection 不存在, collection={}", milvusProperties.collectionName());
            return new KeywordSearchResult(List.of());
        }
        if (!milvusProperties.isBm25Enabled()) {
            log.warn("Milvus BM25 检索分支: Milvus bm25-enabled 未开启");
            return new KeywordSearchResult(List.of());
        }
        String filter = buildKnowledgeBaseFilter(command.knowledgeBaseIds());
        List<BaseVector> queryTexts = List.of(new EmbeddedText(command.queryText()));
        SearchReq searchReq = SearchReq.builder()
                .databaseName(milvusProperties.database())
                .collectionName(milvusProperties.collectionName())
                .annsField(SPARSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .topK(command.topK())
                .filter(filter)
                .data(queryTexts)
                .outputFields(List.of("knowledge_base_id", "file_id", "chunk_id", "chunk_index"))
                .build();
        SearchResp searchResp = milvusClient.search(searchReq);
        List<SearchHit> searchHits = convertSearchHits(searchResp);
        List<KeywordSearchHit> hits = searchHits.stream()
                .map(hit -> new KeywordSearchHit(
                        hit.knowledgeBaseId(),
                        hit.fileId(),
                        hit.chunkId(),
                        hit.chunkIndex(),
                        hit.score()
                ))
                .toList();
        log.info("Milvus BM25 检索出参: knowledgeBaseIds={}, filter={}, hitCount={}",
                command.knowledgeBaseIds(), filter, hits.size());
        return new KeywordSearchResult(hits);
    }

    private List<JsonObject> buildRows(UpsertChunksCommand command) {
        List<JsonObject> rows = new ArrayList<>(command.chunks().size());
        for (VectorChunk chunk : command.chunks()) {
            JsonObject row = new JsonObject();
            row.addProperty("knowledge_base_id", command.knowledgeBaseId());
            row.addProperty("file_id", command.fileId());
            row.addProperty("chunk_id", chunk.chunkId());
            row.addProperty("chunk_index", chunk.chunkIndex());
            row.addProperty("embedding_source", chunk.embeddingSource());
            row.addProperty("content_hash", chunk.contentHash());
            if (milvusProperties.isBm25Enabled()) {
                row.addProperty(CONTENT_TEXT_FIELD, truncateContentText(chunk.content()));
            }
            row.add(VECTOR_FIELD, toJsonArray(chunk.vector()));
            rows.add(row);
        }
        return rows;
    }

    private String truncateContentText(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= CONTENT_TEXT_MAX_LENGTH) {
            return content;
        }
        log.warn("Milvus BM25 文本过长，已截断: originalLength={}, maxLength={}",
                content.length(), CONTENT_TEXT_MAX_LENGTH);
        return content.substring(0, CONTENT_TEXT_MAX_LENGTH);
    }

    private JsonArray toJsonArray(List<Float> vector) {
        validateVectorDimension(vector);
        JsonArray jsonArray = new JsonArray();
        for (Float value : vector) {
            jsonArray.add(value);
        }
        return jsonArray;
    }

    private void validateVectorDimension(List<Float> vector) {
        if (vector.size() != embeddingProperties.dimension()) {
            throw new IllegalStateException("Milvus 向量维度不匹配，期望="
                    + embeddingProperties.dimension() + "，实际=" + vector.size());
        }
    }

    private String buildKnowledgeBaseFilter(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.size() == 1) {
            return "knowledge_base_id == " + knowledgeBaseIds.get(0);
        }
        StringBuilder filterBuilder = new StringBuilder("knowledge_base_id in [");
        for (int index = 0; index < knowledgeBaseIds.size(); index++) {
            if (index > 0) {
                filterBuilder.append(", ");
            }
            filterBuilder.append(knowledgeBaseIds.get(index));
        }
        filterBuilder.append("]");
        return filterBuilder.toString();
    }

    private List<SearchHit> convertSearchHits(SearchResp searchResp) {
        List<SearchHit> hits = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
        if (searchResults == null || searchResults.isEmpty()) {
            log.info("Milvus 向量检索转换分支: searchResults 为空");
            return hits;
        }
        for (List<SearchResp.SearchResult> resultGroup : searchResults) {
            for (SearchResp.SearchResult result : resultGroup) {
                Map<String, Object> entity = result.getEntity();
                SearchHit hit = new SearchHit(
                        toLong(entity.get("knowledge_base_id")),
                        toLong(entity.get("file_id")),
                        toLong(entity.get("chunk_id")),
                        toInteger(entity.get("chunk_index")),
                        BigDecimal.valueOf(result.getScore().doubleValue())
                );
                hits.add(hit);
            }
        }
        return hits;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private void ensureCollection() {
        if (hasCollection()) {
            return;
        }
        log.info("Milvus collection 创建入参: database={}, collection={}, dimension={}",
                milvusProperties.database(), milvusProperties.collectionName(), embeddingProperties.dimension());
        CreateCollectionReq.CollectionSchema collectionSchema = milvusProperties.isBm25Enabled()
                ? buildHybridCollectionSchema()
                : buildDenseCollectionSchema();
        List<IndexParam> indexParams = milvusProperties.isBm25Enabled()
                ? buildHybridIndexParams()
                : List.of(buildDenseIndexParam());
        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                .databaseName(milvusProperties.database())
                .collectionName(milvusProperties.collectionName())
                .collectionSchema(collectionSchema)
                .indexParams(indexParams)
                .build();
        milvusClient.createCollection(createCollectionReq);
        log.info("Milvus collection 创建出参: database={}, collection={}, bm25Enabled={}",
                milvusProperties.database(), milvusProperties.collectionName(), milvusProperties.isBm25Enabled());
    }

    private CreateCollectionReq.CollectionSchema buildDenseCollectionSchema() {
        return CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(
                        CreateCollectionReq.FieldSchema.builder()
                                .name("id")
                                .dataType(DataType.Int64)
                                .isPrimaryKey(Boolean.TRUE)
                                .autoID(Boolean.TRUE)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("knowledge_base_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("file_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("chunk_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("chunk_index")
                                .dataType(DataType.Int32)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("embedding_source")
                                .dataType(DataType.VarChar)
                                .maxLength(64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("content_hash")
                                .dataType(DataType.VarChar)
                                .maxLength(128)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name(VECTOR_FIELD)
                                .dataType(DataType.FloatVector)
                                .dimension(embeddingProperties.dimension())
                                .build()
                ))
                .build();
    }

    private CreateCollectionReq.CollectionSchema buildHybridCollectionSchema() {
        return CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(
                        CreateCollectionReq.FieldSchema.builder()
                                .name("id")
                                .dataType(DataType.Int64)
                                .isPrimaryKey(Boolean.TRUE)
                                .autoID(Boolean.TRUE)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("knowledge_base_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("file_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("chunk_id")
                                .dataType(DataType.Int64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("chunk_index")
                                .dataType(DataType.Int32)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("embedding_source")
                                .dataType(DataType.VarChar)
                                .maxLength(64)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name("content_hash")
                                .dataType(DataType.VarChar)
                                .maxLength(128)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name(CONTENT_TEXT_FIELD)
                                .dataType(DataType.VarChar)
                                .maxLength(65535)
                                .enableAnalyzer(Boolean.TRUE)
                                .enableMatch(Boolean.TRUE)
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name(VECTOR_FIELD)
                                .dataType(DataType.FloatVector)
                                .dimension(embeddingProperties.dimension())
                                .build(),
                        CreateCollectionReq.FieldSchema.builder()
                                .name(SPARSE_VECTOR_FIELD)
                                .dataType(DataType.SparseFloatVector)
                                .build()
                ))
                .functionList(List.of(
                        CreateCollectionReq.Function.builder()
                                .name("bm25_content_text")
                                .functionType(FunctionType.BM25)
                                .inputFieldNames(List.of(CONTENT_TEXT_FIELD))
                                .outputFieldNames(List.of(SPARSE_VECTOR_FIELD))
                                .build()
                ))
                .build();
    }

    private IndexParam buildDenseIndexParam() {
        return IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexName("idx_vector")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
    }

    private List<IndexParam> buildHybridIndexParams() {
        IndexParam denseIndexParam = buildDenseIndexParam();
        IndexParam sparseIndexParam = IndexParam.builder()
                .fieldName(SPARSE_VECTOR_FIELD)
                .indexName("idx_sparse_vector")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();
        return List.of(denseIndexParam, sparseIndexParam);
    }

    private boolean hasCollection() {
        HasCollectionReq hasCollectionReq = HasCollectionReq.builder()
                .databaseName(milvusProperties.database())
                .collectionName(milvusProperties.collectionName())
                .build();
        return milvusClient.hasCollection(hasCollectionReq);
    }

    private MilvusClientV2 connectWithRetry() {
        log.info("Milvus client 连接入参: uri={}, database={}",
                milvusProperties.uri(), milvusProperties.database());
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(milvusProperties.uri())
                .dbName(milvusProperties.database())
                .build();
        MilvusClientV2 client = new MilvusClientV2(connectConfig);
        log.info("Milvus client 连接出参: uri={}, database={}",
                milvusProperties.uri(), milvusProperties.database());
        return client;
    }


}
