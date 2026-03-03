import { createPortal } from 'react-dom';

interface HoverPreviewProps {
  src: string;
  position: { x: number; y: number };
}

export function HoverPreview({ src, position }: HoverPreviewProps) {
  return createPortal(
    <div
      className="fixed z-[9999] p-1 bg-background border border-border rounded shadow-2xl pointer-events-none transition-opacity"
      style={{
        left: `${Math.min(position.x, window.innerWidth - 250)}px`,
        bottom: `${window.innerHeight - position.y + 8}px`,
      }}
    >
      <img
        src={src}
        alt="preview"
        className="max-w-[240px] max-h-[160px] rounded-sm object-contain"
      />
    </div>,
    document.body
  );
}
