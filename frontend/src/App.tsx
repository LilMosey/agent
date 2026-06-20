import { ConfigProvider, Tabs } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { KnowledgeBaseLayout } from './pages/KnowledgeBaseLayout';
import { ConversationPage } from './pages/ConversationPage';

export default function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <Tabs
        className="root-tabs"
        defaultActiveKey="knowledge-base"
        items={[
          {
            key: 'knowledge-base',
            label: '知识库管理',
            children: <KnowledgeBaseLayout />
          },
          {
            key: 'rag-query',
            label: 'RAG 查询',
            children: <ConversationPage />
          }
        ]}
      />
    </ConfigProvider>
  );
}
