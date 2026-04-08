import { LucideIcon } from 'lucide-react';
import { ReactNode } from 'react';
import { SettingsCardShell } from './SettingsCardShell';

interface SettingsSelectCardProps {
  icon: LucideIcon;
  title: string;
  description: ReactNode;
  children: ReactNode;
  className?: string;
}

export function SettingsSelectCard({
  icon: Icon,
  title,
  description,
  children,
  className = 'mb-4',
}: SettingsSelectCardProps) {
  return (
    <SettingsCardShell
      icon={Icon}
      title={title}
      description={description}
      className={className}
    >
      {children}
    </SettingsCardShell>
  );
}
