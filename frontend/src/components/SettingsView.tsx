import { useEffect, useRef, useState } from 'react';
import { LoaderCircle, Mic, Settings2 } from 'lucide-react';
import { AgentOption, AudioTranscriptionFeatureState, AudioTranscriptionSettings, GitCommitGenerationSettings as GitCommitGenerationSettingsValue, GlobalSettingsPayload } from '../types/chat';
import { ACPBridge } from '../utils/bridge';
import ConfirmationModal from './ConfirmationModal';
import { GitCommitGenerationSettings } from './settings/GitCommitGenerationSettings';

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
    audioTranscription: { language: 'auto' },
    gitCommitGeneration: { enabled: false, adapterId: '', modelId: '', instructions: '' },
  },
  host: { hostOs: 'other', wslSupported: false, wslDistributions: [] },
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
  return {
    settings: {
      useWslForAcpAdapters: Boolean(payload?.settings?.useWslForAcpAdapters),
      wslDistributionName: selectedDistribution,
      audioTranscription: payload?.settings?.audioTranscription ?? { language: 'auto' },
      gitCommitGeneration: normalizeGitCommitGenerationSettings(payload?.settings?.gitCommitGeneration),
    },
    host: {
      hostOs: payload?.host?.hostOs === 'windows' ? 'windows' : 'other',
      wslSupported: Boolean(payload?.host?.wslSupported),
      wslDistributions,
    },
  };
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
            <div className="mb-4 rounded-ide border border-border bg-background-secondary">
              <div className="flex items-start justify-between gap-4 px-4 py-3">
                <div className="min-w-0">
                  <div className="text-sm font-medium text-foreground">Agent Environment</div>
                  <div className="mt-1 text-xs text-foreground/60">
                    Choose whether coding agents run in Windows or inside WSL. Switching affects installation, detection and terminal-based sign-in.
                  </div>
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
                </div>
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
              </div>
            </div>
          )}

          <GitCommitGenerationSettings
            settings={globalSettings.settings.gitCommitGeneration}
            installedAgents={installedAgents}
            onChange={handleGitCommitGenerationChange}
          />

          <div className="rounded-ide border border-border bg-background-secondary">
            <div className="flex items-start justify-between gap-4 px-4 py-3">
              <div className="flex min-w-0 items-start gap-3">
                <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-ide border border-border bg-background text-foreground/70">
                  <Mic size={15} />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-medium text-foreground">{feature.title}</div>
                  <div className="mt-1 text-xs text-foreground/60">{feature.status}</div>
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
                </div>
              </div>

              <button
                type="button"
                onClick={action}
                disabled={feature.installing || (!feature.installed && !feature.supported)}
                className="inline-flex shrink-0 items-center gap-1 rounded-ide border border-border bg-background px-3 py-1.5 text-xs text-foreground transition-colors hover:bg-background-secondary disabled:cursor-not-allowed disabled:opacity-50"
              >
                {feature.installing && <LoaderCircle size={12} className="animate-spin" />}
                <span>{actionLabel}</span>
              </button>
            </div>
          </div>
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
