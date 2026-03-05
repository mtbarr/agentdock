import { useState, useEffect } from 'react';
import { ChatTab, AgentOption, HistorySessionMeta } from '../types/chat';
import { ACPBridge } from '../utils/bridge';
import TabBar from '../components/chat/TabBar';
import ChatSessionView from '../components/chat/ChatSessionView';
import HistoryPanel from '../components/chat/HistoryPanel';

let tabCounter = 0;
function nextId(prefix: string): string {
  return `${prefix}-${++tabCounter}-${Date.now()}`;
}

const INITIAL_TAB_ID = nextId('tab');
const INITIAL_SESSION_ID = nextId('ses');

export function ChatView() {
  const [tabs, setTabs] = useState<ChatTab[]>([
    { id: INITIAL_TAB_ID, title: 'Untitled', sessionId: INITIAL_SESSION_ID }
  ]);
  const [activeTabId, setActiveTabId] = useState<string>(INITIAL_TAB_ID);
  const [showHistory, setShowHistory] = useState<boolean>(false);
  const [availableAgents, setAvailableAgents] = useState<AgentOption[]>([]);

  // Initialize Bridge and load cached agents
  useEffect(() => {
    ACPBridge.initialize();
    
    try {
      const cached = localStorage.getItem('unified-llm.adapters');
      if (cached) {
        const parsed = JSON.parse(cached) as AgentOption[];
        if (Array.isArray(parsed)) {
          setAvailableAgents(parsed.filter(a => a.downloaded && a.enabled));
        }
      }
    } catch (e) {
      console.warn('[ChatView] Failed to load cached adapters:', e);
    }
  }, []);

  // Single global listener for adapter updates
  useEffect(() => {
    return ACPBridge.onAdapters((e) => {
      const safeAdapters = Array.isArray(e.detail.adapters) ? e.detail.adapters : [];
      setAvailableAgents(safeAdapters.filter(a => a.downloaded && a.enabled));
      if (safeAdapters.length > 0) {
        try {
          localStorage.setItem('unified-llm.adapters', JSON.stringify(safeAdapters));
        } catch (e) {
          console.warn('[ChatView] Failed to cache adapters:', e);
        }
      }
    });
  }, []);

  const handleNewTab = (agentId?: string) => {
    const newId = nextId('tab');
    const newSessionId = nextId('ses');
    const title = 'Untitled';
    setTabs((prev) => [...prev, { id: newId, title, sessionId: newSessionId, agentId }]);
    setActiveTabId(newId);
    setShowHistory(false);
  };

  const handleCloseTab = (id: string) => {
    // Notify backend to stop the agent process for this chat
    const closingTab = tabs.find(t => t.id === id);
    if (closingTab && typeof window.__stopAgent === 'function') {
      try {
        window.__stopAgent(closingTab.sessionId);
      } catch (e) {
        console.warn('[ChatView] Failed to stop agent:', e);
      }
    }

    const newTabs = tabs.filter((t) => t.id !== id);
    
    if (newTabs.length === 0) {
      setTabs([]);
      setActiveTabId('');
      return;
    }

    setTabs(newTabs);

    if (activeTabId === id) {
      const currentIndex = tabs.findIndex(t => t.id === id);
      if (currentIndex > 0) {
        setActiveTabId(tabs[currentIndex - 1].id);
      } else if (tabs.length > 1) {
        setActiveTabId(tabs[currentIndex + 1].id);
      }
    }
  };

  const handleCloseAllTabs = () => {
    if (typeof window.__stopAgent === 'function') {
      tabs.forEach((tab) => {
        try {
          window.__stopAgent?.(tab.sessionId);
        } catch (e) {
          console.warn('[ChatView] Failed to stop agent:', e);
        }
      });
    }
    setTabs([]);
    setActiveTabId('');
    setShowHistory(false);
  };

  const handleOpenHistory = (item: HistorySessionMeta) => {
    const newId = nextId('tab');
    const newSessionId = nextId('ses');
    const title = item.title || 'Untitled';
    setTabs((prev) => [
      ...prev,
      {
        id: newId,
        title,
        sessionId: newSessionId,
        agentId: item.adapterName,
        historySession: item
      }
    ]);
    setActiveTabId(newId);
    setShowHistory(false);
  };

  return (
    <div className="flex flex-col h-full bg-background text-foreground overflow-hidden">
      <TabBar
        tabs={tabs}
        activeTabId={activeTabId}
        onSelectTab={(id) => {
          setActiveTabId(id);
          setShowHistory(false);
        }}
        onCloseTab={handleCloseTab}
        onCloseAllTabs={handleCloseAllTabs}
        onNewTab={() => handleNewTab()}
        onNewTabWithAgent={(agentId) => handleNewTab(agentId)}
        agents={availableAgents}
        showHistory={showHistory}
        onToggleHistory={() => setShowHistory(!showHistory)}
      />
      
      <div className="flex-1 relative min-h-0">
        {tabs.map((tab) => {
          const isTabActive = tab.id === activeTabId;
          const isVisible = !showHistory && isTabActive;
          
          return (
            <div 
              key={tab.id} 
              className={`absolute inset-0 w-full h-full bg-background ${isVisible ? 'z-10 visible' : 'z-0 invisible'}`}
            >
               <ChatSessionView 
                  initialAgentId={tab.agentId}
                  chatId={tab.sessionId}
                  historySession={tab.historySession}
                  availableAgents={availableAgents}
                  isActive={isTabActive}
                  onAgentChangeRequest={(agentId) => handleNewTab(agentId)}
               />
            </div>
          );
        })}

        {!showHistory && tabs.length === 0 && (
          <div className="absolute inset-0 w-full h-full z-10 bg-background flex items-center justify-center">
            <div className="flex flex-col items-center gap-4 max-w-[620px] px-6 text-center">
              {availableAgents.length === 0 ? (
                <>
                  <div className="text-ide-regular text-foreground/85">
                    No AI agents are currently available.
                  </div>
                  <div className="text-sm text-foreground/60">
                    Install at least one agent and sign in from the plugin Agent Management section.
                  </div>
                  <button
                    onClick={() => window.setView?.('management')}
                    className="px-4 py-2 rounded-md border border-border bg-background-secondary hover:bg-accent hover:text-accent-foreground transition-colors text-ide-regular"
                  >
                    Open Agent Management
                  </button>
                </>
              ) : (
                <>
                  <button
                    onClick={() => handleNewTab()}
                    className="px-4 py-2 rounded-md border border-border bg-background-secondary hover:bg-accent hover:text-accent-foreground transition-colors text-ide-regular"
                  >
                    Open new chat
                  </button>
                <div className="flex items-center gap-2 flex-wrap justify-center max-w-[520px]">
                  {availableAgents.map((agent) => (
                    <button
                      key={agent.id}
                      onClick={() => handleNewTab(agent.id)}
                      className="px-3 py-1.5 rounded-md border border-border bg-background-secondary hover:bg-accent hover:text-accent-foreground transition-colors text-xs flex items-center gap-2"
                    >
                      {agent.iconPath && <img src={agent.iconPath} className="w-3.5 h-3.5" alt="" />}
                      <span>{agent.displayName}</span>
                    </button>
                  ))}
                </div>
                </>
              )}
            </div>
          </div>
        )}

        {showHistory && (
            <div className="absolute inset-0 w-full h-full z-20 bg-background">
                <HistoryPanel onClose={() => setShowHistory(false)} availableAgents={availableAgents} onOpenSession={handleOpenHistory} />
            </div>
        )}
      </div>
    </div>
  );
}
