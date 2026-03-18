import { useAdapterUsage } from '../../hooks/useAdapterUsage';

interface UsageWindow {
  utilization: number | null;
  resets_at: string | null;
}

interface ClaudeUsageData {
  authType?: 'subscription' | 'api_key';
  five_hour?: UsageWindow | null;
  seven_day?: UsageWindow | null;
  extra_usage?: { is_enabled: boolean; utilization: number | null } | null;
}

function formatResetAt(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(value));
  } catch {
    return null;
  }
}

function parseData(json: string): ClaudeUsageData | null {
  try {
    const parsed = JSON.parse(json);
    if (!parsed || typeof parsed !== 'object') return null;
    return parsed as ClaudeUsageData;
  } catch {
    return null;
  }
}

function WindowLine({ label, window }: { label: string; window: UsageWindow }) {
  const resetLabel = formatResetAt(window.resets_at);
  const pct = typeof window.utilization === 'number' ? `${Math.round(window.utilization)}% used` : 'N/A';
  return (
    <div className="text-foreground">
      <span className="text-foreground-secondary">{label}:</span> {pct}
      {resetLabel && <span className="text-foreground-tertiary"> · Resets in: {resetLabel}</span>}
    </div>
  );
}

const AGENT_ID = 'claude-code';

export function ClaudeUsage() {
  const data = useAdapterUsage(AGENT_ID);

  const usage = data ? parseData(data) : null;
  if (!usage) return null;

  const hasUsageData = usage.five_hour || usage.seven_day || usage.extra_usage?.is_enabled;

  if (!hasUsageData) {
    const url = usage.authType === 'api_key' ? 'https://platform.claude.com' : 'https://claude.ai/settings/usage';
    return (
      <div className="text-foreground-secondary">
        Usage: <button type="button" onClick={() => window.__openUrl?.(url)} className="text-link">{url}</button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-0.5">
      {usage.five_hour && <WindowLine label="5 hour limit" window={usage.five_hour} />}
      {usage.seven_day && <WindowLine label="7 day limit" window={usage.seven_day} />}
      {usage.extra_usage?.is_enabled && usage.extra_usage.utilization !== null && (
        <WindowLine label="Extra usage" window={{ utilization: usage.extra_usage.utilization, resets_at: null }} />
      )}
    </div>
  );
}
