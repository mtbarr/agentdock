import { useContext } from 'react';
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext';
import {
  $getNodeByKey,
  $isTextNode,
  DecoratorNode,
  EditorConfig,
  LexicalEditor,
  LexicalNode,
  NodeKey,
  SerializedLexicalNode,
  Spread,
} from 'lexical';
import { ChatInputActionsContext } from './ChatInputActionsContext';
import { CodeReferenceChip } from '../shared/CodeReferenceChip';

export type SerializedCodeReferenceNode = Spread<
  { id: string; path: string; fileName: string; startLine?: number; endLine?: number },
  SerializedLexicalNode
>;

export class CodeReferenceNode extends DecoratorNode<JSX.Element> {
  __id: string;
  __path: string;
  __fileName: string;
  __startLine?: number;
  __endLine?: number;

  static getType(): string { return 'code-reference'; }
  static clone(node: CodeReferenceNode): CodeReferenceNode {
    return new CodeReferenceNode(node.__id, node.__path, node.__fileName, node.__startLine, node.__endLine, node.__key);
  }

  constructor(id: string, path: string, fileName: string, startLine?: number, endLine?: number, key?: NodeKey) {
    super(key);
    this.__id = id;
    this.__path = path;
    this.__fileName = fileName;
    this.__startLine = startLine;
    this.__endLine = endLine;
  }

  createDOM(): HTMLElement {
    const span = document.createElement('span');
    span.style.display = 'inline-block';
    return span;
  }

  updateDOM(): false { return false; }

  static importJSON(serializedNode: SerializedCodeReferenceNode): CodeReferenceNode {
    return $createCodeReferenceNode(
      serializedNode.id,
      serializedNode.path,
      serializedNode.fileName,
      serializedNode.startLine,
      serializedNode.endLine
    );
  }

  exportJSON(): SerializedCodeReferenceNode {
    return {
      id: this.__id,
      path: this.__path,
      fileName: this.__fileName,
      startLine: this.__startLine,
      endLine: this.__endLine,
      type: 'code-reference',
      version: 1,
    };
  }

  getTextContent(): string {
    return `[code-ref-${this.__id}]`;
  }

  decorate(_editor: LexicalEditor, _config: EditorConfig): JSX.Element {
    return (
      <CodeReferenceComponent
        id={this.__id}
        nodeKey={this.__key}
        fileName={this.__fileName}
        path={this.__path}
        startLine={this.__startLine}
        endLine={this.__endLine}
      />
    );
  }
}

export function $createCodeReferenceNode(
  id: string,
  path: string,
  fileName: string,
  startLine?: number,
  endLine?: number
): CodeReferenceNode {
  return new CodeReferenceNode(id, path, fileName, startLine, endLine);
}

export function $isCodeReferenceNode(node: LexicalNode | null | undefined): node is CodeReferenceNode {
  return node instanceof CodeReferenceNode;
}

function CodeReferenceComponent({
  id,
  nodeKey,
  fileName,
  path,
  startLine,
  endLine,
}: {
  id: string;
  nodeKey: NodeKey;
  fileName: string;
  path: string;
  startLine?: number;
  endLine?: number;
}) {
  const [editor] = useLexicalComposerContext();
  const actions = useContext(ChatInputActionsContext);
  const reference = actions?.attachments.find((attachment) => attachment.id === id);
  const isAtStart = editor.getEditorState().read(() => {
    const node = $getNodeByKey(nodeKey);
    if (!node) return false;

    let previous = node.getPreviousSibling();
    while (previous) {
      if ($isTextNode(previous)) {
        if (previous.getTextContent().trim().length > 0) return false;
      } else {
        return false;
      }
      previous = previous.getPreviousSibling();
    }

    return true;
  });

  const onDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    editor.update(() => {
      const node = $getNodeByKey(nodeKey);
      node?.remove();
    });
  };

  const onOpen = () => {
    actions?.onOpenFile?.(reference?.path || path, startLine ? startLine - 1 : undefined);
  };

  return (
    <CodeReferenceChip
      fileName={reference?.name || fileName}
      path={reference?.path || path}
      startLine={startLine}
      endLine={endLine}
      onClick={onOpen}
      onRemove={onDelete}
      flushLeft={isAtStart}
    />
  );
}
