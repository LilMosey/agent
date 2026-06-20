import { Button, Empty, List, Popconfirm, Tooltip, Typography } from 'antd';
import { Edit3, Plus, Trash2 } from 'lucide-react';
import type { Conversation } from '../types/domain';

interface ConversationListProps {
  conversations: Conversation[];
  selectedConversationId?: number;
  loading: boolean;
  onCreate: () => void;
  onSelect: (conversationId: number) => void;
  onRename: (conversation: Conversation) => void;
  onDelete: (conversation: Conversation) => void;
}

export function ConversationList({
  conversations,
  selectedConversationId,
  loading,
  onCreate,
  onSelect,
  onRename,
  onDelete
}: ConversationListProps) {
  return (
    <aside className="conversation-sider">
      <div className="conversation-sider-header">
        <Typography.Title level={4}>会话</Typography.Title>
        <Button type="primary" icon={<Plus size={16} />} onClick={onCreate}>
          新建
        </Button>
      </div>
      <List
        loading={loading}
        dataSource={conversations}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" /> }}
        renderItem={(conversation) => (
          <List.Item
            className={`conversation-item ${
              conversation.id === selectedConversationId ? 'conversation-item-active' : ''
            }`}
            onClick={() => onSelect(conversation.id)}
            actions={[
              <Tooltip title="重命名" key="rename">
                <Button
                  type="text"
                  size="small"
                  icon={<Edit3 size={15} />}
                  onClick={(event) => {
                    event.stopPropagation();
                    onRename(conversation);
                  }}
                />
              </Tooltip>,
              <Popconfirm
                title="删除会话"
                description="删除后不会在会话列表显示。"
                okText="删除"
                cancelText="取消"
                onConfirm={(event) => {
                  event?.stopPropagation();
                  onDelete(conversation);
                }}
                onCancel={(event) => event?.stopPropagation()}
                key="delete"
              >
                <Tooltip title="删除">
                  <Button
                    danger
                    type="text"
                    size="small"
                    icon={<Trash2 size={15} />}
                    onClick={(event) => event.stopPropagation()}
                  />
                </Tooltip>
              </Popconfirm>
            ]}
          >
            <Typography.Text strong ellipsis>
              {conversation.title}
            </Typography.Text>
          </List.Item>
        )}
      />
    </aside>
  );
}
