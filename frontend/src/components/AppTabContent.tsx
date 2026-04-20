import { AgentOption, ChatTab, PendingHandoffContext } from '../types/chat';
import { AgentManagementView } from './AgentManagement';
import { DesignSystemView } from './DesignSystem';
import HistoryPanel from './HistoryPanel';
import { McpServersView } from './McpServersView';
import { PromptLibraryView } from './PromptLibraryView';
import { SettingsView } from './SettingsView';
import { SystemInstructionsView } from './SystemInstructionsView';
import ChatSessionView from './chat/ChatSessionView';

interface AppTabContentProps {
  tab: ChatTab;
  isActive: boolean;
  availableAgents: AgentOption[];
  runnableAgents: AgentOption[];
  pendingHandoff?: PendingHandoffContext;
  onOpenHistory: Parameters<typeof HistoryPanel>[0]['onOpenSession'];
  onAssistantActivity: () => void;
  onAtBottomChange: (isAtBottom: boolean) => void;
  onCanMarkReadChange: (canMarkRead: boolean) => void;
  onPermissionRequestChange: (hasPendingPermission: boolean) => void;
  onAgentChangeRequest: Parameters<typeof ChatSessionView>[0]['onAgentChangeRequest'];
  onHandoffConsumed: (handoffId: string) => void;
  onSessionStateChange: Parameters<typeof ChatSessionView>[0]['onSessionStateChange'];
}

export function AppTabContent({
  tab,
  isActive,
  availableAgents,
  runnableAgents,
  pendingHandoff,
  onOpenHistory,
  onAssistantActivity,
  onAtBottomChange,
  onCanMarkReadChange,
  onPermissionRequestChange,
  onAgentChangeRequest,
  onHandoffConsumed,
  onSessionStateChange,
}: AppTabContentProps) {
  return (
    <div className={`absolute inset-0 w-full h-full bg-background ${isActive ? 'z-10 visible' : 'z-0 invisible'}`}>
      {tab.type === 'chat' && (
        <ChatSessionView
          initialAgentId={tab.agentId}
          conversationId={tab.conversationId}
          historySession={tab.historySession}
          pendingHandoff={pendingHandoff}
          availableAgents={runnableAgents}
          isActive={isActive}
          onAssistantActivity={onAssistantActivity}
          onAtBottomChange={onAtBottomChange}
          onCanMarkReadChange={onCanMarkReadChange}
          onPermissionRequestChange={onPermissionRequestChange}
          onAgentChangeRequest={onAgentChangeRequest}
          onHandoffConsumed={onHandoffConsumed}
          onSessionStateChange={onSessionStateChange}
        />
      )}
      {tab.type !== 'chat' && (
        <>
          {tab.type === 'management' && <AgentManagementView initialAgents={availableAgents} isActive={isActive} />}
          {tab.type === 'design' && <DesignSystemView />}
          {tab.type === 'history' && (
            <HistoryPanel availableAgents={availableAgents} onOpenSession={onOpenHistory} />
          )}
          {tab.type === 'mcp' && <McpServersView />}
          {tab.type === 'prompt-library' && <PromptLibraryView />}
          {tab.type === 'system-instructions' && <SystemInstructionsView />}
          {tab.type === 'settings' && <SettingsView />}
        </>
      )}
    </div>
  );
}
