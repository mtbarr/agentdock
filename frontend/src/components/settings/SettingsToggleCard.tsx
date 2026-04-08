import { LucideIcon } from 'lucide-react';
import { ReactNode } from 'react';
import { SettingsCardShell } from './SettingsCardShell';

interface SettingsToggleCardProps {
  icon: LucideIcon;
  title: string;
  description: ReactNode;
  enabled: boolean;
  onToggle: () => void;
  ariaLabel: string;
  disabled?: boolean;
  children?: ReactNode;
  className?: string;
}

export function SettingsToggleCard({
  icon: Icon,
  title,
  description,
  enabled,
  onToggle,
  ariaLabel,
  disabled = false,
  children,
  className = 'mb-4',
}: SettingsToggleCardProps) {
  return (
    <SettingsCardShell
      icon={Icon}
      title={title}
      description={description}
      className={className}
      aside={(
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-label={ariaLabel}
          onClick={onToggle}
          disabled={disabled}
          className={`relative mt-0.5 inline-flex h-4 w-7 shrink-0 rounded-full border-2 border-transparent transition-colors ${
            disabled ? 'cursor-not-allowed opacity-50' : ''
          } ${enabled ? 'bg-primary' : 'bg-border'}`}
        >
          <span
            className={`pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow transition-transform ${
              enabled ? 'translate-x-3' : 'translate-x-0'
            }`}
          />
        </button>
      )}
    >
      {children}
    </SettingsCardShell>
  );
}
