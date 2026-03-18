export function CursorUsage() {
  return (
    <div className="text-foreground-secondary">
      Usage: <span></span>
        <button type="button" onClick={() => window.__openUrl?.('https://cursor.com/dashboard/spending')} className="text-link">
            https://cursor.com/dashboard/spending
        </button>
    </div>
  );
}
