import { useEffect, useRef, memo } from 'react';
import { Message } from '../../types/chat';
import { UserMessage } from './UserMessage';
import { AssistantMessage } from './AssistantMessage';

import { ChatLoadingIndicator } from './ChatLoadingIndicator';

function MessageList({ 
  messages,
  onImageClick,
  isSending,
  status,
  agentName
}: { 
  messages: Message[],
  onImageClick: (src: string) => void,
  isSending?: boolean,
  status?: string,
  agentName?: string
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const shouldAutoScroll = useRef(true);

  const handleScroll = () => {
    const el = containerRef.current;
    if (!el) return;
    const threshold = 100;
    shouldAutoScroll.current = el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
  };

  useEffect(() => {
    if (shouldAutoScroll.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  return (
    <div
      ref={containerRef}
      onScroll={handleScroll}
      className="flex-1 min-h-0 overflow-y-auto p-8 space-y-6 scroll-smooth"
    >
      <div className="max-w-4xl mx-auto">

        {messages.map((message, index) => {
          const isAssistant = message.role === 'assistant';
          const isLast = index === messages.length - 1;

          if (isAssistant) {
            return (
              <AssistantMessage 
                key={message.id} 
                message={message} 
                onImageClick={onImageClick} 
                showBorder={!isLast}
              />
            );
          }

          return (
            <UserMessage 
              key={message.id} 
              message={message} 
              onImageClick={onImageClick} 
            />
          );
        })}

        {isSending && (
          <div className="flex justify-start mb-8">
            <ChatLoadingIndicator status={status} agentName={agentName} />
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>
    </div>
  );
}

export default memo(MessageList);
