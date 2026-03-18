import { Tooltip } from '../../chat/shared/Tooltip';
import React from 'react';

export function UsageIcon({ children, label }: { children: React.ReactNode, label?: string }) {
  return (
    <Tooltip content={<div className="p-1 min-w-[150px]">{children}</div>}>
      <div className="flex items-center gap-1.5 p-1.5 ml-1 text-foreground/50 hover:text-foreground cursor-default transition-colors outline-none rounded hover:bg-background">
        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21.21 15.89A10 10 0 1 1 8 2.83"></path>
          <path d="M22 12A10 10 0 0 0 12 2v10z"></path>
        </svg>
        {label && <span className="text-[11px] font-medium whitespace-nowrap">{label}</span>}
      </div>
    </Tooltip>
  );
}
