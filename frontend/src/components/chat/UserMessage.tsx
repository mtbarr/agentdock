import { useState, useEffect, useRef, memo } from 'react';
import { Message, RichContentBlock, TextBlock, ImageBlock, FileBlock } from '../../types/chat';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { AttachmentItem } from './shared/AttachmentItem';

interface UserMessageProps {
  message: Message;
  onImageClick: (src: string) => void;
}

export const UserMessage = memo(({ message, onImageClick }: UserMessageProps) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isLargeContent, setIsLargeContent] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = contentRef.current;
    if (!el) return;

    const checkHeight = () => {
      if (el.scrollHeight > 300) {
        setIsLargeContent(true);
      } else {
        setIsLargeContent(false);
      }
    };

    const observer = new ResizeObserver(() => checkHeight());
    observer.observe(el);
    checkHeight();

    return () => observer.disconnect();
  }, [message.content, message.blocks]);

  const getBlocks = () => {
    const inline: RichContentBlock[] = [];
    const trailing: RichContentBlock[] = [];
    if (message.blocks) {
      message.blocks.forEach(b => {
        if (b.type === 'file' || (b.type === 'image' && (b as ImageBlock).isInline === false)) {
          trailing.push(b);
        } else {
          inline.push(b);
        }
      });
    }
    return { inline, trailing };
  };

  const { inline, trailing } = getBlocks();

  const renderContent = () => {
    if (inline.length > 0) {
      return (
        <div className="whitespace-pre-wrap">
          {inline.map((block, idx) => {
            if (block.type === 'image' && (block as ImageBlock).data) {
              const img = block as ImageBlock;
              return (
                <AttachmentItem
                  key={idx}
                  att={{
                    id: String(idx),
                    name: 'Image',
                    mimeType: img.mimeType,
                    data: img.data
                  }}
                  onImageClick={onImageClick}
                />
              );
            }
            return <span className="cursor-text" key={idx}>{block.type === 'text' ? (block as TextBlock).text : ''}</span>;
          })}
        </div>
      );
    }
    return <div className="whitespace-pre-wrap">{message.content}</div>;
  };

  const renderTrailingAttachments = () => {
    if (trailing.length === 0) return null;
    return (
      <div className="flex flex-wrap gap-2 mt-3 block w-full">
        {trailing.map((block, idx) => {
          if (block.type === 'file') {
            const fb = block as FileBlock;
            return (
              <AttachmentItem key={`trail-${idx}`}
                att={{ id: `trail-${idx}`, name: fb.name, mimeType: fb.mimeType, data: fb.data }}
                onImageClick={onImageClick}
              />
            );
          }
          const ib = block as ImageBlock;
          return (
            <AttachmentItem key={`trail-${idx}`}
              att={{ id: `trail-${idx}`, name: 'Image', mimeType: ib.mimeType, data: ib.data }}
              onImageClick={onImageClick}
            />
          );
        })}
      </div>
    );
  };

  const formattedDate = message.timestamp ? (() => {
    const d = new Date(message.timestamp);
    const datePart = d.toISOString().split('T')[0];
    const timePart = d.toTimeString().split(' ')[0].slice(0, 5);
    return `${datePart} ${timePart}`;
  })() : null;

  return (
    <div className="flex flex-col mb-4 animate-in fade-in slide-in-from-bottom-2 duration-300 mt-12">
      {formattedDate && (
        <div className="flex justify-end mb-1 px-1">
          <span className="text-ide-small text-foreground-secondary font-mono">
            {formattedDate}
          </span>
        </div>
      )}
      <div className="flex justify-end relative">
        <div
          className="user-message-bubble group max-w-[85%] bg-accent text-accent-foreground px-6 py-4 ml-auto
            shadow-sm rounded-lg relative transition-[max-height] duration-300 ease-in-out"
        >
          {isLargeContent && (
            <button
              onClick={() => setIsExpanded(!isExpanded)}
              className="absolute top-2 right-2 w-8 h-8 flex items-center justify-center rounded-full"
              title={isExpanded ? "Collapse text" : "Expand text"}
            >
              {isExpanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
            </button>
          )}

          <div
            ref={contentRef}
            className={`leading-relaxed break-words transition-[max-height] duration-500 ease-in-out 
              ${!isExpanded && isLargeContent ? 'max-h-[200px] overflow-hidden' : ''} 
              ${isLargeContent ? 'pr-8' : ''}`}
          >
            {renderContent()}
          </div>

          {!isExpanded && isLargeContent && (
            <div className="absolute bottom-0 left-0 right-0 h-16 bg-gradient-to-t from-accent to-transparent
              pointer-events-none z-10" />
          )}

          {renderTrailingAttachments()}
        </div>
      </div>
    </div>
  );
});
