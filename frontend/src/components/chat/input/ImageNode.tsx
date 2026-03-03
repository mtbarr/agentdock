import { useContext, useState } from 'react';
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext';
import { 
  $getNodeByKey, 
  DecoratorNode, 
  NodeKey, 
  EditorConfig, 
  LexicalNode, 
  SerializedLexicalNode, 
  Spread, 
  LexicalEditor 
} from 'lexical';
import { ChatInputActionsContext } from './ChatInputActionsContext';
import { HoverPreview } from '../shared/HoverPreview';

export type SerializedImageNode = Spread<
  { id: string },
  SerializedLexicalNode
>;

export class ImageNode extends DecoratorNode<JSX.Element> {
  __id: string;

  static getType(): string { return 'image'; }
  static clone(node: ImageNode): ImageNode { return new ImageNode(node.__id, node.__key); }

  constructor(id: string, key?: NodeKey) {
    super(key);
    this.__id = id;
  }

  createDOM(_config: EditorConfig): HTMLElement {
    const span = document.createElement('span');
    span.style.display = 'inline-block';
    return span;
  }

  updateDOM(): false { return false; }

  static importJSON(serializedNode: SerializedImageNode): ImageNode {
    return $createImageNode(serializedNode.id);
  }

  exportJSON(): SerializedImageNode {
    return { id: this.__id, type: 'image', version: 1 };
  }

  getTextContent(): string { return `[image-${this.__id}]`; }

  decorate(_editor: LexicalEditor, _config: EditorConfig): JSX.Element {
    return <ImageComponent id={this.__id} nodeKey={this.__key} />;
  }
}

export function $createImageNode(id: string): ImageNode {
  return new ImageNode(id);
}

export function $isImageNode(node: LexicalNode | null | undefined): node is ImageNode {
  return node instanceof ImageNode;
}

function ImageComponent({ id, nodeKey }: { id: string; nodeKey: NodeKey }) {
  const [editor] = useLexicalComposerContext();
  const actions = useContext(ChatInputActionsContext);
  const att = actions?.attachments.find(a => a.id === id);
  const [tooltipPos, setTooltipPos] = useState<{ x: number, y: number } | null>(null);

  const onDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    editor.update(() => {
      const node = $getNodeByKey(nodeKey);
      if (node) node.remove();
    });
  };

  const onClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (actions && att) {
      actions.onImageClick(`data:${att.mimeType};base64,${att.data}`);
    }
  };

  return (
    <span 
      contentEditable={false}
      className="inline-flex items-center gap-1.5 px-2 py-1 mx-1 rounded-md border border-border
        align-middle bg-background transition-all group relative"
    >
      <span
        onClick={onClick}
        onMouseEnter={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          setTooltipPos({ x: rect.left, y: rect.top });
        }}
        onMouseLeave={() => setTooltipPos(null)}
        className="flex items-center gap-1.5 cursor-pointer"
      >
        {att ? (
          <div className="w-3 h-3 rounded-sm overflow-hidden flex-shrink-0">
            <img 
              src={`data:${att.mimeType};base64,${att.data}`} 
              alt="thumbnail" 
              className="w-full h-full object-cover"
            />
          </div>
        ) : (
          <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="text-foreground"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>
        )}
        <span className="text-xs font-medium text-foreground">Image</span>
      </span>
      <button 
        onClick={onDelete}
        className="ml-0.5 p-0.5 text-foreground transition-all rounded-full hover:bg-background-secondary"
        title="Delete image"
      >
        <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
      </button>

      {att && tooltipPos && (
        <HoverPreview
          src={`data:${att.mimeType};base64,${att.data}`}
          position={tooltipPos}
        />
      )}
    </span>
  );
}
