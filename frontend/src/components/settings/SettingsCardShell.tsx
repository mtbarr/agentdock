import { LucideIcon } from 'lucide-react';
import { ReactNode } from 'react';

interface SettingsCardShellProps {
  icon: LucideIcon;
  title: string;
  description: ReactNode;
  children?: ReactNode;
  aside?: ReactNode;
  bodyClassName?: string;
  className?: string;
}

export function SettingsCardShell({
  icon: Icon,
  title,
  description,
  children,
  aside,
  bodyClassName = 'px-4 py-3',
  className = 'mb-4',
}: SettingsCardShellProps) {
  return (
    <div className={`${className} rounded-ide border border-border bg-background-secondary`}>
      <div className={bodyClassName}>
        <div className="flex items-start justify-between gap-4">
          <div className="flex min-w-0 items-start gap-3">
            <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-ide border border-border bg-background text-foreground/70">
              <Icon size={15} />
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-foreground">{title}</div>
              <div className="mt-1 text-xs text-foreground/60">{description}</div>
              {children}
            </div>
          </div>

          {aside}
        </div>
      </div>
    </div>
  );
}
