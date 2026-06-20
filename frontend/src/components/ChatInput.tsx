import { Button, Input } from 'antd';
import { SendHorizontal } from 'lucide-react';
import { useState } from 'react';

interface ChatInputProps {
  sending: boolean;
  onSend: (content: string) => Promise<void>;
}

export function ChatInput({ sending, onSend }: ChatInputProps) {
  const [content, setContent] = useState('');

  async function handleSend() {
    const trimmedContent = content.trim();
    if (!trimmedContent) {
      return;
    }
    await onSend(trimmedContent);
    setContent('');
  }

  return (
    <div className="chat-input">
      <Input.TextArea
        value={content}
        onChange={(event) => setContent(event.target.value)}
        placeholder="输入问题，Shift + Enter 换行"
        autoSize={{ minRows: 2, maxRows: 5 }}
        disabled={sending}
        onPressEnter={(event) => {
          if (!event.shiftKey) {
            event.preventDefault();
            void handleSend();
          }
        }}
      />
      <Button type="primary" icon={<SendHorizontal size={16} />} loading={sending} onClick={() => void handleSend()}>
        发送
      </Button>
    </div>
  );
}
