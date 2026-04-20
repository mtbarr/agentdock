import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent } from 'react';

type OverlayActionState = 'downloaded' | 'copied' | null;

export function useImageOverlayActions() {
  const [selectedImage, setSelectedImage] = useState<string | null>(null);
  const [overlayActionState, setOverlayActionState] = useState<OverlayActionState>(null);
  const overlayActionTimerRef = useRef<number | null>(null);
  const overlayPrimaryActionRef = useRef<HTMLButtonElement | null>(null);

  const clearOverlayActionState = useCallback(() => {
    if (overlayActionTimerRef.current !== null) {
      window.clearTimeout(overlayActionTimerRef.current);
      overlayActionTimerRef.current = null;
    }
    setOverlayActionState(null);
  }, []);

  const flashOverlayActionState = useCallback((state: Exclude<OverlayActionState, null>) => {
    clearOverlayActionState();
    setOverlayActionState(state);
    overlayActionTimerRef.current = window.setTimeout(() => {
      overlayActionTimerRef.current = null;
      setOverlayActionState(null);
    }, 1800);
  }, [clearOverlayActionState]);

  const handleDownload = (e: MouseEvent) => {
    e.stopPropagation();
    flashOverlayActionState('downloaded');
  };

  const handleCopyImage = useCallback(async (e: MouseEvent) => {
    e.stopPropagation();
    if (!selectedImage || !navigator.clipboard?.write || typeof ClipboardItem === 'undefined') {
      return;
    }

    try {
      const response = await fetch(selectedImage);
      const blob = await response.blob();
      await navigator.clipboard.write([
        new ClipboardItem({
          [blob.type || 'image/png']: blob,
        }),
      ]);
      flashOverlayActionState('copied');
    } catch (error) {
      console.warn('[ChatSessionView] Failed to copy image:', error);
    }
  }, [flashOverlayActionState, selectedImage]);

  useEffect(() => {
    return () => {
      clearOverlayActionState();
    };
  }, [clearOverlayActionState]);

  useEffect(() => {
    clearOverlayActionState();
  }, [selectedImage, clearOverlayActionState]);

  useEffect(() => {
    if (!selectedImage) return;
    requestAnimationFrame(() => {
      overlayPrimaryActionRef.current?.focus();
    });
  }, [selectedImage]);

  return {
    selectedImage,
    setSelectedImage,
    closeSelectedImage: () => setSelectedImage(null),
    overlayActionState,
    overlayPrimaryActionRef,
    handleDownload,
    handleCopyImage,
  };
}
