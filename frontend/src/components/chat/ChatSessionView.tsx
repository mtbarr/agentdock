import { useCallback, useState } from 'react';
import { useChatSession } from '../../hooks/useChatSession';
import { useFileChanges } from '../../hooks/useFileChanges';
import { AgentOption, FileChangeSummary, HistorySessionMeta } from '../../types/chat';
import MessageList from './MessageList';
import ChatInput from './ChatInput';
import PermissionModal from './PermissionModal';
import FileChangesPanel from './FileChangesPanel';

interface ChatSessionProps {
  isActive: boolean;
  initialAgentId?: string;
  chatId: string;
  availableAgents: AgentOption[];
  historySession?: HistorySessionMeta;
  onAgentChangeRequest?: (agentId: string) => void;
}

export default function ChatSessionView({ 
  isActive, 
  initialAgentId, 
  chatId,
  availableAgents,
  historySession,
  onAgentChangeRequest
}: ChatSessionProps) {
  const {
    messages,
    inputValue,
    setInputValue,
    status,
    isSending,
    agentOptions,
    selectedAgentId,
    modelOptions,
    selectedModelId,
    handleModelChange,
    modeOptions,
    selectedModeId,
    handleModeChange,
    permissionRequest,
    handleSend,
    handleStop,
    handlePermissionDecision,
    hasSelectedAgent,
    attachments,
    setAttachments,
    acpSessionId,
    adapterName,
    adapterDisplayName
  } = useChatSession(chatId, availableAgents, initialAgentId, historySession);

  const {
    fileChanges,
    totalAdditions,
    totalDeletions,
    handleUndoFile,
    handleUndoAllFiles,
    handleKeepFile,
    handleKeepAll,
  } = useFileChanges(chatId, acpSessionId, adapterName);

  const handleShowDiff = useCallback((fc: FileChangeSummary) => {
    if (typeof window.__showDiff === 'function') {
      window.__showDiff(JSON.stringify({
        filePath: fc.filePath,
        status: fc.status,
        operations: fc.operations,
      }));
    }
  }, []);

  const handleOpenFile = useCallback((filePath: string) => {
    if (typeof window.__openFile === 'function') {
      window.__openFile(JSON.stringify({ filePath }));
    }
  }, []);

  const [selectedImage, setSelectedImage] = useState<string | null>(null);

  return (
    <div className="flex flex-col h-full relative">
      <MessageList 
        messages={messages} 
        onImageClick={setSelectedImage} 
        isSending={isSending}
        status={status}
        agentName={adapterDisplayName}
      />

      <FileChangesPanel
        fileChanges={fileChanges}
        totalAdditions={totalAdditions}
        totalDeletions={totalDeletions}
        onUndoFile={handleUndoFile}
        onUndoAllFiles={handleUndoAllFiles}
        onKeepFile={handleKeepFile}
        onKeepAll={handleKeepAll}
        onOpenFile={handleOpenFile}
        onShowDiff={handleShowDiff}
      />

      <ChatInput
        inputValue={inputValue}
        onInputChange={setInputValue}
        onSend={handleSend}
        onStop={handleStop}
        isSending={isSending}
        
        agentOptions={agentOptions}
        selectedAgentId={selectedAgentId}
        onAgentChange={(id) => {
          if (onAgentChangeRequest && id !== selectedAgentId) {
            onAgentChangeRequest(id);
          }
        }}
        
        modelOptions={modelOptions}
        selectedModelId={selectedModelId}
        onModelChange={handleModelChange}
        
        modeOptions={modeOptions}
        selectedModeId={selectedModeId}
        onModeChange={handleModeChange}
        
        hasSelectedAgent={hasSelectedAgent}
        attachments={attachments}
        onAttachmentsChange={setAttachments}
        onImageClick={setSelectedImage}
      />


      {permissionRequest && isActive && (
        <PermissionModal 
          request={permissionRequest} 
          onRespond={handlePermissionDecision} 
        />
      )}

      {/* Full-size Image Overlay */}
      {selectedImage && (
        <div 
          className="fixed inset-0 z-[100] bg-black/90 backdrop-blur-sm flex items-center justify-center p-8 animate-in fade-in duration-200 cursor-zoom-out"
          onClick={() => setSelectedImage(null)}
        >
          <img 
            src={selectedImage} 
            className="max-w-full max-h-full object-contain rounded-lg shadow-2xl animate-in zoom-in-95 duration-200" 
            alt="Pilna izmēra attēls"
          />
          <button 
            className="absolute top-4 right-4 p-2 bg-white/10 hover:bg-white/20 rounded-full text-white transition-colors"
            onClick={(e) => { e.stopPropagation(); setSelectedImage(null); }}
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
          </button>
        </div>
      )}
    </div>
  );
}
