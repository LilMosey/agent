import { ConfigProvider, Tabs } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { KnowledgeBaseLayout } from './pages/KnowledgeBaseLayout';
import { ConversationPage } from './pages/ConversationPage';

export default function App() {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#0f8f7b',
          colorInfo: '#0f8f7b',
          colorSuccess: '#1f9d69',
          colorWarning: '#b76e00',
          colorError: '#c2413a',
          colorTextBase: '#18211f',
          colorBgBase: '#f7f8fa',
          borderRadius: 8,
          fontFamily: '"Inter", "PingFang SC", "Microsoft YaHei", Arial, sans-serif'
        },
        components: {
          Button: {
            controlHeight: 38,
            borderRadius: 8
          },
          Input: {
            controlHeight: 38,
            borderRadius: 8
          },
          Select: {
            controlHeight: 38,
            borderRadius: 8
          },
          Table: {
            headerBg: '#ece8dd',
            headerColor: '#27322f',
            rowHoverBg: '#f7f5ee'
          },
          Tabs: {
            inkBarColor: '#0f8f7b',
            itemSelectedColor: '#0f8f7b',
            itemHoverColor: '#0f8f7b'
          }
        }
      }}
    >
      <div className="product-frame">
        <div className="product-topbar">
          <div className="product-name">Enterprise KB</div>
          <div className="product-subtitle">知识库管理与 RAG 对话</div>
        </div>
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
              label: 'RAG 对话',
              children: <ConversationPage />
            }
          ]}
        />
      </div>
    </ConfigProvider>
  );
}
