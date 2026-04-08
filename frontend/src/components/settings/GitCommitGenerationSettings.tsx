import { Bot, FileText, GitCommitHorizontal } from 'lucide-react';
import { AgentOption, GitCommitGenerationSettings as GitCommitGenerationSettingsValue, ModelOption } from '../../types/chat';
import { SettingsToggleCard } from './SettingsToggleCard';

interface GitCommitGenerationSettingsProps {
  settings: GitCommitGenerationSettingsValue;
  installedAgents: AgentOption[];
  onChange: (settings: GitCommitGenerationSettingsValue) => void;
}

function resolveModelId(agent: AgentOption | undefined, preferredModelId: string): string {
  const models = agent?.availableModels ?? [];
  if (models.length === 0) return '';
  if (models.some((model) => model.modelId === preferredModelId)) {
    return preferredModelId;
  }
  if (agent?.currentModelId && models.some((model) => model.modelId === agent.currentModelId)) {
    return agent.currentModelId;
  }
  return models[0]?.modelId ?? '';
}

function selectedModelValue(models: ModelOption[], modelId: string): string {
  if (models.some((model) => model.modelId === modelId)) {
    return modelId;
  }
  return models[0]?.modelId ?? '';
}

export function GitCommitGenerationSettings({
  settings,
  installedAgents,
  onChange,
}: GitCommitGenerationSettingsProps) {
  if (installedAgents.length === 0) {
    return null;
  }

  const fallbackAgent = installedAgents[0];
  const activeAgent = installedAgents.find((agent) => agent.id === settings.adapterId) ?? fallbackAgent;
  const models = activeAgent?.availableModels ?? [];
  const activeModelId = selectedModelValue(models, settings.modelId);

  const update = (next: Partial<GitCommitGenerationSettingsValue>) => {
    onChange({
      ...settings,
      ...next,
    });
  };

  const handleToggle = () => {
    if (settings.enabled) {
      update({ enabled: false });
      return;
    }

    update({
      enabled: true,
      adapterId: activeAgent?.id ?? '',
      modelId: resolveModelId(activeAgent, settings.modelId),
    });
  };

  const handleAgentChange = (adapterId: string) => {
    const nextAgent = installedAgents.find((agent) => agent.id === adapterId) ?? installedAgents[0];
    update({
      adapterId,
      modelId: resolveModelId(nextAgent, settings.modelId),
    });
  };

  return (
    <SettingsToggleCard
      icon={GitCommitHorizontal}
      title="Git Commit Generation"
      description="Use a downloaded AI agent to prepare commit message settings. The generation flow itself is not enabled yet."
      enabled={settings.enabled}
      onToggle={handleToggle}
      ariaLabel="Enable Git commit generation settings"
    >
      {settings.enabled && (
        <div className="mt-4 space-y-4 border-t border-border pt-4">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-[0.08em] text-foreground/55">
              <Bot size={13} />
              <span>AI Agent</span>
            </div>
            <select
              value={activeAgent?.id ?? ''}
              onChange={(event) => handleAgentChange(event.target.value)}
              className="w-full rounded-ide border border-border bg-background px-3 py-2 text-sm text-foreground outline-none transition-colors focus:border-primary"
            >
              {installedAgents.map((agent) => (
                <option key={agent.id} value={agent.id}>
                  {agent.name}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-[0.08em] text-foreground/55">
              <GitCommitHorizontal size={13} />
              <span>Model</span>
            </div>
            <select
              value={activeModelId}
              onChange={(event) => update({ modelId: event.target.value })}
              disabled={models.length === 0}
              className="w-full rounded-ide border border-border bg-background px-3 py-2 text-sm text-foreground outline-none transition-colors focus:border-primary disabled:cursor-not-allowed disabled:opacity-50"
            >
              {models.length === 0 ? (
                <option value="">No models available</option>
              ) : (
                models.map((model) => (
                  <option key={model.modelId} value={model.modelId}>
                    {model.name}
                  </option>
                ))
              )}
            </select>
          </div>

          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-[0.08em] text-foreground/55">
              <FileText size={13} />
              <span>Instructions</span>
            </div>
            <textarea
              value={settings.instructions}
              onChange={(event) => update({ instructions: event.target.value })}
              rows={5}
              placeholder="Describe how commit messages should be written."
              className="w-full resize-y rounded-ide border border-border bg-background px-3 py-2 text-sm text-foreground outline-none transition-colors placeholder:text-foreground/35 focus:border-primary"
            />
          </div>
        </div>
      )}
    </SettingsToggleCard>
  );
}
