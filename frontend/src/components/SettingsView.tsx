import { useEffect, useRef, useState } from 'react';
import { Bell, LoaderCircle, Mic, Palette, Settings2, Type, Waypoints } from 'lucide-react';
import { AgentOption, AudioTranscriptionFeatureState, AudioTranscriptionSettings, GitCommitGenerationSettings as GitCommitGenerationSettingsValue, GlobalSettingsPayload } from '../types/chat';
import { ACPBridge } from '../utils/bridge';
import ConfirmationModal from './ConfirmationModal';
import { GitCommitGenerationSettings } from './settings/GitCommitGenerationSettings';
import { SettingsCardShell } from './settings/SettingsCardShell';
import { SettingsSelectCard } from './settings/SettingsSelectCard';
import { SettingsToggleCard } from './settings/SettingsToggleCard';

const emptyState: AudioTranscriptionFeatureState = {
  id: 'whisper-transcription',
  title: 'Whisper',
  installed: false,
  installing: false,
  supported: false,
  status: 'Loading',
  detail: '',
  installPath: '',
};

const defaultGlobalSettings: GlobalSettingsPayload = {
  settings: {
    useWslForAcpAdapters: false,
    wslDistributionName: '',
    audioNotificationsEnabled: true,
    uiFontSizeOffsetPx: 0,
    userMessageBackgroundStyle: 'accent',
    audioTranscription: { language: 'auto' },
    gitCommitGeneration: { enabled: false, adapterId: '', modelId: '', instructions: '' },
  },
  host: { hostOs: 'other', wslSupported: false, wslDistributions: [], uiFontSizeBasePx: 14 },
};

function normalizeGitCommitGenerationSettings(payload: Partial<GitCommitGenerationSettingsValue> | undefined): GitCommitGenerationSettingsValue {
  return {
    enabled: Boolean(payload?.enabled),
    adapterId: payload?.adapterId?.trim() ?? '',
    modelId: payload?.modelId?.trim() ?? '',
    instructions: payload?.instructions ?? '',
  };
}

function normalizeGlobalSettings(payload: Partial<GlobalSettingsPayload> | undefined): GlobalSettingsPayload {
  const wslDistributions = Array.isArray(payload?.host?.wslDistributions)
    ? payload!.host!.wslDistributions.filter((item): item is { name: string } => Boolean(item?.name))
    : [];
  const requestedDistribution = payload?.settings?.wslDistributionName?.trim() ?? '';
  const selectedDistribution = wslDistributions.some((item) => item.name === requestedDistribution)
    ? requestedDistribution
    : (wslDistributions[0]?.name ?? requestedDistribution);
  const uiFontSizeBasePx = Number.isFinite(payload?.host?.uiFontSizeBasePx)
    ? Math.round(payload!.host!.uiFontSizeBasePx)
    : 14;
  const uiFontSizeOffsetPx = Number.isFinite(payload?.settings?.uiFontSizeOffsetPx)
    ? Math.max(-3, Math.min(3, Math.round(payload!.settings!.uiFontSizeOffsetPx)))
    : 0;
  return {
    settings: {
      useWslForAcpAdapters: Boolean(payload?.settings?.useWslForAcpAdapters),
      wslDistributionName: selectedDistribution,
      audioNotificationsEnabled: payload?.settings?.audioNotificationsEnabled ?? true,
      uiFontSizeOffsetPx,
      userMessageBackgroundStyle: userMessageBackgroundOptions.some((option) => option.id === payload?.settings?.userMessageBackgroundStyle)
        ? payload!.settings!.userMessageBackgroundStyle
        : 'accent',
      audioTranscription: payload?.settings?.audioTranscription ?? { language: 'auto' },
      gitCommitGeneration: normalizeGitCommitGenerationSettings(payload?.settings?.gitCommitGeneration),
    },
    host: {
      hostOs: payload?.host?.hostOs === 'windows' ? 'windows' : 'other',
      wslSupported: Boolean(payload?.host?.wslSupported),
      wslDistributions,
      uiFontSizeBasePx,
    },
  };
}

function uiFontSizeCssValue(offsetPx: number): string {
  return offsetPx === 0
    ? 'var(--ide-font-size-base)'
    : `calc(var(--ide-font-size-base) + ${offsetPx}px)`;
}

const userMessageBackgroundOptions: Array<{
  id: GlobalSettingsPayload['settings']['userMessageBackgroundStyle'];
  background: string;
}> = [
  { id: 'background-secondary', background: 'var(--ide-background-secondary)' },
  { id: 'primary', background: 'var(--ide-Button-default-startBackground)' },
  { id: 'secondary', background: 'var(--ide-Button-startBackground)' },
  { id: 'accent', background: 'var(--ide-List-selectionBackground)' },
  { id: 'input', background: 'var(--ide-TextField-background)' },
  { id: 'editor-bg', background: 'var(--ide-editor-bg)' },
];

function applyUserMessageTheme(styleId: GlobalSettingsPayload['settings']['userMessageBackgroundStyle']) {
  const selected = userMessageBackgroundOptions.find((option) => option.id === styleId) ?? userMessageBackgroundOptions[3];
  document.documentElement.style.setProperty('--user-message-bg', selected.background);
}

export function SettingsView() {
  const [feature, setFeature] = useState<AudioTranscriptionFeatureState>(emptyState);
  const [settings, setSettings] = useState<AudioTranscriptionSettings>({ language: 'auto' });
  const [globalSettings, setGlobalSettings] = useState<GlobalSettingsPayload>(defaultGlobalSettings);
  const [installedAgents, setInstalledAgents] = useState<AgentOption[]>([]);
  const pendingWslSaveRef = useRef(false);
  const [pendingWslTarget, setPendingWslTarget] = useState<boolean | null>(null);
  const [switchInProgress, setSwitchInProgress] = useState(false);
  const hasWslDistributions = globalSettings.host.wslDistributions.length > 0;
  const uiFontSizeOptions = Array.from({ length: 7 }, (_, index) => {
    const offset = index - 3;
    const px = globalSettings.host.uiFontSizeBasePx + offset;
    return {
      offset,
      label: offset === 0 ? `${px}px (default)` : `${px}px`,
    };
  });

  useEffect(() => {
    document.documentElement.style.setProperty('--ide-font-size', uiFontSizeCssValue(globalSettings.settings.uiFontSizeOffsetPx));
  }, [globalSettings.settings.uiFontSizeOffsetPx]);

  useEffect(() => {
    applyUserMessageTheme(globalSettings.settings.userMessageBackgroundStyle);
  }, [globalSettings.settings.userMessageBackgroundStyle]);

  useEffect(() => {
    const requestSettings = () => {
      ACPBridge.loadAudioTranscriptionFeature();
      ACPBridge.loadAudioTranscriptionSettings();
      ACPBridge.loadGlobalSettings();
      ACPBridge.requestAdapters();
    };

    const cleanupFeature = ACPBridge.onAudioTranscriptionFeature((e) => {
      setFeature(e.detail.state);
    });
    const cleanupSettings = ACPBridge.onAudioTranscriptionSettings((e) => {
      setSettings(e.detail.settings);
    });
    const cleanupGlobalSettings = ACPBridge.onGlobalSettings((e) => {
      setGlobalSettings(normalizeGlobalSettings(e.detail?.payload));
      if (pendingWslSaveRef.current) {
        pendingWslSaveRef.current = false;
        setSwitchInProgress(false);
      }
    });
    const cleanupAdapters = ACPBridge.onAdapters((e) => {
      const nextInstalledAgents = Array.isArray(e.detail.adapters)
        ? e.detail.adapters.filter((agent) => agent.downloaded === true)
        : [];
      setInstalledAgents(nextInstalledAgents);
    });

    const handleBridgeReady = () => {
      requestSettings();
    };

    if (window.__settingsBridgeReady) {
      requestSettings();
    } else {
      window.addEventListener('settings-bridge-ready', handleBridgeReady);
    }

    return () => {
      cleanupFeature();
      cleanupSettings();
      cleanupGlobalSettings();
      cleanupAdapters();
      window.removeEventListener('settings-bridge-ready', handleBridgeReady);
    };
  }, []);

  const actionLabel = feature.installed ? 'Uninstall' : 'Install';
  const action = feature.installed
    ? () => ACPBridge.uninstallAudioTranscriptionFeature()
    : () => ACPBridge.installAudioTranscriptionFeature();

  const handleLanguageChange = (language: string) => {
    const next = { language };
    setSettings(next);
    ACPBridge.saveAudioTranscriptionSettings(next);
  };

  const handleUseWslChange = (useWslForAcpAdapters: boolean) => {
    setPendingWslTarget(useWslForAcpAdapters);
  };

  const handleWslDistributionChange = (wslDistributionName: string) => {
    const next = { ...globalSettings.settings, wslDistributionName };
    setGlobalSettings(prev => ({ ...prev, settings: next }));
    pendingWslSaveRef.current = true;
    ACPBridge.saveGlobalSettings(next);
  };

  const confirmUseWslChange = () => {
    if (pendingWslTarget === null) return;
    pendingWslSaveRef.current = true;
    setSwitchInProgress(true);
    setGlobalSettings(prev => ({
      ...prev,
      settings: {
        ...prev.settings,
        useWslForAcpAdapters: pendingWslTarget,
      },
    }));
    ACPBridge.saveGlobalSettings({
      ...globalSettings.settings,
      useWslForAcpAdapters: pendingWslTarget,
    });
    setPendingWslTarget(null);
  };

  const handleGitCommitGenerationChange = (gitCommitGeneration: GitCommitGenerationSettingsValue) => {
    const next = { ...globalSettings.settings, gitCommitGeneration };
    setGlobalSettings(prev => ({ ...prev, settings: next }));
    ACPBridge.saveGlobalSettings(next);
  };

  const handleAudioNotificationsChange = (audioNotificationsEnabled: boolean) => {
    const next = { ...globalSettings.settings, audioNotificationsEnabled };
    setGlobalSettings(prev => ({ ...prev, settings: next }));
    ACPBridge.saveGlobalSettings(next);
  };

  const handleUiFontSizeChange = (uiFontSizeOffsetPx: number) => {
    const next = { ...globalSettings.settings, uiFontSizeOffsetPx };
    setGlobalSettings(prev => ({ ...prev, settings: next }));
    ACPBridge.saveGlobalSettings(next);
  };

  const handleUserMessageBackgroundStyleChange = (userMessageBackgroundStyle: GlobalSettingsPayload['settings']['userMessageBackgroundStyle']) => {
    const next = { ...globalSettings.settings, userMessageBackgroundStyle };
    setGlobalSettings(prev => ({ ...prev, settings: next }));
    ACPBridge.saveGlobalSettings(next);
  };

  return (
    <div className="flex h-full flex-col bg-background text-foreground">
      <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-2 text-foreground/80">
          <Settings2 size={14} />
          <span className="font-medium">Settings</span>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="px-4 py-4">
          {(globalSettings.host.hostOs === 'windows') && (
            <SettingsCardShell
              icon={Waypoints}
              title="Agent Environment"
              description="Choose whether coding agents run in Windows or inside WSL. Switching affects installation, detection and terminal-based sign-in."
              aside={(
                <button
                  role="switch"
                  aria-checked={globalSettings.settings.useWslForAcpAdapters}
                  aria-label="Use WSL"
                  onClick={() => handleUseWslChange(!globalSettings.settings.useWslForAcpAdapters)}
                  disabled={!globalSettings.host.wslSupported || !globalSettings.settings.wslDistributionName || switchInProgress}
                  className={`relative mt-0.5 inline-flex h-4 w-7 shrink-0 rounded-full border-2 border-transparent transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                    globalSettings.settings.useWslForAcpAdapters ? 'bg-primary' : 'bg-border'
                  }`}
                >
                  <span
                    className={`pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow transition-transform ${
                      globalSettings.settings.useWslForAcpAdapters ? 'translate-x-3' : 'translate-x-0'
                    }`}
                  />
                </button>
              )}
            >
              <div className="mt-3 flex items-center gap-2">
                <label htmlFor="wsl-distribution" className="text-xs text-foreground/60">Distribution</label>
                <select
                  id="wsl-distribution"
                  value={globalSettings.settings.wslDistributionName}
                  onChange={(e) => handleWslDistributionChange(e.target.value)}
                  disabled={globalSettings.settings.useWslForAcpAdapters || switchInProgress || !hasWslDistributions}
                  className="min-w-[180px] rounded-ide border border-border bg-background px-2 py-1 text-xs text-foreground disabled:opacity-50"
                >
                  {globalSettings.host.wslDistributions.length === 0 ? (
                    <option value="">No distributions</option>
                  ) : (
                    globalSettings.host.wslDistributions.map((distribution) => (
                      <option key={distribution.name} value={distribution.name}>
                        {distribution.name}
                      </option>
                    ))
                  )}
                </select>
              </div>
              {!globalSettings.host.wslSupported && (
                <div className="mt-2 text-xs text-error">WSL could not be detected from the IDE process.</div>
              )}
            </SettingsCardShell>
          )}

          <GitCommitGenerationSettings
            settings={globalSettings.settings.gitCommitGeneration}
            installedAgents={installedAgents}
            onChange={handleGitCommitGenerationChange}
          />

          <SettingsToggleCard
            icon={Bell}
            title="Audio Notifications"
            description="Play sounds for new assistant messages and permission requests."
            enabled={globalSettings.settings.audioNotificationsEnabled}
            onToggle={() => handleAudioNotificationsChange(!globalSettings.settings.audioNotificationsEnabled)}
            ariaLabel="Enable audio notifications"
          />

          <SettingsSelectCard
            icon={Type}
            title="UI Font Size"
            description="Adjust the plugin interface font size relative to the IDE default."
          >
            <div className="mt-3 flex items-center gap-2">
              <label htmlFor="ui-font-size" className="text-xs text-foreground/60">Size</label>
              <select
                id="ui-font-size"
                value={String(globalSettings.settings.uiFontSizeOffsetPx)}
                onChange={(e) => handleUiFontSizeChange(Number(e.target.value))}
                className="min-w-[180px] rounded-ide border border-border bg-background px-2 py-1 text-xs text-foreground"
              >
                {uiFontSizeOptions.map((option) => (
                  <option key={option.offset} value={option.offset}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          </SettingsSelectCard>

          <SettingsCardShell
            icon={Palette}
            title="Your Message Color"
            description="Choose the background color used for your chat messages."
          >
            <div className="mt-3 flex flex-wrap gap-2">
              {userMessageBackgroundOptions.map((option) => {
                const selected = globalSettings.settings.userMessageBackgroundStyle === option.id;
                return (
                  <button
                    key={option.id}
                    type="button"
                    onClick={() => handleUserMessageBackgroundStyleChange(option.id)}
                    aria-label="Select message color"
                    aria-pressed={selected}
                    className={`h-8 w-8 rounded-ide border transition-all ${
                      selected
                        ? 'border-primary ring-2 ring-primary/50'
                        : 'border-border hover:border-foreground/40'
                    }`}
                    style={{ backgroundColor: option.background }}
                  >
                    <span className="sr-only">{option.id}</span>
                  </button>
                );
              })}
            </div>
          </SettingsCardShell>

          <SettingsCardShell
            icon={Mic}
            title={feature.title}
            description={feature.status}
            className="mb-0"
            aside={(
              <button
                type="button"
                onClick={action}
                disabled={feature.installing || (!feature.installed && !feature.supported)}
                className="inline-flex shrink-0 items-center gap-1 rounded-ide border border-border bg-background px-3 py-1.5 text-xs text-foreground transition-colors hover:bg-background-secondary disabled:cursor-not-allowed disabled:opacity-50"
              >
                {feature.installing && <LoaderCircle size={12} className="animate-spin" />}
                <span>{actionLabel}</span>
              </button>
            )}
          >
            {feature.detail && (
              <div className="mt-1 break-words text-xs text-foreground/40">{feature.detail}</div>
            )}
            {feature.installed && feature.installPath && (
              <div className="mt-1 break-all font-mono text-[11px] text-foreground/35">{feature.installPath}</div>
            )}
            <div className="mt-3 flex items-center gap-2">
              <label htmlFor="whisper-language" className="text-xs text-foreground/60">Language</label>
              <select
                id="whisper-language"
                value={settings.language}
                onChange={(e) => handleLanguageChange(e.target.value)}
                disabled={!feature.installed}
                className="rounded-ide border border-border bg-background px-2 py-1 text-xs text-foreground disabled:opacity-50"
              >
                <option value="auto">auto</option>
                <option value="lv">Latvian (lv)</option>
                <option value="en">English (en)</option>
                <option value="ru">Russian (ru)</option>
                <option value="de">German (de)</option>
                <option value="fr">French (fr)</option>
                <option value="es">Spanish (es)</option>
              </select>
            </div>
          </SettingsCardShell>
        </div>
      </div>

      <ConfirmationModal
        isOpen={pendingWslTarget !== null}
        title={pendingWslTarget ? 'Switch to WSL' : 'Switch to Windows'}
        message={
          pendingWslTarget
            ? `This will close all open conversations, stop all running ACP agents, refresh Service Providers immediately and restart downloaded agents inside WSL (${globalSettings.settings.wslDistributionName}).`
            : 'This will close all open conversations, stop all running ACP agents, refresh Service Providers immediately and restart downloaded agents in Windows.'
        }
        confirmLabel="Switch"
        onConfirm={confirmUseWslChange}
        onCancel={() => setPendingWslTarget(null)}
      />
    </div>
  );
}
