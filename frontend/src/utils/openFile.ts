export function openFile(path: string, line?: number): void {
  if (typeof window.__openFile !== 'function') return;
  window.__openFile(JSON.stringify({ filePath: path, line }));
}
