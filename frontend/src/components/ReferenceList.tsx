import { Collapse, Tag, Typography } from 'antd';
import type { RetrievalReference } from '../types/domain';

interface ReferenceListProps {
  references: RetrievalReference[];
}

export function ReferenceList({ references }: ReferenceListProps) {
  if (references.length === 0) {
    return null;
  }

  return (
    <div className="reference-list">
      <Collapse
        size="small"
        ghost
        items={[
          {
            key: 'references',
            label: `引用来源（${references.length}）`,
            children: (
              <div className="reference-items">
                {references.map((reference) => (
                  <div className="reference-item" key={`${reference.chunkId}-${reference.referenceNo}`}>
                    <div className="reference-title">
                      <Tag color="blue">引用 {reference.referenceNo}</Tag>
                      <Typography.Text strong>{reference.fileName}</Typography.Text>
                      <Typography.Text type="secondary">chunk {reference.chunkIndex}</Typography.Text>
                      <Typography.Text type="secondary">{Number(reference.score).toFixed(4)}</Typography.Text>
                    </div>
                    {reference.titlePath ? (
                      <Typography.Text className="reference-path" type="secondary">
                        {reference.titlePath}
                      </Typography.Text>
                    ) : null}
                    {reference.contentPreview ? (
                      <Typography.Paragraph className="reference-preview" ellipsis={{ rows: 2 }}>
                        {reference.contentPreview}
                      </Typography.Paragraph>
                    ) : null}
                  </div>
                ))}
              </div>
            )
          }
        ]}
      />
    </div>
  );
}
