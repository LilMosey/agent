import { Input, Layout, message, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  createConversation,
  deleteConversation,
  listConversationMessages,
  listConversations,
  sendConversationMessage,
  updateConversationTitle
} from '../api/conversationApi';
import { toApiError } from '../api/client';
import { ChatInput } from '../components/ChatInput';
import { ChatMessageList } from '../components/ChatMessageList';
import { ConversationList } from '../components/ConversationList';
import type { Conversation, ConversationMessage, RetrievalReference } from '../types/domain';

const { Content } = Layout;

export function ConversationPage() {
  const [messageApi, contextHolder] = message.useMessage();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversationId, setSelectedConversationId] = useState<number>();
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [referencesByMessageId, setReferencesByMessageId] = useState<Record<number, RetrievalReference[]>>({});
  const [conversationLoading, setConversationLoading] = useState(false);
  const [messageLoading, setMessageLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [renamingConversation, setRenamingConversation] = useState<Conversation>();
  const [renameTitle, setRenameTitle] = useState('');
  const [renameSubmitting, setRenameSubmitting] = useState(false);

  useEffect(() => {
    void initialize();
  }, []);

  useEffect(() => {
    if (selectedConversationId) {
      void loadMessages(selectedConversationId);
    } else {
      setMessages([]);
      setReferencesByMessageId({});
    }
  }, [selectedConversationId]);

  async function initialize() {
    setConversationLoading(true);
    try {
      const data = await listConversations();
      if (data.length === 0) {
        const conversation = await createConversation();
        setConversations([conversation]);
        setSelectedConversationId(conversation.id);
      } else {
        setConversations(data);
        setSelectedConversationId(data[0].id);
      }
    } catch (error) {
      messageApi.error(toApiError(error).message);
    } finally {
      setConversationLoading(false);
    }
  }

  async function loadConversations(nextSelectedId?: number) {
    setConversationLoading(true);
    try {
      const data = await listConversations();
      setConversations(data);
      const nextSelectedExists = nextSelectedId ? data.some((conversation) => conversation.id === nextSelectedId) : false;
      const currentSelectedExists = selectedConversationId
        ? data.some((conversation) => conversation.id === selectedConversationId)
        : false;
      if (nextSelectedId && nextSelectedExists) {
        setSelectedConversationId(nextSelectedId);
      } else if (!selectedConversationId && data.length > 0) {
        setSelectedConversationId(data[0].id);
      } else if (!currentSelectedExists) {
        setSelectedConversationId(data[0]?.id);
      }
    } catch (error) {
      messageApi.error(toApiError(error).message);
    } finally {
      setConversationLoading(false);
    }
  }

  async function loadMessages(conversationId: number) {
    setMessageLoading(true);
    try {
      const data = await listConversationMessages(conversationId);
      setMessages(data);
      setReferencesByMessageId({});
    } catch (error) {
      messageApi.error(toApiError(error).message);
    } finally {
      setMessageLoading(false);
    }
  }

  async function handleCreateConversation() {
    try {
      const conversation = await createConversation();
      await loadConversations(conversation.id);
    } catch (error) {
      messageApi.error(toApiError(error).message);
    }
  }

  function openRenameModal(conversation: Conversation) {
    setRenamingConversation(conversation);
    setRenameTitle(conversation.title);
  }

  async function handleRenameConversation() {
    if (!renamingConversation) {
      return;
    }
    const normalizedTitle = renameTitle.trim();
    if (!normalizedTitle) {
      messageApi.warning('会话名称不能为空');
      return;
    }
    setRenameSubmitting(true);
    try {
      await updateConversationTitle(renamingConversation.id, normalizedTitle);
      messageApi.success('会话名称已修改');
      setRenamingConversation(undefined);
      setRenameTitle('');
      await loadConversations(renamingConversation.id);
    } catch (error) {
      messageApi.error(toApiError(error).message);
    } finally {
      setRenameSubmitting(false);
    }
  }

  async function handleDeleteConversation(conversation: Conversation) {
    try {
      await deleteConversation(conversation.id);
      messageApi.success('会话已删除');
      if (selectedConversationId === conversation.id) {
        setMessages([]);
        setReferencesByMessageId({});
        setSelectedConversationId(undefined);
      }
      await loadConversations();
    } catch (error) {
      messageApi.error(toApiError(error).message);
    }
  }

  async function handleSend(content: string) {
    if (!selectedConversationId) {
      messageApi.warning('请先创建会话');
      return;
    }
    const optimisticUserMessage: ConversationMessage = {
      id: -Date.now(),
      conversationId: selectedConversationId,
      role: 'USER',
      content,
      messageOrder: messages.length + 1,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    setMessages((currentMessages) => [...currentMessages, optimisticUserMessage]);
    setSending(true);
    try {
      const response = await sendConversationMessage(selectedConversationId, content);
      const assistantMessage: ConversationMessage = {
        id: response.messageId,
        conversationId: selectedConversationId,
        role: 'ASSISTANT',
        content: response.content,
        messageOrder: optimisticUserMessage.messageOrder + 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      };
      setMessages((currentMessages) => [...currentMessages, assistantMessage]);
      setReferencesByMessageId((currentReferences) => ({
        ...currentReferences,
        [response.messageId]: response.references
      }));
      await loadConversations(selectedConversationId);
    } catch (error) {
      setMessages((currentMessages) => currentMessages.filter((messageItem) => messageItem.id !== optimisticUserMessage.id));
      messageApi.error(toApiError(error).message);
    } finally {
      setSending(false);
    }
  }

  return (
    <Layout className="conversation-shell">
      {contextHolder}
      <ConversationList
        conversations={conversations}
        selectedConversationId={selectedConversationId}
        loading={conversationLoading}
        onCreate={() => void handleCreateConversation()}
        onSelect={setSelectedConversationId}
        onRename={openRenameModal}
        onDelete={(conversation) => void handleDeleteConversation(conversation)}
      />
      <Content className="conversation-content">
        <div className="conversation-header">
          <Typography.Title level={3}>RAG 查询</Typography.Title>
          <Typography.Text type="secondary">自动选择知识库，基于引用内容回答</Typography.Text>
        </div>
        <ChatMessageList messages={messages} loading={messageLoading} referencesByMessageId={referencesByMessageId} />
        <ChatInput sending={sending} onSend={handleSend} />
      </Content>
      <Modal
        title="修改会话名称"
        open={Boolean(renamingConversation)}
        okText="保存"
        cancelText="取消"
        confirmLoading={renameSubmitting}
        onOk={() => void handleRenameConversation()}
        onCancel={() => {
          setRenamingConversation(undefined);
          setRenameTitle('');
        }}
      >
        <Input
          value={renameTitle}
          maxLength={255}
          placeholder="请输入会话名称"
          onChange={(event) => setRenameTitle(event.target.value)}
          onPressEnter={() => void handleRenameConversation()}
        />
      </Modal>
    </Layout>
  );
}
