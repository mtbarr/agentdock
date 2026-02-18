import { useState, useEffect, useRef } from 'react';

export interface AcpLogEntryPayload {
  direction: 'SENT' | 'RECEIVED';
  json: string;
  timestamp: number;
}

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

interface ModelOption {
  id: string;
  displayName: string;
}

interface AgentOption {
  id: string;
  displayName: string;
  isDefault: boolean;
  defaultModelId?: string;
  models?: ModelOption[];
}

interface DropdownOption {
  id: string;
  label: string;
}

declare global {
  interface Window {
    __startAgent?: (adapterId?: string, modelId?: string) => void;
    __setModel?: (modelId: string) => void;
    __sendPrompt?: (message: string) => void;
    __requestAdapters?: () => void;
    __notifyReady?: () => void;
    __onAcpLog?: (payload: AcpLogEntryPayload) => void;
    __onAgentText?: (text: string) => void;
    __onStatus?: (status: string) => void;
    __onSessionId?: (id: string) => void;
    __onAdapters?: (adapters: AgentOption[]) => void;
    __cancelPrompt?: () => void;
  }
}

function ChatDropdown({
  value,
  options,
  placeholder,
  disabled,
  minWidthClass,
  direction = 'up',
  header,
  onChange,
}: {
  value: string;
  options: DropdownOption[];
  placeholder: string;
  disabled: boolean;
  minWidthClass?: string;
  direction?: 'up' | 'down';
  header?: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const selectedOption = options.find((option) => option.id === value);
  const selectedLabel = selectedOption?.label || placeholder;

  useEffect(() => {
    const onPointerDown = (event: MouseEvent) => {
      if (!rootRef.current) return;
      if (!rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener('mousedown', onPointerDown);
    return () => window.removeEventListener('mousedown', onPointerDown);
  }, []);

  return (
    <div ref={rootRef} className={`relative inline-flex items-center ${minWidthClass}`}>
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen((prev) => !prev)}
        className="flex items-center gap-1.5 py-1 px-2 rounded hover:bg-surface-hover text-[13px] text-foreground/80 transition-colors disabled:opacity-50 disabled:cursor-not-allowed group"
      >
        <span className="truncate max-w-[150px]">{selectedLabel}</span>
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          className={`opacity-50 group-hover:opacity-100 transition-transform ${open ? 'rotate-180' : ''}`}
        >
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </button>

      {open && !disabled && (
        <div
          className={`absolute z-50 min-w-[220px] rounded-md border border-border bg-surface shadow-xl overflow-hidden py-1 ${
            direction === 'up' ? 'bottom-full mb-2 left-0' : 'top-full mt-2 left-0'
          }`}
        >
          {header && (
            <div className="px-3 py-1.5 text-[11px] font-medium text-foreground/40 border-b border-border/50 text-center uppercase tracking-wider">
              {header}
            </div>
          )}
          <div className="max-h-64 overflow-y-auto">
            {options.length > 0 ? (
              options.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  onClick={() => {
                    onChange(option.id);
                    setOpen(false);
                  }}
                  className={`flex items-center w-full px-3 py-1.5 text-left text-[13px] transition-colors hover:bg-surface-hover group ${
                    option.id === value ? 'bg-accent text-foreground font-medium' : 'text-foreground/80'
                  }`}
                >
                  <span className="w-5 flex-shrink-0">
                    {option.id === value && (
                      <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="text-accent-foreground">
                        <polyline points="20 6 9 17 4 12"></polyline>
                      </svg>
                    )}
                  </span>
                  <span className="truncate">{option.label}</span>
                </button>
              ))
            ) : (
              <div className="px-3 py-2 text-[13px] text-foreground/40 italic text-center">
                {placeholder}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export function ChatView() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [status, setStatus] = useState<string>('not started');
  const [isSending, setIsSending] = useState(false);
  const [availableAgents, setAvailableAgents] = useState<AgentOption[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string>('');
  const [selectedModelByAgent, setSelectedModelByAgent] = useState<Record<string, string>>({});
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const currentAgentMessageRef = useRef<string>('');
  const pendingMessageRef = useRef<string | null>(null);
  const startedAgentIdRef = useRef<string>('');
  const startedModelIdRef = useRef<string>('');

  const selectedAgent = availableAgents.find((agent) => agent.id === selectedAgentId);
  const availableModels = selectedAgent?.models ?? [];
  const selectedModelId = selectedAgent
    ? (selectedModelByAgent[selectedAgent.id] || selectedAgent.defaultModelId || availableModels[0]?.id || '')
    : '';

  const agentOptions: DropdownOption[] = availableAgents.map((agent) => ({ id: agent.id, label: agent.displayName }));
  const modelOptions: DropdownOption[] = availableModels.map((model) => ({ id: model.id, label: model.displayName }));

  useEffect(() => {
    let retryTimer: number | undefined;
    const requestAdapters = () => {
      if (typeof window.__requestAdapters === 'function') {
        window.__requestAdapters();
      }
    };
    try {
      const cached = localStorage.getItem('unified-llm.adapters');
      if (cached) {
        const parsed = JSON.parse(cached) as AgentOption[];
        if (Array.isArray(parsed) && parsed.length > 0) {
          setAvailableAgents(parsed);
          setSelectedAgentId((prev) => prev || parsed.find((agent) => agent.isDefault)?.id || parsed[0]?.id || '');
          const initialModelMap: Record<string, string> = {};
          parsed.forEach((agent) => {
            const firstModel = agent.models?.[0]?.id;
            const defaultModel = agent.defaultModelId || firstModel || '';
            if (defaultModel) initialModelMap[agent.id] = defaultModel;
          });
          setSelectedModelByAgent(initialModelMap);
        }
      }
    } catch {
    }

    window.__onAcpLog = (payload: AcpLogEntryPayload) => {
      try {
        const jsonObj = JSON.parse(payload.json);
        console.log(`[ACP ${payload.direction}]`, jsonObj);
      } catch (e) {
        console.log(`[ACP ${payload.direction}]`, payload.json);
      }
    };

    window.__onAgentText = (text: string) => {
      currentAgentMessageRef.current += text;
      if (currentAgentMessageRef.current.trim()) {
        setMessages((prev) => {
          const lastMsg = prev[prev.length - 1];
          if (lastMsg && lastMsg.role === 'assistant') {
            return [
              ...prev.slice(0, -1),
              { ...lastMsg, content: currentAgentMessageRef.current },
            ];
          }
          return prev;
        });
      }
    };

    window.__onStatus = (s: string) => {
      setStatus(s);
      if (s === 'ready') {
        const accumulatedText = currentAgentMessageRef.current.trim();
        if (accumulatedText) {
          setMessages((prev) => {
            const lastMsg = prev[prev.length - 1];
            if (lastMsg && lastMsg.role === 'assistant') {
              if (lastMsg.content !== accumulatedText) {
                return [
                  ...prev.slice(0, -1),
                  { ...lastMsg, content: accumulatedText },
                ];
              }
            }
            return prev;
          });
        }
        currentAgentMessageRef.current = '';
        if (!pendingMessageRef.current) {
          setIsSending(false);
        }
      }
      if (s === 'ready' && pendingMessageRef.current && typeof window.__sendPrompt === 'function') {
        const messageToSend = pendingMessageRef.current;
        pendingMessageRef.current = null;
        setIsSending(true);
        const userMessage: Message = {
          id: `msg-${Date.now()}-user`,
          role: 'user',
          content: messageToSend,
          timestamp: Date.now(),
        };
        setMessages((prev) => [...prev, userMessage]);
        currentAgentMessageRef.current = '';
        const assistantMessage: Message = {
          id: `msg-${Date.now()}-assistant`,
          role: 'assistant',
          content: '',
          timestamp: Date.now(),
        };
        setMessages((prev) => [...prev, assistantMessage]);
        try {
          window.__sendPrompt(messageToSend);
        } catch (e) {
          console.error('[ChatView] Failed to send pending prompt:', e);
          setIsSending(false);
        }
      }
    };

    window.__onSessionId = (id: string) => {
      console.debug('[ChatView] Session ID:', id);
    };

    window.__onAdapters = (adapters: AgentOption[]) => {
      const safeAdapters = Array.isArray(adapters) ? adapters : [];
      setAvailableAgents(safeAdapters);
      if (safeAdapters.length > 0) {
        try {
          localStorage.setItem('unified-llm.adapters', JSON.stringify(safeAdapters));
        } catch {}
      }
      setSelectedAgentId((prev) => {
        if (prev && safeAdapters.some((agent) => agent.id === prev)) return prev;
        const defaultAgent = safeAdapters.find((agent) => agent.isDefault);
        return defaultAgent?.id ?? safeAdapters[0]?.id ?? '';
      });
      setSelectedModelByAgent((prev) => {
        const next: Record<string, string> = { ...prev };
        safeAdapters.forEach((agent) => {
          if (next[agent.id]) return;
          const firstModel = agent.models?.[0]?.id;
          const defaultModel = agent.defaultModelId || firstModel || '';
          if (defaultModel) next[agent.id] = defaultModel;
        });
        Object.keys(next).forEach((agentId) => {
          if (!safeAdapters.some((agent) => agent.id === agentId)) {
            delete next[agentId];
          }
        });
        return next;
      });
    };

    if (typeof window.__notifyReady === 'function') {
      window.__notifyReady();
    }
    requestAdapters();
    retryTimer = window.setTimeout(requestAdapters, 450);

    return () => {
      window.__onAcpLog = undefined;
      window.__onAgentText = undefined;
      window.__onStatus = undefined;
      window.__onSessionId = undefined;
      window.__onAdapters = undefined;
      if (retryTimer) window.clearTimeout(retryTimer);
    };
  }, []);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (!selectedAgentId || !selectedModelId) return;
    if (status !== 'ready') return;
    if (startedAgentIdRef.current !== selectedAgentId) return;
    if (startedModelIdRef.current === selectedModelId) return;
    if (typeof window.__setModel !== 'function') return;
    try {
      window.__setModel(selectedModelId);
      startedModelIdRef.current = selectedModelId;
    } catch (e) {
      console.error('[ChatView] Failed to set model:', e);
    }
  }, [selectedAgentId, selectedModelId, status]);

  const handleSend = async () => {
    const text = inputValue.trim();
    if (!text || isSending) return;
    const selectedAgent = selectedAgentId || availableAgents.find((agent) => agent.isDefault)?.id || availableAgents[0]?.id || '';
    const needsRestart = Boolean(selectedAgent) && startedAgentIdRef.current !== selectedAgent;
    const needsStart = status !== 'ready' || needsRestart;

    if (needsStart) {
      if (typeof window.__startAgent !== 'function') return;
      setIsSending(true);
      setInputValue('');
      pendingMessageRef.current = text;
      try {
        startedAgentIdRef.current = selectedAgent;
        startedModelIdRef.current = selectedModelId || '';
        window.__startAgent(selectedAgent || undefined, selectedModelId || undefined);
        return;
      } catch (e) {
        setIsSending(false);
        pendingMessageRef.current = null;
        return;
      }
    }

    if (typeof window.__sendPrompt !== 'function') return;
    setIsSending(true);
    const userMessage: Message = {
      id: `msg-${Date.now()}-user`,
      role: 'user',
      content: text,
      timestamp: Date.now(),
    };
    setMessages((prev) => [...prev, userMessage]);
    setInputValue('');
    currentAgentMessageRef.current = '';
    const assistantMessage: Message = {
      id: `msg-${Date.now()}-assistant`,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    };
    setMessages((prev) => [...prev, assistantMessage]);
    try {
      window.__sendPrompt(text);
    } catch (e) {
      console.error('[ChatView] Failed to send prompt:', e);
      setIsSending(false);
    }
  };

  return (
    <div className="flex flex-col h-screen bg-background text-foreground">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-background">
        <span className="text-xs font-medium">UnifiedLLM</span>
        <div className="flex items-center gap-3">
          <button className="p-1 hover:bg-surface-hover rounded opacity-60 hover:opacity-100 transition-all">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
          </button>
          <button className="p-1 hover:bg-surface-hover rounded opacity-60 hover:opacity-100 transition-all">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
          </button>
          <button className="p-1 hover:bg-surface-hover rounded opacity-60 hover:opacity-100 transition-all">
             <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="1"></circle><circle cx="12" cy="5" r="1"></circle><circle cx="12" cy="19" r="1"></circle></svg>
          </button>
        </div>
      </div>

      {/* Chat Area */}
      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        <div className="max-w-3xl mx-auto">
          {messages.length === 0 && (
            <div className="mt-20 space-y-4 text-center">Hello, world!</div>
          )}

          {messages.map((message) => (
            <div key={message.id} className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'} mb-6`}>
              <div className={`max-w-[90%] rounded-lg p-3 ${
                message.role === 'user' ? 'bg-surface border border-border text-foreground' : 'text-foreground'
              }`}>
                <div className="text-[14px] leading-relaxed whitespace-pre-wrap break-words">
                  {message.content || (
                    <div className="flex gap-1 py-1">
                      <div className="w-1.5 h-1.5 bg-foreground/30 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                      <div className="w-1.5 h-1.5 bg-foreground/30 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                      <div className="w-1.5 h-1.5 bg-foreground/30 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>
      </div>

      {/* Input Area */}
      <div className="px-4 pb-4 pt-2">
        <div className="max-w-4xl mx-auto">
          <div className="bg-surface rounded-xl border border-border shadow-2xl focus-within:ring-1 focus-within:ring-ring transition-all">
            <textarea
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
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
                  onChange={(v) => setSelectedAgentId(v)}
                />
              </div>

              {/* Right Controls */}
              <div className="flex items-center gap-3">
                <ChatDropdown
                  value={selectedModelId}
                  options={modelOptions}
                  placeholder="Select Model"
                  header="Model"
                  disabled={isSending || !selectedAgent}
                  direction="up"
                  onChange={(mId) => {
                    setSelectedModelByAgent((prev) => (
                      selectedAgentId ? { ...prev, [selectedAgentId]: mId } : prev
                    ));
                  }}
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
                          onClick={() => {
                              if (typeof window.__cancelPrompt === 'function') {
                                  window.__cancelPrompt();
                                  setIsSending(false);
                              }
                          }}
                          className="p-1.5 bg-transparent text-red-500 hover:text-red-600 transition-all translate-y-[1px]"
                          title="Stop generating"
                      >
                          <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                          </svg>
                      </button>
                  ) : (
                      <button
                          type="button"
                          onClick={handleSend}
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
    </div>
  );
}
