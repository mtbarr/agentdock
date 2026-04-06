import React, { useCallback, useMemo } from 'react';
import { marked } from 'marked';
import { markedHighlight } from 'marked-highlight';
import hljs from '../../utils/highlight';
import { openFile } from '../../utils/openFile';
import '../../styles/markdown.css';

// Configure marked with highlight.js integration
marked.use(
  markedHighlight({
    highlight(code, lang) {
      const language = hljs.getLanguage(lang) ? lang : 'plaintext';
      return hljs.highlight(code, { language }).value;
    }
  })
);

marked.setOptions({
  breaks: true, // Support GFM line breaks
  gfm: true,
});

interface MarkdownMessageProps {
  content: string;
}

/**
 * Minimalist Markdown rendering component for chat messages.
 * Adheres to IDE theme using Tailwind arbitrary variants and CSS variables.
 */
export const MarkdownMessage: React.FC<MarkdownMessageProps> = ({ content }) => {
  const html = useMemo(() => {
    try {
      let processed = content;
      const codeBlockMatches = processed.match(/```/g);
      if (codeBlockMatches && codeBlockMatches.length % 2 !== 0) {
        processed += '\n```';
      }
      return marked.parse(processed);
    } catch (e) {
      console.error('[MarkdownMessage] Parse error:', e);
      return content;
    }
  }, [content]);

  const handleClick = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement | null;
    const anchor = target?.closest('a');
    if (!anchor) return;

    const rawHref = anchor.getAttribute('href')?.trim();
    if (!rawHref) return;

    event.preventDefault();
    event.stopPropagation();

    const href = decodeHtmlHref(rawHref);
    const localFileTarget = parseLocalFileTarget(href);
    if (localFileTarget) {
      openFile(localFileTarget.path, localFileTarget.line);
      return;
    }

    if (/^https?:\/\//i.test(href)) {
      window.__openUrl?.(href);
    }
  }, []);

  return (
    <div
      className="markdown-body"
      onClick={handleClick}
      dangerouslySetInnerHTML={{ __html: html as string }}
    />
  );
};

function decodeHtmlHref(href: string): string {
  const textarea = document.createElement('textarea');
  textarea.innerHTML = href;
  return textarea.value;
}

function parseLocalFileTarget(href: string): { path: string; line?: number } | null {
  const normalized = href.replace(/\\/g, '/');
  const match = normalized.match(/^(?:\/)?([A-Za-z]:\/.+?)(?:#L(\d+))?$/);
  if (match) {
    return {
      path: match[1],
      line: match[2] ? Number(match[2]) : undefined,
    };
  }

  return null;
}

