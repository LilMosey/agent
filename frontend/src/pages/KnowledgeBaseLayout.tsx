import { Button, Empty, Input, Layout, List, message, Popconfirm, Select, Typography, Upload } from 'antd';
import type { UploadProps } from 'antd';
import { Edit3, Plus, Search, Trash2, UploadCloud } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  updateKnowledgeBase
} from '../api/knowledgeBaseApi';
import { toApiError } from '../api/client';
import { deleteFile, downloadFile, listFiles, uploadFile } from '../api/knowledgeFileApi';
import { FileDetailDrawer } from '../components/FileDetailDrawer';
import { FileTable } from '../components/FileTable';
import { KnowledgeBaseModal } from '../components/KnowledgeBaseModal';
import type { FileStatus, KnowledgeBase, KnowledgeBasePayload, KnowledgeFile } from '../types/domain';

const { Header, Sider, Content } = Layout;

const allowedExtensions = ['docx', 'md', 'markdown', 'txt'];

export function KnowledgeBaseLayout() {
  const [messageApi, contextHolder] = message.useMessage();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKnowledgeBaseId, setSelectedKnowledgeBaseId] = useState<number>();
  const [files, setFiles] = useState<KnowledgeFile[]>([]);
  const [knowledgeBaseLoading, setKnowledgeBaseLoading] = useState(false);
  const [fileLoading, setFileLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingKnowledgeBase, setEditingKnowledgeBase] = useState<KnowledgeBase>();
  const [modalSubmitting, setModalSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<FileStatus | ''>('');
  const [detailFile, setDetailFile] = useState<KnowledgeFile>();

  const selectedKnowledgeBase = useMemo(
    () => knowledgeBases.find((knowledgeBase) => knowledgeBase.id === selectedKnowledgeBaseId),
    [knowledgeBases, selectedKnowledgeBaseId]
  );

  useEffect(() => {
    void loadKnowledgeBases();
  }, []);

  useEffect(() => {
    if (selectedKnowledgeBaseId) {
      void loadFiles(selectedKnowledgeBaseId, keyword, status);
    } else {
      setFiles([]);
    }
  }, [selectedKnowledgeBaseId, keyword, status]);

  async function loadKnowledgeBases() {
    setKnowledgeBaseLoading(true);
    try {
      const data = await listKnowledgeBases();
      setKnowledgeBases(data);
      const selectedExists = selectedKnowledgeBaseId
        ? data.some((knowledgeBase) => knowledgeBase.id === selectedKnowledgeBaseId)
        : false;
      if (!selectedExists && data.length > 0) {
        setSelectedKnowledgeBaseId(data[0].id);
      } else if (!selectedExists) {
        setSelectedKnowledgeBaseId(undefined);
      }
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    } finally {
      setKnowledgeBaseLoading(false);
    }
  }

  async function loadFiles(knowledgeBaseId: number, nextKeyword: string, nextStatus: FileStatus | '') {
    setFileLoading(true);
    try {
      const data = await listFiles(knowledgeBaseId, {
        keyword: nextKeyword || undefined,
        status: nextStatus,
        page: 1,
        size: 50
      });
      setFiles(data);
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    } finally {
      setFileLoading(false);
    }
  }

  async function handleSaveKnowledgeBase(payload: KnowledgeBasePayload) {
    setModalSubmitting(true);
    try {
      if (editingKnowledgeBase) {
        await updateKnowledgeBase(editingKnowledgeBase.id, payload);
        messageApi.success('知识库已更新');
      } else {
        await createKnowledgeBase(payload);
        messageApi.success('知识库已创建');
      }
      setModalOpen(false);
      setEditingKnowledgeBase(undefined);
      await loadKnowledgeBases();
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    } finally {
      setModalSubmitting(false);
    }
  }

  async function confirmDeleteKnowledgeBase(knowledgeBase: KnowledgeBase) {
    try {
      console.info('删除知识库请求开始', knowledgeBase);
      await deleteKnowledgeBase(knowledgeBase.id);
      console.info('删除知识库请求成功', knowledgeBase);
      messageApi.success('知识库已删除');
      const nextKnowledgeBases = knowledgeBases.filter((item) => item.id !== knowledgeBase.id);
      setKnowledgeBases(nextKnowledgeBases);
      if (selectedKnowledgeBaseId === knowledgeBase.id) {
        setSelectedKnowledgeBaseId(nextKnowledgeBases[0]?.id);
      }
      await loadKnowledgeBases();
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    }
  }

  const uploadProps: UploadProps = {
    showUploadList: false,
    maxCount: 1,
    beforeUpload: (file) => {
      void handleUpload(file);
      return false;
    }
  };

  async function handleUpload(file: File) {
    if (!selectedKnowledgeBaseId) {
      messageApi.warning('请先选择知识库');
      return;
    }
    if (!isAllowedFile(file.name)) {
      messageApi.error(toUnsupportedFileMessage(file.name));
      return;
    }
    try {
      await uploadFile(selectedKnowledgeBaseId, file);
      messageApi.success('文件已上传');
      await loadFiles(selectedKnowledgeBaseId, keyword, status);
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    }
  }

  async function handleDownload(file: KnowledgeFile) {
    if (!selectedKnowledgeBaseId) {
      return;
    }
    try {
      await downloadFile(selectedKnowledgeBaseId, file);
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    }
  }

  async function handleDeleteFile(file: KnowledgeFile) {
    if (!selectedKnowledgeBaseId) {
      return;
    }
    const knowledgeBaseId = selectedKnowledgeBaseId;
    try {
      console.info('删除文件请求开始', { knowledgeBaseId, file });
      await deleteFile(knowledgeBaseId, file.id);
      console.info('删除文件请求成功', { knowledgeBaseId, file });
      messageApi.success('文件已删除');
      setFiles((current) => current.filter((item) => item.id !== file.id));
      if (detailFile?.id === file.id) {
        setDetailFile(undefined);
      }
      await loadFiles(knowledgeBaseId, keyword, status);
    } catch (error) {
      messageApi.error(toErrorMessage(error));
    }
  }

  return (
    <Layout className="app-shell">
      {contextHolder}
      <Sider width={300} className="kb-sider">
        <div className="kb-sider-header">
          <Typography.Title level={4}>知识库</Typography.Title>
          <Button
            type="primary"
            icon={<Plus size={16} />}
            onClick={() => {
              setEditingKnowledgeBase(undefined);
              setModalOpen(true);
            }}
          >
            新建
          </Button>
        </div>
        <List
          loading={knowledgeBaseLoading}
          dataSource={knowledgeBases}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无知识库" /> }}
          renderItem={(knowledgeBase) => (
            <List.Item
              className={knowledgeBase.id === selectedKnowledgeBaseId ? 'kb-item kb-item-active' : 'kb-item'}
              onClick={() => setSelectedKnowledgeBaseId(knowledgeBase.id)}
              actions={[
                <Button
                  key="edit"
                  type="text"
                  icon={<Edit3 size={15} />}
                  onClick={(event) => {
                    event.stopPropagation();
                    setEditingKnowledgeBase(knowledgeBase);
                    setModalOpen(true);
                  }}
                />,
                <Popconfirm
                  key="delete-confirm"
                  title="删除知识库"
                  description={`确认删除“${knowledgeBase.name}”？`}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={(event) => {
                    event?.stopPropagation();
                    void confirmDeleteKnowledgeBase(knowledgeBase);
                  }}
                  onCancel={(event) => event?.stopPropagation()}
                >
                  <Button
                    danger
                    type="text"
                    icon={<Trash2 size={15} />}
                    onClick={(event) => event.stopPropagation()}
                  />
                </Popconfirm>
              ]}
            >
              <List.Item.Meta title={knowledgeBase.name} description={knowledgeBase.description || '无描述'} />
            </List.Item>
          )}
        />
      </Sider>
      <Layout>
        <Header className="app-header">
          <div>
            <Typography.Title level={3}>{selectedKnowledgeBase?.name || '请选择知识库'}</Typography.Title>
            <Typography.Text type="secondary">{selectedKnowledgeBase?.description || '创建或选择知识库后管理文件'}</Typography.Text>
          </div>
          <Upload {...uploadProps} disabled={!selectedKnowledgeBaseId}>
            <Button type="primary" icon={<UploadCloud size={16} />} disabled={!selectedKnowledgeBaseId}>
              上传文件
            </Button>
          </Upload>
        </Header>
        <Content className="app-content">
          {selectedKnowledgeBase ? (
            <>
              <div className="toolbar">
                <Input
                  allowClear
                  prefix={<Search size={16} />}
                  placeholder="搜索文件名"
                  value={keyword}
                  onChange={(event) => setKeyword(event.target.value)}
                />
                <Select<FileStatus | ''>
                  value={status}
                  onChange={setStatus}
                  className="status-select"
                  options={[
                    { label: '全部状态', value: '' },
                    { label: 'UPLOADED', value: 'UPLOADED' },
                    { label: 'PENDING_PARSE', value: 'PENDING_PARSE' },
                    { label: 'PARSING', value: 'PARSING' },
                    { label: 'PARSE_FAILED', value: 'PARSE_FAILED' },
                    { label: 'READY', value: 'READY' },
                    { label: 'DISABLED', value: 'DISABLED' }
                  ]}
                />
              </div>
              <FileTable
                files={files}
                loading={fileLoading}
                onDetail={setDetailFile}
                onDownload={handleDownload}
                onDelete={(file) => void handleDeleteFile(file)}
              />
            </>
          ) : (
            <div className="empty-panel">
              <Empty description="暂无知识库，请先创建知识库" />
            </div>
          )}
        </Content>
      </Layout>
      <KnowledgeBaseModal
        open={modalOpen}
        knowledgeBase={editingKnowledgeBase}
        submitting={modalSubmitting}
        onCancel={() => {
          setModalOpen(false);
          setEditingKnowledgeBase(undefined);
        }}
        onSubmit={handleSaveKnowledgeBase}
      />
      <FileDetailDrawer open={Boolean(detailFile)} file={detailFile} onClose={() => setDetailFile(undefined)} />
    </Layout>
  );
}

function isAllowedFile(filename: string): boolean {
  const segments = filename.toLowerCase().split('.');
  const extension = segments.length > 1 ? segments[segments.length - 1] : '';
  return allowedExtensions.includes(extension);
}

function toUnsupportedFileMessage(filename: string): string {
  const lowerName = filename.toLowerCase();
  if (lowerName.endsWith('.pdf')) {
    return '当前版本不支持上传 PDF 文件。';
  }
  if (lowerName.endsWith('.doc')) {
    return '当前版本仅支持 DOCX，不支持旧版 DOC 文件，请另存为 DOCX 后上传。';
  }
  return '仅支持上传 DOCX、Markdown、TXT 文件。';
}

function toErrorMessage(error: unknown): string {
  return toApiError(error).message;
}
