import { useRef, useState, useCallback, useEffect } from 'react';
import { useDesignStore } from '../../store/designStore';
import type { ElementType } from '../../types/design';
import './Canvas.css';

export function Canvas() {
  const {
    document,
    selectedElementIds,
    activeTool,
    addElement,
    selectElement,
    clearSelection,
    moveElement,
    updateElement,
    deleteSelectedElements,
    duplicateSelectedElements,
  } = useDesignStore();

  const svgRef = useRef<SVGSVGElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
  const [dragTarget, setDragTarget] = useState<string | null>(null);
  const [isResizing, setIsResizing] = useState(false);
  const [resizeHandle, setResizeHandle] = useState<string | null>(null);
  const [editingTextId, setEditingTextId] = useState<string | null>(null);
  const nudgeIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Note: browser keydown auto-repeats, so setInterval is not strictly needed for nudges.

  const handleCanvasClick = useCallback((e: React.MouseEvent) => {
    if (e.target === svgRef.current || (e.target as Element)?.classList?.contains('canvas-bg')) {
      if (activeTool !== 'select') {
        const rect = svgRef.current?.getBoundingClientRect();
        if (rect) {
          const x = e.clientX - rect.left;
          const y = e.clientY - rect.top;
          addElement(activeTool as ElementType, x - 50, y - 40);
        }
      } else {
        clearSelection();
      }
    }
  }, [activeTool, addElement, clearSelection]);

  const handleElementMouseDown = useCallback((e: React.MouseEvent, elementId: string) => {
    e.stopPropagation();
    
    if (!selectedElementIds.includes(elementId)) {
      selectElement(elementId);
    }

    if (activeTool === 'select') {
      setIsDragging(true);
      setDragTarget(elementId);
      setDragStart({ x: e.clientX, y: e.clientY });
    }
  }, [activeTool, selectedElementIds, selectElement]);

  const handleResizeStart = useCallback((e: React.MouseEvent, elementId: string, handle: string) => {
    e.stopPropagation();
    setIsResizing(true);
    setDragTarget(elementId);
    setResizeHandle(handle);
    setDragStart({ x: e.clientX, y: e.clientY });
  }, []);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (isDragging && dragTarget) {
      const dx = e.clientX - dragStart.x;
      const dy = e.clientY - dragStart.y;
      moveElement(dragTarget, dx, dy);
      setDragStart({ x: e.clientX, y: e.clientY });
    }

    if (isResizing && dragTarget && resizeHandle) {
      const dx = e.clientX - dragStart.x;
      const dy = e.clientY - dragStart.y;
      const element = document.elements.find(el => el.id === dragTarget);
      if (element) {
        let newWidth = element.width;
        let newHeight = element.height;
        let newX = element.x;
        let newY = element.y;

        if (resizeHandle.includes('e')) newWidth = Math.max(10, element.width + dx);
        if (resizeHandle.includes('w')) { newWidth = Math.max(10, element.width - dx); newX = element.x + dx; }
        if (resizeHandle.includes('s')) newHeight = Math.max(10, element.height + dy);
        if (resizeHandle.includes('n')) { newHeight = Math.max(10, element.height - dy); newY = element.y + dy; }

        updateElement(dragTarget, { width: newWidth, height: newHeight, x: newX, y: newY });
      }
      setDragStart({ x: e.clientX, y: e.clientY });
    }
  }, [isDragging, isResizing, dragTarget, resizeHandle, dragStart, moveElement, updateElement, document.elements]);

  const handleMouseUp = useCallback(() => {
    setIsDragging(false);
    setIsResizing(false);
    setDragTarget(null);
    setResizeHandle(null);
  }, []);

  const startNudge = useCallback((dx: number, dy: number) => {
    selectedElementIds.forEach((id) => moveElement(id, dx, dy));
  }, [selectedElementIds, moveElement]);

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (editingTextId) return;
    
    if (e.key === 'Delete' || e.key === 'Backspace') {
      deleteSelectedElements();
      return;
    }
    if (e.key === 'Escape') {
      clearSelection();
      setEditingTextId(null);
      return;
    }

    if ((e.ctrlKey || e.metaKey) && e.key === 'd') {
      e.preventDefault();
      duplicateSelectedElements();
      return;
    }

    const arrowKeys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];
    if (arrowKeys.includes(e.key) && selectedElementIds.length > 0) {
      e.preventDefault();
      const speed = e.shiftKey ? 10 : 1;
      switch (e.key) {
        case 'ArrowUp': startNudge(0, -speed); break;
        case 'ArrowDown': startNudge(0, speed); break;
        case 'ArrowLeft': startNudge(-speed, 0); break;
        case 'ArrowRight': startNudge(speed, 0); break;
      }
    }
  }, [deleteSelectedElements, clearSelection, editingTextId, selectedElementIds, startNudge, duplicateSelectedElements]);

  const handleKeyUp = useCallback((e: KeyboardEvent) => {
    const arrowKeys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];
    if (arrowKeys.includes(e.key) && nudgeIntervalRef.current) {
      clearInterval(nudgeIntervalRef.current);
      nudgeIntervalRef.current = null;
    }
  }, []);

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
      if (nudgeIntervalRef.current) {
        clearInterval(nudgeIntervalRef.current);
      }
    };
  }, [handleKeyDown, handleKeyUp]);

  const renderElement = (el: typeof document.elements[0]) => {
    const isSelected = selectedElementIds.includes(el.id);
    const strokeColor = el.strokeColor !== 'transparent' && el.strokeColor ? el.strokeColor : undefined;

    return (
      <g key={el.id} style={{ cursor: activeTool === 'select' ? 'move' : 'crosshair' }}>
        {el.elementType === 'rectangle' && (
          <rect
            x={el.x}
            y={el.y}
            width={el.width}
            height={el.height}
            rx={el.borderRadius}
            fill={el.fillColor}
            stroke={strokeColor}
            strokeWidth={el.strokeWidth}
            opacity={el.opacity}
            transform={el.rotation ? `rotate(${el.rotation} ${el.x + el.width / 2} ${el.y + el.height / 2})` : undefined}
            onMouseDown={(e) => handleElementMouseDown(e, el.id)}
            className="canvas-element"
          />
        )}
        {el.elementType === 'circle' && (
          <ellipse
            cx={el.x + el.width / 2}
            cy={el.y + el.height / 2}
            rx={el.width / 2}
            ry={el.height / 2}
            fill={el.fillColor}
            stroke={strokeColor}
            strokeWidth={el.strokeWidth}
            opacity={el.opacity}
            transform={el.rotation ? `rotate(${el.rotation} ${el.x + el.width / 2} ${el.y + el.height / 2})` : undefined}
            onMouseDown={(e) => handleElementMouseDown(e, el.id)}
            className="canvas-element"
          />
        )}
        {el.elementType === 'line' && (
          <line
            x1={el.x}
            y1={el.y}
            x2={el.x + el.width}
            y2={el.y + el.height}
            stroke={el.fillColor}
            strokeWidth={Math.max(el.strokeWidth || 2, 2)}
            opacity={el.opacity}
            onMouseDown={(e) => handleElementMouseDown(e, el.id)}
            className="canvas-element"
          />
        )}
        {el.elementType === 'text' && (
          <foreignObject
            x={el.x}
            y={el.y}
            width={el.width || 120}
            height={el.height || 24}
            transform={el.rotation ? `rotate(${el.rotation} ${el.x + el.width / 2} ${el.y + el.height / 2})` : undefined}
            onMouseDown={(e) => handleElementMouseDown(e, el.id)}
          >
            <div
              style={{
                width: '100%',
                height: '100%',
                color: el.fillColor,
                fontSize: `${el.fontSize}px`,
                fontFamily: el.fontFamily,
                opacity: el.opacity,
                cursor: activeTool === 'select' ? 'move' : 'crosshair',
                outline: 'none',
                padding: 4,
                overflow: 'hidden',
              }}
              contentEditable={editingTextId === el.id}
              suppressContentEditableWarning
              onDoubleClick={() => setEditingTextId(el.id)}
              onBlur={(e) => {
                updateElement(el.id, { textContent: e.currentTarget.textContent || '' });
                setEditingTextId(null);
              }}
            >
              {el.textContent || 'Text'}
            </div>
          </foreignObject>
        )}
        {isSelected && activeTool === 'select' && (
          <>
            <rect
              x={el.x - 2}
              y={el.y - 2}
              width={el.width + 4}
              height={el.height + 4}
              fill="none"
              stroke="#6366f1"
              strokeWidth={1.5}
              strokeDasharray="4 2"
              rx={el.borderRadius + 2}
              pointerEvents="none"
            />
            {['nw', 'ne', 'sw', 'se', 'n', 's', 'e', 'w'].map((handle) => {
              const positions: Record<string, { x: number; y: number }> = {
                nw: { x: el.x - 4, y: el.y - 4 },
                n: { x: el.x + el.width / 2 - 4, y: el.y - 4 },
                ne: { x: el.x + el.width - 4, y: el.y - 4 },
                e: { x: el.x + el.width - 4, y: el.y + el.height / 2 - 4 },
                se: { x: el.x + el.width - 4, y: el.y + el.height - 4 },
                s: { x: el.x + el.width / 2 - 4, y: el.y + el.height - 4 },
                sw: { x: el.x - 4, y: el.y + el.height - 4 },
                w: { x: el.x - 4, y: el.y + el.height / 2 - 4 },
              };
              const pos = positions[handle];
              const cursors: Record<string, string> = {
                nw: 'nw-resize', n: 'n-resize', ne: 'ne-resize',
                e: 'e-resize', se: 'se-resize', s: 's-resize',
                sw: 'sw-resize', w: 'w-resize',
              };
              return (
                <rect
                  key={handle}
                  x={pos.x}
                  y={pos.y}
                  width={8}
                  height={8}
                  fill="white"
                  stroke="#6366f1"
                  strokeWidth={1.5}
                  rx={1}
                  style={{ cursor: cursors[handle] }}
                  onMouseDown={(e) => handleResizeStart(e, el.id, handle)}
                />
              );
            })}
          </>
        )}
      </g>
    );
  };

  return (
    <div className="canvas-wrapper">
      <svg
        ref={svgRef}
        className="canvas-svg"
        width={document.width}
        height={document.height}
        viewBox={`0 0 ${document.width} ${document.height}`}
        onClick={handleCanvasClick}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
      >
        <defs>
          <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
            <path d="M 20 0 L 0 0 0 20" fill="none" stroke="rgba(255,255,255,0.04)" strokeWidth="0.5" />
          </pattern>
        </defs>
        <rect className="canvas-bg" width="100%" height="100%" fill="white" />
        <rect width="100%" height="100%" fill="url(#grid)" />
        {document.elements.map(renderElement)}
      </svg>
    </div>
  );
}
