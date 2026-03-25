import { useEffect, useMemo, useState } from 'react';
import { ACPBridge } from '../utils/bridge';
import type { AgentOption, HistorySessionMeta } from '../types/chat';
import ConfirmationModal from './ConfirmationModal';
import { RefreshCw, Trash2, Search, ChevronDown, Pencil, Check, X, Terminal } from 'lucide-react';

interface HistoryPanelProps {
  availableAgents: AgentOption[];
  onOpenSession: (session: HistorySessionMeta) => void;
}

function getItemAgents(item: HistorySessionMeta): string[] {
  return item.allAdapterNames && item.allAdapterNames.length > 0
    ? item.allAdapterNames
    : [item.adapterName];
}

export default function HistoryPanel({ availableAgents, onOpenSession }: HistoryPanelProps) {
  const [historyList, setHistoryList] = useState<HistorySessionMeta[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [selectedConversationIds, setSelectedConversationIds] = useState<string[]>([]);
  const [pendingDeleteIds, setPendingDeleteIds] = useState<string[]>([]);
  const [deleteProjectPath, setDeleteProjectPath] = useState<string>('');
  const [selectedAgents, setSelectedAgents] = useState<string[]>([]);
  const [isFilterOpen, setIsFilterOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteErrors, setDeleteErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    const unsubHistory = ACPBridge.onHistoryList((e) => {
      const list = Array.isArray(e.detail.list) ? e.detail.list : [];
      setHistoryList(list);
      setSelectedConversationIds((prev) => prev.filter((id) => list.some((item) => item.conversationId === id)));
      setDeleteErrors((prev) => Object.fromEntries(
        Object.entries(prev).filter(([conversationId]) => list.some((item) => item.conversationId === conversationId))
      ));
      setIsLoading(false);
      setIsDeleting(false);
    });

    const unsubDeleteResult = ACPBridge.onHistoryDeleteResult((e) => {
      const result = e.detail.result;
      const failures = Array.isArray(result.failures) ? result.failures : [];
      setDeleteErrors((prev) => {
        const next = { ...prev };
        (result.requestedConversationIds || []).forEach((conversationId) => {
          delete next[conversationId];
        });
        failures.forEach((failure) => {
          if (failure?.conversationId && failure?.message) {
            next[failure.conversationId] = failure.message;
          }
        });
        return next;
      });
      setIsDeleting(false);
    });

    ACPBridge.requestHistoryList();
    const intervalId = window.setInterval(() => {
      ACPBridge.requestHistoryList();
    }, 30_000);

    return () => {
      window.clearInterval(intervalId);
      unsubDeleteResult();
      unsubHistory();
    };
  }, []);

  const adapterDisplay = useMemo(() => {
    const map = new Map<string, AgentOption>();
    availableAgents.forEach((a) => map.set(a.id, a));
    return map;
  }, [availableAgents]);

  const uniqueAgentsInHistory = useMemo(() => {
    const agentIds = new Set<string>();
    historyList.forEach((item) => {
      getItemAgents(item).forEach((agentId) => agentIds.add(agentId));
    });
    return Array.from(agentIds);
  }, [historyList]);

  const filteredHistoryList = useMemo(() => {
    if (selectedAgents.length === 0) return historyList;
    return historyList.filter((item) => {
      return getItemAgents(item).some((a) => selectedAgents.includes(a));
    });
  }, [historyList, selectedAgents]);

  const selectedCount = selectedConversationIds.length;

  const formatDate = (ms: number) => {
    const d = new Date(ms);
    const now = new Date();
    const isToday = d.getDate() === now.getDate() &&
      d.getMonth() === now.getMonth() &&
      d.getFullYear() === now.getFullYear();

    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    const isYesterday = d.getDate() === yesterday.getDate() &&
      d.getMonth() === yesterday.getMonth() &&
      d.getFullYear() === yesterday.getFullYear();

    const hours = String(d.getHours()).padStart(2, '0');
    const minutes = String(d.getMinutes()).padStart(2, '0');
    const timeStr = `${hours}:${minutes}`;

    if (isToday) return `Today ${timeStr}`;
    if (isYesterday) return `Yesterday ${timeStr}`;
    
    const day = String(d.getDate()).padStart(2, '0');
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const year = d.getFullYear();
    return `${day}/${month}/${year} ${timeStr}`;
  };

  const formatConversationLength = (promptCount?: number) => {
    if (promptCount == null || promptCount <= 0) return null;
    return `${promptCount} prompts`;
  };

  const toggleSelection = (conversationId: string) => {
    setSelectedConversationIds((prev) =>
      prev.includes(conversationId) ? prev.filter((id) => id !== conversationId) : [...prev, conversationId]
    );
  };


  const confirmDelete = () => {
    if (pendingDeleteIds.length === 0 || !deleteProjectPath) return;
    const deleteIds = [...pendingDeleteIds];
    setIsDeleting(true);
    setDeleteErrors((prev) => {
      const next = { ...prev };
      deleteIds.forEach((conversationId) => {
        delete next[conversationId];
      });
      return next;
    });
    ACPBridge.deleteHistoryConversations(deleteProjectPath, deleteIds);
    setPendingDeleteIds([]);
    setDeleteProjectPath('');
    setSelectedConversationIds((prev) => prev.filter((id) => !deleteIds.includes(id)));
  };

  const refreshHistory = () => {
    setIsLoading(true);
    setDeleteErrors({});
    ACPBridge.syncHistoryList();
  };

  const openDeleteConfirmation = (items: HistorySessionMeta[]) => {
    if (items.length === 0) return;
    setPendingDeleteIds(items.map((item) => item.conversationId));
    setDeleteProjectPath(items[0].projectPath);
  };

  const startEditing = (item: HistorySessionMeta, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingId(item.conversationId);
    setEditTitle(item.title);
  };

  const submitRename = (projectPath: string, conversationId: string) => {
    if (!editTitle.trim()) {
      setEditingId(null);
      return;
    }
    
    // Optimistic update
    setHistoryList(prev => prev.map(item => 
      item.conversationId === conversationId ? { ...item, title: editTitle.trim() } : item
    ));
    
    ACPBridge.renameHistoryConversation(projectPath, conversationId, editTitle.trim());
    setEditingId(null);
  };

  const handleEditKeyDown = (e: React.KeyboardEvent, projectPath: string, conversationId: string) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      submitRename(projectPath, conversationId);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      setEditingId(null);
    }
  };

  return (
    <div className="flex flex-col h-full bg-background text-foreground z-10 w-full overflow-hidden relative">
      <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0 min-h-12 relative z-20">
        <div className="flex items-center gap-3">
          <div className="relative">
            <button 
              onClick={() => setIsFilterOpen(!isFilterOpen)}
              className={`flex items-center gap-1.5 px-2 py-1.5 rounded transition-colors ${
                isFilterOpen || selectedAgents.length > 0
                  ? 'bg-background-secondary text-foreground' 
                  : 'bg-transparent text-foreground-secondary hover:text-foreground'
              }`}
              title="Filter by Agent"
            >
              <Search size={16} />
              <span className="text-sm font-medium pr-1">Filter Agents</span>
              <ChevronDown size={14} className="opacity-70" />
            </button>
            
            {isFilterOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setIsFilterOpen(false)} />
                <div className="absolute top-full left-0 mt-1 w-48 bg-background border border-border rounded shadow-lg py-1 z-50">
                  <button 
                    className={`w-full text-left px-3 py-2 text-sm transition-colors ${selectedAgents.length === 0 ? 'bg-accent text-accent-foreground' : 'text-foreground hover:bg-background-secondary'}`}
                    onClick={() => { setSelectedAgents([]); setIsFilterOpen(false); }}
                  >
                    All Agents
                  </button>
                  {uniqueAgentsInHistory.map(agentId => {
                    const agent = adapterDisplay.get(agentId);
                    const label = agent?.name || agentId;
                    const isSelected = selectedAgents.includes(agentId);
                    return (
                      <button 
                        key={agentId}
                        className={`w-full flex justify-between items-center text-left px-3 py-2 text-sm transition-colors ${isSelected ? 'bg-accent text-accent-foreground' : 'text-foreground hover:bg-background-secondary'}`}
                        onClick={() => { setSelectedAgents([agentId]); setIsFilterOpen(false); }}
                      >
                        {label}
                      </button>
                    )
                  })}
                </div>
              </>
            )}
          </div>
          
          <span className="text-sm text-foreground-secondary font-medium pl-1">
            {filteredHistoryList.length} conversation{filteredHistoryList.length !== 1 ? 's' : ''}
          </span>
        </div>
        
        <div className="flex items-center gap-3 shrink-0">
          {selectedCount > 0 && (
            <>
              <button
                onClick={() => openDeleteConfirmation(historyList.filter((item) => selectedConversationIds.includes(item.conversationId)))}
                className="px-3 py-1.5 text-xs rounded-sm bg-[#3574F0] text-white hover:bg-[#2B5DD0] font-medium transition-colors"
                title="Delete Selected Conversations"
              >
                Delete Selected ({selectedCount})
              </button>
              <button
                onClick={() => setSelectedConversationIds([])}
                className="px-3 py-1.5 text-xs rounded-sm text-link hover:underline transition-colors"
                title="Clear Selection"
              >
                Clear
              </button>
            </>
          )}
          <button
            onClick={refreshHistory}
            disabled={isLoading}
            className={`p-1 text-foreground-secondary hover:text-foreground transition-colors ${isLoading ? 'animate-spin' : ''}`}
            title="Refresh History"
          >
            <RefreshCw className="w-4 h-4" />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto w-full p-2 space-y-1 mt-1">
        {isLoading ? (
          <div className="flex justify-center p-8 text-foreground">Loading history...</div>
        ) : filteredHistoryList.length === 0 ? (
          <div className="flex flex-col items-center justify-center p-8 text-foreground h-full">
            <p className="text-sm italic">No history available yet.</p>
          </div>
        ) : (
          filteredHistoryList.map((item, index) => {
            const conversationId = item.conversationId;
            const isSelected = selectedConversationIds.includes(conversationId);
            const conversationLength = formatConversationLength(item.promptCount);
            const deleteError = deleteErrors[conversationId];
            
            const itemAgents = getItemAgents(item);
            
            const otherAgents = itemAgents.filter(a => a !== item.adapterName);

            const mainAgent = adapterDisplay.get(item.adapterName);
            const mainLabel = mainAgent?.name || item.adapterName;
            const canOpenCli = !!mainAgent?.cliAvailable;
            const isLast = index === filteredHistoryList.length - 1;

            return (
              <div
                key={conversationId}
                className={`group relative transition-colors ${!isLast ? 'border-b border-border' : ''} ${isSelected ? 'bg-background-secondary' : ''}`}
                onClick={() => { if (editingId !== conversationId) onOpenSession(item); }}
              >
                <div className="flex items-start gap-3 p-3 pr-4 cursor-pointer">
                  <div className="flex flex-col items-center shrink-0 w-[42px] min-w-[42px] gap-1.5 pt-0.5">
                    {mainAgent?.iconPath ? (
                      <img src={mainAgent.iconPath} alt={mainLabel} className="h-9 w-9 object-contain opacity-80" />
                    ) : (
                      <div className="flex items-center justify-center rounded bg-background border border-border font-bold uppercase shrink-0 h-9 w-9 text-base">
                        {mainLabel.slice(0, 1)}
                      </div>
                    )}
                    
                    {otherAgents.length > 0 && (
                      <div className="flex flex-wrap items-center justify-center gap-0.5 w-full">
                        {otherAgents.map((agentId, idx) => {
                          const adapter = adapterDisplay.get(agentId);
                          const iconLabel = adapter?.name || agentId;
                          if (adapter?.iconPath) {
                            return (
                              <img 
                                key={idx} 
                                src={adapter.iconPath} 
                                alt={iconLabel} 
                                className="h-4 w-4 object-contain opacity-70 group-hover:opacity-100 transition-opacity" 
                                title={iconLabel} 
                              />
                            );
                          }
                          return (
                             <div key={idx} className="flex h-4 min-w-4 items-center justify-center rounded bg-background border border-border text-[9px] font-bold uppercase shrink-0 opacity-70 group-hover:opacity-100" title={iconLabel}>
                               {iconLabel.slice(0, 1)}
                             </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                  
                  <div className="min-w-0 flex-1 flex flex-col justify-center py-0.5">
                    {editingId === conversationId ? (
                      <div className="flex items-center gap-1 w-full" onClick={(e) => e.stopPropagation()}>
                        <input
                          type="text"
                          autoFocus
                          value={editTitle}
                          onChange={(e) => setEditTitle(e.target.value)}
                          onKeyDown={(e) => handleEditKeyDown(e, item.projectPath, conversationId)}
                          className="font-semibold text-sm bg-background border border-primary text-foreground outline-none px-1 py-0.5 rounded -ml-1 flex-1 min-w-0"
                        />
                        <button 
                          onClick={(e) => { e.stopPropagation(); submitRename(item.projectPath, conversationId); }}
                          className="p-1 text-foreground-secondary hover:text-primary transition-colors bg-background rounded border border-border"
                          title="Save"
                        >
                          <Check size={14} />
                        </button>
                        <button 
                          onClick={(e) => { e.stopPropagation(); setEditingId(null); }}
                          className="p-1 text-foreground-secondary hover:text-error transition-colors bg-background rounded border border-border"
                          title="Cancel"
                        >
                          <X size={14} />
                        </button>
                      </div>
                    ) : (
                      <div className="font-semibold text-sm truncate">{item.title}</div>
                    )}
                    <div className="mt-1 flex items-center gap-2 text-xs text-foreground-secondary group-hover:text-accent-foreground/80 transition-colors">
                      <span>{formatDate(item.updatedAt)}</span>
                      {conversationLength || item.modelId ? (
                        <span className="opacity-50">&bull;</span>
                      ) : null}
                      {conversationLength ? <span>{conversationLength}</span> : null}
                      {item.modelId ? <span>{item.modelId}</span> : null}
                    </div>
                    {deleteError ? (
                      <div className="mt-1 text-xs text-error">
                        {deleteError}
                      </div>
                    ) : null}
                  </div>

                  <div 
                    className="flex items-center gap-2 shrink-0 pt-1 relative z-10"
                    onClick={(e) => e.stopPropagation()}
                  >
                    {canOpenCli && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          ACPBridge.openHistoryConversationCli(item.projectPath, conversationId);
                        }}
                        className="p-1 text-foreground-secondary hover:text-primary opacity-0 group-hover:opacity-100 transition-opacity"
                        title="Open Conversation in CLI"
                      >
                        <Terminal className="w-4 h-4" />
                      </button>
                    )}
                    <button
                      onClick={(e) => startEditing(item, e)}
                      className="p-1 text-foreground-secondary hover:text-primary opacity-0 group-hover:opacity-100 transition-opacity"
                      title="Rename Conversation"
                    >
                      <Pencil className="w-4 h-4" />
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        openDeleteConfirmation([item]);
                      }}
                      className="p-1 text-foreground-secondary hover:text-error opacity-0 group-hover:opacity-100 transition-opacity"
                      title="Delete Conversation"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                    
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggleSelection(conversationId)}
                      onClick={(e) => e.stopPropagation()}
                      className={`h-3.5 w-3.5 transition-opacity cursor-pointer shrink-0 accent-primary ${isSelected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                      aria-label={`Select ${item.title}`}
                    />
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      <ConfirmationModal
        isOpen={pendingDeleteIds.length > 0}
        title={pendingDeleteIds.length > 1 ? 'Delete Conversations' : 'Delete Conversation'}
        message={
          pendingDeleteIds.length > 1
            ? `Are you sure you want to delete ${pendingDeleteIds.length} conversations? This action cannot be undone.`
            : `Are you sure you want to delete this conversation? This action cannot be undone.`
        }
        onConfirm={confirmDelete}
        confirmLabel="Delete"
        onCancel={() => {
          setPendingDeleteIds([]);
          setDeleteProjectPath('');
        }}
      />

      {(pendingDeleteIds.length > 0 || isDeleting) && (
        <div className="absolute inset-0 z-[90] flex items-center justify-center bg-black/40 transition-all duration-200">
          {isDeleting && (
            <div className="flex flex-col items-center gap-3 bg-[#2b2d30] border border-[#3b3b3b] p-5 rounded-lg shadow-xl text-[#DFDFDF]">
              <RefreshCw className="w-6 h-6 animate-spin text-[#3574F0]" />
              <span className="text-sm font-medium leading-none mt-1">Deleting...</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
