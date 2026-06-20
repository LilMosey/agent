export type FileStatus = 'UPLOADED' | 'PENDING_PARSE' | 'PARSING' | 'PARSE_FAILED' | 'READY' | 'DISABLED';
export type FileType = 'WORD' | 'MARKDOWN' | 'TEXT' | 'PDF_RESERVED';
export type ChunkStrategy = 'FIXED_SIZE' | 'SECTION' | 'RECURSIVE';
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';
export type RagRouterAction = 'NO_KB' | 'SEARCH_KB' | 'REUSE_LAST_CONTEXT';
export type QueryIntent = 'FACT_QA' | 'SUMMARY' | 'FORMAT_CONVERT' | 'FOLLOW_UP' | 'CHAT';

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

export interface KnowledgeBase {
  id: number;
  name: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeFile {
  id: number;
  knowledgeBaseId: number;
  originalFilename: string;
  fileExt: string;
  contentType?: string;
  fileSize: number;
  checksumSha256: string;
  storageBucket: string;
  storageObjectKey: string;
  fileType: FileType;
  fileStatus: FileStatus;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
  parseError?: string;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeBasePayload {
  name: string;
  description?: string;
}

export interface Conversation {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string;
}

export interface ConversationMessage {
  id: number;
  conversationId: number;
  role: MessageRole;
  content: string;
  messageOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface RouterResult {
  action: RagRouterAction;
  knowledgeBaseIds: number[];
  queryIntent: QueryIntent;
  confidence: number;
  reason: string;
}

export interface RetrievalReference {
  referenceNo: number;
  knowledgeBaseId: number;
  fileId: number;
  fileName: string;
  chunkId: number;
  chunkIndex: number;
  titlePath?: string;
  score: number;
  contentPreview?: string;
}

export interface SendMessageResponse {
  messageId: number;
  content: string;
  router: RouterResult;
  references: RetrievalReference[];
}
