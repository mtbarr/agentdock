import { useAdapterUsage } from '../../../hooks/useAdapterUsage';
import { UsageIcon } from './UsageIcon';
import { CopilotUsage } from '../CopilotUsage';

export function CopilotChatUsage() {
  const data = useAdapterUsage('github-copilot-cli');

  let hasData = false;
  let usageLabel: string | undefined;

  if (data) {
    try {
      const parsed = JSON.parse(data);
      const premium = parsed?.quota_snapshots?.premium_interactions;
      if (premium) {
        hasData = true;
        if (premium.unlimited === true) {
          usageLabel = 'Unlimited';
        } else {
          const entitlement = typeof premium.entitlement === 'number' ? premium.entitlement : null;
          const remaining = typeof premium.remaining === 'number' ? premium.remaining : null;
          if (entitlement !== null && remaining !== null) {
            usageLabel = `${Math.max(0, entitlement - remaining)}/${entitlement} used`;
          } else if (typeof premium.percent_remaining === 'number') {
            usageLabel = `${parseFloat((100 - premium.percent_remaining).toFixed(1))}% used`;
          }
        }
      }
    } catch {
      hasData = false;
    }
  }

  if (!hasData) return null;

  return (
    <UsageIcon label={usageLabel}>
      <CopilotUsage />
    </UsageIcon>
  );
}
