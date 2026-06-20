import { Empty, Spin, Typography } from 'antd';
import type { ConversationMessage, RetrievalReference } from '../types/domain';
import { ReferenceList } from './ReferenceList';

interface ChatMessageListProps {
  messages: ConversationMessage[];
  loading: boolean;
  referencesByMessageId: Record<number, RetrievalReference[]>;
}

export function ChatMessageList({ messages, loading, referencesByMessageId }: ChatMessageListProps) {
  if (loading) {
    return (
      <div className="chat-empty">
        <Spin />
      </div>
    );
  }

  if (messages.length === 0) {
    return (
      <div className="chat-empty">
        <Empty description="开始提问企业知识库" />
      </div>
    );
  }

  return (
    <div className="chat-message-list">
      {messages.map((message) => {
        const isUser = message.role === 'USER';
        return (
          <div className={`chat-message-row ${isUser ? 'chat-message-row-user' : 'chat-message-row-assistant'}`} key={message.id}>
            <div className={`chat-message-bubble ${isUser ? 'chat-message-bubble-user' : 'chat-message-bubble-assistant'}`}>
              <Typography.Paragraph className="chat-message-content">{message.content}</Typography.Paragraph>
              {!isUser ? <ReferenceList references={referencesByMessageId[message.id] || []} /> : null}
            </div>
          </div>
        );
      })}
    </div>
  );
}
