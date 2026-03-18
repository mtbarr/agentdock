import { useAdapterUsage } from '../../../hooks/useAdapterUsage';
import { UsageIcon } from './UsageIcon';
import { CodexUsage } from '../CodexUsage';

export function CodexChatUsage() {
  const data = useAdapterUsage('codex');

  let hasData = false;
  let usageLabel = '';

  if (data) {
    try {
      const parsed = JSON.parse(data);
      if (parsed && typeof parsed === 'object' && parsed.rate_limit) {
        hasData = true;
        
        const primary = parsed.rate_limit.primary_window;
        const secondary = parsed.rate_limit.secondary_window;
        
        let percent = primary?.used_percent ?? 0;
        
        if (secondary && secondary.used_percent > 89 && (!primary || primary.used_percent < 89)) {
          percent = secondary.used_percent;
        }
        
        usageLabel = `${Math.round(percent)}% used`;
      }
    } catch {
      hasData = false;
    }
  }

  if (!hasData) return null;

  return (
    <UsageIcon label={usageLabel}>
      <CodexUsage />
    </UsageIcon>
  );
}
