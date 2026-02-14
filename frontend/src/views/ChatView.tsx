
export function ChatView() {
  return (
    <div className="flex flex-col h-screen bg-background text-foreground animate-in slide-in-from-bottom-4 duration-500">
      {/* Chat History Area */}
      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        <div className="max-w-3xl mx-auto space-y-6">
          <div className="bg-surface border border-border p-4 rounded-ide shadow-sm">
            <h2 className="text-sm font-semibold mb-2 flex items-center gap-2">
              <span className="w-2 h-2 bg-primary rounded-full animate-pulse" />
              AI Assistant Ready
            </h2>
            <p className="text-sm opacity-70 leading-relaxed">
              Hello! I'm your Unified LLM assistant. How can I help you with your code today?
            </p>
          </div>

          <div className="flex justify-center">
            <div className="text-[10px] uppercase tracking-widest opacity-30 border-t border-border w-full text-center pt-2">
              Today
            </div>
          </div>
          
          <div className="bg-surface/50 border border-border/50 p-4 rounded-ide italic text-sm opacity-50">
            Start a new conversation by typing below...
          </div>
        </div>
      </div>

      {/* Input Area */}
      <div className="p-4 border-t border-border bg-surface/30 backdrop-blur-sm">
        <div className="max-w-3xl mx-auto relative">
          <textarea 
            rows={3}
            placeholder="Ask me anything..."
            className="w-full p-4 pr-12 bg-input border border-border rounded-ide focus:ring-2 focus:ring-ring outline-none transition-all resize-none shadow-inner"
          />
          <button
            type="button"
            aria-label="Send message"
            className="absolute right-3 bottom-3 p-2 bg-primary text-primary-foreground rounded-lg hover:opacity-90 transition-all active:scale-90"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"></line>
              <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
            </svg>
          </button>
        </div>
        <div className="mt-2 text-[10px] text-center opacity-30 uppercase tracking-widest">
          Press Enter to send / Shift + Enter for new line
        </div>
      </div>
    </div>
  );
}
