
export default function HistoryPanel({ onClose }: { onClose: () => void }) {
  // This component will evolve to list past conversations.
  // For now, it's a simple placeholder.
  return (
    <div className="flex flex-col h-full bg-background text-foreground z-10 w-full">
      <div className="flex items-center justify-between px-4 py-3 border-b border-border">
        <h2 className="text-sm font-semibold tracking-wide uppercase text-foreground/80">Chat History</h2>
        <button 
          onClick={onClose}
          className="p-1 hover:bg-surface-hover rounded transition-colors text-foreground/60 hover:text-foreground"
          title="Close History Panel"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>
      <div className="flex-1 p-8 flex flex-col items-center justify-center text-foreground/40">
        <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round" className="mb-4 opacity-50">
           <circle cx="12" cy="12" r="10"></circle>
           <polyline points="12 6 12 12 16 14"></polyline>
        </svg>
        <p className="text-sm italic">No history available yet.</p>
        <p className="text-xs text-foreground/30 mt-2">Past conversations will appear here.</p>
      </div>
    </div>
  );
}
