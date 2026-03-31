import { useAdapterUsage } from '../../hooks/useAdapterUsage';

interface CopilotQuotaWindow {
  entitlement?: number;
  remaining?: number;
  percent_remaining?: number;
  unlimited?: boolean;
}

interface CopilotUsageData {
  quota_reset_date?: string;
  quota_reset_date_utc?: string;
  quota_snapshots?: {
    premium_interactions?: CopilotQuotaWindow;
  };
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

const AGENT_ID = 'github-copilot-cli';
const BILLING_URL = 'https://github.com/settings/billing';

export function CopilotUsage() {
  const data = useAdapterUsage(AGENT_ID);
  if (!data) {
    return (
      <div className="text-foreground-secondary">
        Usage: <button type="button" onClick={() => window.__openUrl?.(BILLING_URL)} className="text-link">{BILLING_URL}</button>
      </div>
    );
  }

  let usage: CopilotUsageData | null = null;
  try {
    usage = JSON.parse(data);
  } catch {
    return (
      <div className="text-foreground-secondary">
        Usage: <button type="button" onClick={() => window.__openUrl?.(BILLING_URL)} className="text-link">{BILLING_URL}</button>
      </div>
    );
  }

  const premium = usage?.quota_snapshots?.premium_interactions;
  if (!premium) {
    return (
      <div className="text-foreground-secondary">
        Usage: <button type="button" onClick={() => window.__openUrl?.(BILLING_URL)} className="text-link">{BILLING_URL}</button>
      </div>
    );
  }

  if (premium.unlimited === true) {
    return (
      <div className="text-foreground">
        <span className="text-foreground-secondary">Premium requests:</span> Unlimited
      </div>
    );
  }

  const entitlement = typeof premium.entitlement === 'number' ? premium.entitlement : null;
  const remaining = typeof premium.remaining === 'number' ? premium.remaining : null;
  const used = entitlement !== null && remaining !== null ? Math.max(0, entitlement - remaining) : null;
  const percentRemaining = typeof premium.percent_remaining === 'number' ? premium.percent_remaining : null;
  const percentUsed = percentRemaining !== null ? Math.max(0, 100 - percentRemaining) : null;
  const resetLabel = formatResetAt(usage?.quota_reset_date_utc ?? usage?.quota_reset_date);

  return (
    <div className="text-foreground">
      <span className="text-foreground-secondary">Premium requests:</span>{' '}
      {used !== null && entitlement !== null ? `${used} / ${entitlement} used` : 'N/A'}
      {remaining !== null && <span className="text-foreground-tertiary"> · {remaining} left</span>}
      {percentUsed !== null && <span className="text-foreground-tertiary"> · {parseFloat(percentUsed.toFixed(1))}% used</span>}
      {resetLabel && <span className="text-foreground-tertiary"> · Resets in: {resetLabel}</span>}
    </div>
  );
}
