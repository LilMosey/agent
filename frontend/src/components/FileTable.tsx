import { Button, Popconfirm, Space, Table, Tag, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { Download, Info, Trash2 } from 'lucide-react';
import type { ChunkStrategy, KnowledgeFile } from '../types/domain';
import { formatFileSize, statusColor } from './FileDetailDrawer';

interface FileTableProps {
  files: KnowledgeFile[];
  loading: boolean;
  onDetail: (file: KnowledgeFile) => void;
  onDownload: (file: KnowledgeFile) => void;
  onDelete: (file: KnowledgeFile) => void;
}

export function FileTable({ files, loading, onDetail, onDownload, onDelete }: FileTableProps) {
  const columns: ColumnsType<KnowledgeFile> = [
    {
      title: '文件名',
      dataIndex: 'originalFilename',
      width: 320,
      ellipsis: true,
      render: (filename: string) => (
        <Tooltip title={filename} placement="topLeft">
          <span className="file-name-cell">{filename}</span>
        </Tooltip>
      )
    },
    {
      title: '类型',
      dataIndex: 'fileType',
      width: 120
    },
    {
      title: '大小',
      dataIndex: 'fileSize',
      width: 120,
      render: (size: number) => formatFileSize(size)
    },
    {
      title: '状态',
      dataIndex: 'fileStatus',
      width: 140,
      render: (status: string) => <Tag color={statusColor(status)}>{status}</Tag>
    },
    {
      title: '分块方式',
      dataIndex: 'chunkStrategy',
      width: 120,
      render: (strategy: ChunkStrategy) => chunkStrategyLabel(strategy)
    },
    {
      title: '块大小',
      dataIndex: 'chunkSize',
      width: 100
    },
    {
      title: '重叠',
      dataIndex: 'chunkOverlap',
      width: 90
    },
    {
      title: '上传时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (createdAt: string) => dayjs(createdAt).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '操作',
      width: 180,
      render: (_, file) => (
        <Space size={4}>
          <Button type="text" icon={<Info size={16} />} onClick={() => onDetail(file)} />
          <Button type="text" icon={<Download size={16} />} onClick={() => onDownload(file)} />
          <Popconfirm
            title="删除文件"
            description={`确认删除“${file.originalFilename}”？`}
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => onDelete(file)}
          >
            <Button danger type="text" icon={<Trash2 size={16} />} />
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={files}
      loading={loading}
      pagination={false}
      scroll={{ x: 1360 }}
      locale={{ emptyText: '暂无文件' }}
    />
  );
}

function chunkStrategyLabel(strategy: ChunkStrategy): string {
  if (strategy === 'FIXED_SIZE') {
    return '固定大小';
  }
  if (strategy === 'SECTION') {
    return '按章节';
  }
  return '递归切分';
}
