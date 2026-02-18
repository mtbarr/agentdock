/**
 * Bridge API exposed to Kotlin (JCEF) for view switching.
 */
interface Window {
  setView?: (view: 'chat' | 'demo') => void;
  __startAgent?: (adapterId?: string, modelId?: string) => void;
  __setModel?: (modelId: string) => void;
  __sendPrompt?: (message: string) => void;
  __requestAdapters?: () => void;
  __notifyReady?: () => void;
}
