import ChatDropdown from './ChatDropdown';
import { DropdownOption } from '../../types/chat';

interface ChatInputProps {
  inputValue: string;
  onInputChange: (val: string) => void;
  onSend: () => void;
  onStop: () => void;
  isSending: boolean;
  status: string;
  
  agentOptions: DropdownOption[];
  selectedAgentId: string;
  onAgentChange: (id: string) => void;

  modelOptions: DropdownOption[];
  selectedModelId: string;
  onModelChange: (id: string) => void;

  modeOptions: DropdownOption[];
  selectedModeId: string;
  onModeChange: (id: string) => void;

  hasSelectedAgent: boolean;
}

export default function ChatInput({
  inputValue,
  onInputChange,
  onSend,
  onStop,
  isSending,
  status,
  agentOptions,
  selectedAgentId,
  onAgentChange,
  modelOptions,
  selectedModelId,
  onModelChange,
  modeOptions,
  selectedModeId,
  onModeChange,
  hasSelectedAgent
}: ChatInputProps) {
  return (
    <div className="flex-shrink-0 px-4 pb-4 pt-2">
      <div className="max-w-4xl mx-auto">
        <div className="bg-surface rounded-xl border border-border shadow-2xl focus-within:ring-1 focus-within:ring-ring transition-all">
          <textarea
            value={inputValue}
            onChange={(e) => onInputChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                onSend();
              }
            }}
            rows={3}
            placeholder="Type your task here"
            disabled={isSending}
            className="w-full p-4 bg-transparent border-0 outline-none resize-none text-[14px] text-foreground placeholder-foreground/30 disabled:opacity-50"
          />

          <div className="flex items-center justify-between px-3 py-2 border-t border-border">
            {/* Left Controls */}
            <div className="flex items-center gap-1">
              <button
                className="p-1.5 hover:bg-surface-hover rounded text-foreground/60 hover:text-foreground transition-colors"
                title="Add context"
              >
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
              </button>

              <div className="h-4 w-[1px] bg-border mx-1" />

              <ChatDropdown
                value={selectedAgentId}
                options={agentOptions}
                placeholder="Select Agent"
                disabled={isSending}
                onChange={onAgentChange}
              />

              {modeOptions.length > 0 && (
                <div className="h-4 w-[1px] bg-border mx-1" />
              )}
              {modeOptions.length > 0 && (
                <ChatDropdown
                  value={selectedModeId}
                  options={modeOptions}
                  placeholder="Mode"
                  header="Mode"
                  minWidthClass="min-w-[100px]"
                  disabled={isSending || !hasSelectedAgent}
                  onChange={onModeChange}
                />
              )}
            </div>

            {/* Right Controls */}
            <div className="flex items-center gap-3">
              <ChatDropdown
                value={selectedModelId}
                options={modelOptions}
                placeholder="Select Model"
                header="Model"
                disabled={isSending || !hasSelectedAgent}
                direction="up"
                onChange={onModelChange}
              />

              <div className="flex items-center gap-2">
                {status !== 'ready' && status !== 'not started' && (
                  <span className="text-[10px] uppercase tracking-tighter text-foreground/40 font-bold">
                    {status}
                  </span>
                )}
                {isSending ? (
                  <button
                    type="button"
                    onClick={onStop}
                    className="p-1.5 bg-transparent text-error hover:opacity-80 transition-all translate-y-[1px]"
                    title="Stop generating"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                    </svg>
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={onSend}
                    disabled={!inputValue.trim()}
                    className="p-1.5 bg-transparent text-foreground/60 hover:text-accent transition-all disabled:opacity-20 translate-y-[1px]"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="22" y1="2" x2="11" y2="13"></line>
                      <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
                    </svg>
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
