import { memo } from 'react';
import { Message } from '../../types/chat';
import { MarkdownMessage } from './MarkdownMessage';
import { ContentBlockRenderer } from './blocks/ContentBlockRenderer';

interface AssistantMessageProps {
  message: Message;
  onImageClick: (src: string) => void;
  showBorder: boolean;
}

export const AssistantMessage = memo(({ message, onImageClick, showBorder }: AssistantMessageProps) => {
  const renderContent = () => {
    // 1. Check for contentBlocks (new structured format)
    if (message.contentBlocks && message.contentBlocks.length > 0) {
      return (
        <div className="flex flex-col">
          {message.contentBlocks.map((block, idx) => (
            <ContentBlockRenderer key={idx} block={block} />
          ))}
        </div>
      );
    }

    // 2. Check for traditional blocks
    if (message.blocks && message.blocks.length > 0) {
      return (
        <div className="space-y-2">
          {message.blocks.map((block, idx) => {
            if (block.type === 'image' && block.data) {
              const src = `data:${block.mimeType || 'image/png'};base64,${block.data}`;
              return (
                <div key={idx} className="my-2">
                  <img 
                    src={src} 
                    alt=""
                    className="max-w-full rounded-md shadow-sm cursor-zoom-in hover:opacity-90 transition-opacity" 
                    style={{ maxHeight: '300px' }}
                    onClick={() => onImageClick(src)}
                  />
                </div>
              );
            }
            return (
              <MarkdownMessage key={idx} content={(block as any).text || ''} />
            );
          })}
        </div>
      );
    }

    // 3. Fallback to plain content
    return (
      <div className="">
        {message.content ? (
          <MarkdownMessage content={message.content} />
        ) : null}
      </div>
    );
  };

  const showMeta = message.duration || message.agentName;

  return (
    <div className="animate-in fade-in slide-in-from-bottom-2 duration-300">
      <div className={`flex justify-start mb-2`}>
        <div className="w-full text-foreground">
          <div className="leading-relaxed break-words">
            {renderContent()}
          </div>
        </div>
      </div>
      
      <div className={(showMeta || showBorder) ? "mt-8" : ""}>
        {showMeta && (
          <div className="flex justify-end items-center gap-2 mb-2 text-ide-small text-foreground-secondary font-mono">
            {message.duration !== undefined && (
              <span className="px-1.5 py-0.5">
                {message.duration < 60 
                  ? `${message.duration.toFixed(1)}s` 
                  : `${Math.floor(message.duration / 60)}:${(message.duration % 60).toFixed(0).padStart(2, '0')}`}
              </span>
            )}
            <div className="flex items-center gap-1.5 opacity-80">
              {message.agentName && <span>{message.agentName}</span>}
              {(message.modelName || message.modeName) && <span className="opacity-40">•</span>}
              {message.modelName && <span>{message.modelName}</span>}
              {message.modeName && <span className="opacity-40">•</span>}
              {message.modeName && <span>{message.modeName}</span>}
            </div>
          </div>
        )}

        {showBorder && (<div className="border-b border-border -mx-8" />)}
      </div>
    </div>
  );
});
