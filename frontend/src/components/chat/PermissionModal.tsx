import { PermissionRequest } from '../../types/chat';

interface PermissionModalProps {
  request: PermissionRequest;
  onRespond: (decision: string) => void;
}

export default function PermissionModal({ request, onRespond }: PermissionModalProps) {
  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999] p-6">
      <div className="bg-surface text-foreground border border-border p-5 rounded-lg shadow-2xl max-w-md w-full animate-in fade-in zoom-in duration-200">
        <h3 className="font-bold mb-3 text-lg border-b border-border pb-2 flex items-center gap-2">
          <span className="text-warning">&#9888;</span> Permission Required
        </h3>
        <div className="mb-6 text-sm leading-relaxed max-h-[60vh] overflow-y-auto whitespace-pre-wrap font-mono bg-background p-3 rounded border border-border">
          {request.description}
        </div>
        <div className="flex justify-end gap-3 pt-2 border-t border-border">
          <button
            onClick={() => onRespond('deny')}
            className="px-4 py-2 text-sm font-medium rounded bg-secondary hover:bg-secondary/80 text-secondary-foreground transition-colors"
          >
            Deny
          </button>
          <button
            onClick={() => onRespond(request.options[0]?.optionId || 'allow')}
            className="px-4 py-2 text-sm font-medium rounded bg-primary hover:bg-primary/80 text-primary-foreground shadow-lg transition-colors"
          >
            Allow
          </button>
        </div>
      </div>
    </div>
  );
}
