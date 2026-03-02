import { useState, useEffect, useRef } from 'react';
import { Message } from '../../types/chat';
import { ChevronDown, ChevronUp } from 'lucide-react';

interface UserMessageProps {
  message: Message;
  onImageClick: (src: string) => void;
}

export function UserMessage({ message, onImageClick }: UserMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isLargeContent, setIsLargeContent] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);
  const [hoveredImage, setHoveredImage] = useState<{ src: string, x: number, y: number } | null>(null);

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

  const renderContent = () => {
    if (message.blocks && message.blocks.length > 0) {
      return (
        <div className="whitespace-pre-wrap">
          {message.blocks.map((block, idx) => {
            if (block.type === 'image' && block.data) {
              const src = `data:${block.mimeType};base64,${block.data}`;
              return (
                <div 
                  key={idx} 
                  className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md border border-border
                    cursor-pointer align-middle mx-1 bg-background transition-all"
                  onClick={(e) => {
                    e.stopPropagation();
                    onImageClick(src);
                  }}
                  onMouseEnter={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    const parentRect = e.currentTarget.closest('.user-message-bubble')?.getBoundingClientRect();
                    if (parentRect) {
                      setHoveredImage({ src, x: rect.left - parentRect.left, y: rect.top - parentRect.top });
                    }
                  }}
                  onMouseLeave={() => setHoveredImage(null)}
                >
                  <img src={src} alt="" className="w-3 h-3 object-cover" />
                  <span className="text-xs font-medium opacity-90">Image</span>
                </div>
              );
            }
            return <span key={idx}>{(block as any).text || ''}</span>;
          })}
        </div>
      );
    }
    return <div className="whitespace-pre-wrap">{message.content}</div>;
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

          {/* Moved popover outside the clipped area, positioned relative to bubble */}
          {hoveredImage && (
            <div
              className="absolute z-50 pointer-events-none rounded shadow-2xl border border-border"
              style={{
                left: hoveredImage.x > 150 ? 'auto' : `${hoveredImage.x}px`,
                right: hoveredImage.x > 150 ? '0' : 'auto',
                bottom: `calc(100% - ${hoveredImage.y}px + 8px)`
              }}
            >
              <img
                src={hoveredImage.src}
                alt=""
                className="max-w-[250px] max-h-[250px] rounded-sm object-contain"
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
