import { useState } from 'react';

/**
 * Manages expand/collapse state for tool call blocks.
 * Starts expanded during live streaming, auto-collapses when finished.
 * Replayed blocks start collapsed.
 */
export function useAutoCollapse() {
  const [isExpanded, setIsExpanded] = useState(false);

  const toggle = () => setIsExpanded(v => !v);

  return { isExpanded, toggle };
}
