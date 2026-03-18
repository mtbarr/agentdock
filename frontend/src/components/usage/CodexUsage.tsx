import { useAdapterUsage } from '../../hooks/useAdapterUsage';

interface CodexWindow {
  used_percent: number;
  reset_after_seconds: number;
}

interface CodexUsageData {
  authType?: 'subscription' | 'api_key';
  rate_limit?: {
    primary_window: CodexWindow;
    secondary_window: CodexWindow;
  };
}

function formatResetAt(seconds: number): string {
  if (seconds <= 0) return 'now';
  
  const resetDate = new Date(Date.now() + seconds * 1000);
  
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(resetDate);
  } catch {
    return 'soon';
  }
}

function WindowLine({ label, window }: { label: string; window: CodexWindow }) {
  const resetLabel = formatResetAt(window.reset_after_seconds);
  return (
    <div className="text-foreground">
      <span className="text-foreground-secondary">{label}:</span> {Math.round(window.used_percent)}% used
      {resetLabel && <span className="text-foreground-tertiary"> · Resets in: {resetLabel}</span>}
    </div>
  );
}

const AGENT_ID = 'codex';

export function CodexUsage() {
  const data = useAdapterUsage(AGENT_ID);

  let usage: CodexUsageData | null = null;
  try {
    if (data) usage = JSON.parse(data);
  } catch {
    return null;
  }

  if (!usage?.rate_limit) {
    if (!usage?.authType) return null;
    const url = usage.authType === 'api_key' ? 'https://platform.openai.com/usage' : 'https://chatgpt.com/codex/settings/usage';
    return (
      <div className="text-foreground-secondary">
        Usage: <button type="button" onClick={() => window.__openUrl?.(url)} className="text-link">{url}</button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-0.5">
      <WindowLine label="5 hour limit" window={usage.rate_limit.primary_window} />
      <WindowLine label="7 day limit" window={usage.rate_limit.secondary_window} />
    </div>
  );
}
