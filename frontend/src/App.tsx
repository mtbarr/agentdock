import TabBar from './components/TabBar';
import { AppTabContent } from './components/AppTabContent';
import { EmptyStateView } from './components/EmptyStateView';
import ConfirmationModal from './components/ConfirmationModal';
import { useAppController } from './hooks/app/useAppController';

function App() {
  const {
    tabs,
    activeTabId,
    tabUi,
    availableAgents,
    runnableAgents,
    agentAvailabilityResolved,
    pendingAgentSwitch,
    pendingAgentName,
    pendingHandoffsByTab,
    handleSelectTab,
    handleReorderTabs,
    handleCloseTab,
    handleCloseAllTabs,
    handleNewTab,
    handleOpenHistory,
    openSingletonTab,
    handleAssistantActivity,
    handleAtBottomChange,
    handleCanMarkReadChange,
    handlePermissionRequestChange,
    requestAgentSwitch,
    handleHandoffConsumed,
    handleChatSessionStateChange,
    handleContinueInNewTab,
    handleContinueInCurrentConversation,
    handleCancelAgentSwitch,
  } = useAppController();

  return (
    <div className="h-screen bg-background text-foreground overflow-hidden flex flex-col">
      <TabBar
        tabs={tabs}
        activeTabId={activeTabId}
        tabUi={tabUi}
        onSelectTab={handleSelectTab}
        onReorderTabs={handleReorderTabs}
        onCloseTab={handleCloseTab}
        onCloseAllTabs={handleCloseAllTabs}
        onNewTab={() => handleNewTab()}
        onNewTabWithAgent={(agentId) => handleNewTab(agentId)}
        agents={availableAgents}
        onOpenHistory={() => openSingletonTab('history', 'History')}
        onOpenManagement={() => openSingletonTab('management', 'Service Providers')}
        onOpenDesignSystem={() => openSingletonTab('design', 'Design System')}
        onOpenMcp={() => openSingletonTab('mcp', 'MCP Servers')}
        onOpenPromptLibrary={() => openSingletonTab('prompt-library', 'Prompt Library')}
        onOpenSystemInstructions={() => openSingletonTab('system-instructions', 'System Instructions')}
        onOpenSettings={() => openSingletonTab('settings', 'Settings')}
      />

      <div className="flex-1 relative min-h-0">
        {/* All tabs -- keep mounted for state preservation, toggle visibility */}
        {tabs.map((tab) => {
          const isTabActive = tab.id === activeTabId;

          return (
            <AppTabContent
              key={tab.id}
              tab={tab}
              isActive={isTabActive}
              availableAgents={availableAgents}
              runnableAgents={runnableAgents}
              pendingHandoff={pendingHandoffsByTab[tab.id]}
              onOpenHistory={handleOpenHistory}
              onAssistantActivity={() => handleAssistantActivity(tab.id)}
              onAtBottomChange={(isAtBottom) => handleAtBottomChange(tab.id, isAtBottom)}
              onCanMarkReadChange={(canMarkRead) => handleCanMarkReadChange(tab.id, canMarkRead)}
              onPermissionRequestChange={(hasPendingPermission) => handlePermissionRequestChange(tab.id, hasPendingPermission)}
              onAgentChangeRequest={(payload) => requestAgentSwitch(tab.id, payload)}
              onHandoffConsumed={(handoffId) => handleHandoffConsumed(tab.id, handoffId)}
              onSessionStateChange={(state) => handleChatSessionStateChange(tab.id, state)}
            />
          );
        })}

        {/* Empty state */}
        {tabs.length === 0 && (
          <EmptyStateView
            availableAgents={availableAgents}
            runnableAgents={runnableAgents}
            adaptersResolved={agentAvailabilityResolved}
            onStartWithAgent={handleNewTab}
            onOpenRecentConversation={handleOpenHistory}
            onOpenHistory={() => openSingletonTab('history', 'History')}
            onOpenManagement={() => openSingletonTab('management', 'Service Providers')}
          />
        )}
      </div>

      <ConfirmationModal
        isOpen={pendingAgentSwitch !== null}
        title={`Switch to ${pendingAgentName}`}
        message={`Click "Continue" to pass the current chat context to ${pendingAgentName}.` + "\n" + `Click "Start New" to begin a new separate chat.`}
        confirmLabel="Continue"
        
        secondaryActionLabel="Start New"
        onSecondaryAction={handleContinueInNewTab}
        showCancelButton={false}
        onConfirm={handleContinueInCurrentConversation}
        onCancel={handleCancelAgentSwitch}
      />
    </div>
  );
}

export default App;
