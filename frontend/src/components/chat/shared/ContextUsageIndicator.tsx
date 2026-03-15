import { Tooltip } from './Tooltip';

interface ContextUsageIndicatorProps {
  used?: number;
  size?: number;
}

export function ContextUsageIndicator({ used, size }: ContextUsageIndicatorProps) {
  if (used === undefined && size === undefined) return null;

  const percent = used !== undefined && size !== undefined && size > 0 
    ? (used / size) * 100 
    : 0;
  
  const r = 5.5;
  const circumference = 2 * Math.PI * r;
  const strokeDashoffset = circumference - (percent / 100) * circumference;

  const usedFormatted = used?.toLocaleString() ?? '?';
  const sizeFormatted = size?.toLocaleString() ?? '?';
  const percentFormatted = percent.toFixed(1) + '%';

  return (
    <Tooltip 
      content={
        <div className="flex flex-col gap-1.5 p-1 min-w-[200px] text-left">
          <div className="font-semibold text-[13px] text-foreground mb-1">
            {percentFormatted} <span className="text-foreground-secondary font-normal text-[13px]">context used</span>
          </div>
          <div className="text-[11px] text-foreground-secondary flex justify-between items-center">
            <span>Tokens:</span>
            <span className="text-foreground font-mono">{usedFormatted} / {sizeFormatted}</span>
          </div>
        </div>
      }
    >
      <div className="flex items-center p-1.5 ml-1 text-foreground/50 hover:text-foreground cursor-default transition-colors outline-none rounded hover:bg-background">
        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 14 14" className="rotate-[-90deg]">
          <circle cx="7" cy="7" r={r} fill="none" stroke="currentColor" strokeWidth="2.5" className="opacity-20" />
          <circle 
            cx="7" 
             cy="7" 
            r={r} 
            fill="none" 
            stroke="currentColor" 
            strokeWidth="2.5" 
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            strokeLinecap="round"
          />
        </svg>
      </div>
    </Tooltip>
  );
}
