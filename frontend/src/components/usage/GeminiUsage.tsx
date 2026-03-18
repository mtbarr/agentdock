import { useAdapterUsage } from '../../hooks/useAdapterUsage';

interface QuotaBucket {
  modelId: string;
  remainingFraction: number | null;
  resetTime: string | null;
}

interface GeminiUsageData {
  quota?: { buckets: QuotaBucket[] };
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

const AGENT_ID = 'gemini-cli';

export function GeminiUsage({ disabledModels }: { disabledModels?: string[] }) {
  const data = useAdapterUsage(AGENT_ID);

  if (!data) return null;

  let usage: GeminiUsageData | null = null;
  try {
    usage = JSON.parse(data);
  } catch {
    return null;
  }

  const buckets = (usage?.quota?.buckets ?? []).filter(b =>
    !disabledModels?.some(d => d && b.modelId.includes(d))
  );

  if (buckets.length === 0) return (
    <div className="text-foreground-secondary">
      Usage: <button type="button" onClick={() => window.__openUrl?.('https://console.cloud.google.com')} className="text-link">console.cloud.google.com</button>
    </div>
  );

  return (
    <div className="flex flex-col gap-0.5">
      {buckets.map((bucket, idx) => {
        const pct = typeof bucket.remainingFraction === 'number'
          ? `${parseFloat(((1 - bucket.remainingFraction) * 100).toFixed(1))}% used`
          : 'N/A';
        const resetLabel = formatResetAt(bucket.resetTime);
        return (
          <div key={bucket.modelId || idx} className="text-[12px] text-foreground">
            <span className="text-foreground-secondary">{bucket.modelId.replace('gemini-', '')}:</span> {pct}
            {resetLabel && <span className="text-foreground-tertiary"> · Resets in: {resetLabel}</span>}
          </div>
        );
      })}
    </div>
  );
}
