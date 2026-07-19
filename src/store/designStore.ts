import { create } from 'zustand';
import type { DesignElement, DesignDocument, ToolType, ElementType } from '../types/design';

interface DesignState {
  document: DesignDocument;
  selectedElementIds: string[];
  activeTool: ToolType;
  
  // Document actions
  setDocument: (doc: DesignDocument) => void;
  updateDocumentSize: (width: number, height: number) => void;
  
  // Selection actions
  selectElement: (id: string) => void;
  addToSelection: (id: string) => void;
  clearSelection: () => void;
  
  // Element actions
  addElement: (type: ElementType, x: number, y: number) => void;
  updateElement: (id: string, updates: Partial<DesignElement>) => void;
  deleteSelectedElements: () => void;
  duplicateSelectedElements: () => void;
  moveElement: (id: string, dx: number, dy: number) => void;
  resizeElement: (id: string, width: number, height: number) => void;
  
  // Tool actions
  setActiveTool: (tool: ToolType) => void;
  
  // Reordering
  bringToFront: () => void;
  sendToBack: () => void;
  bringForward: () => void;
  sendBackward: () => void;
}

let elementCounter = 0;
function generateId(): string {
  elementCounter++;
  return `elem-${Date.now()}-${elementCounter}`;
}

function createNewElement(type: ElementType, x: number, y: number): DesignElement {
  const base: DesignElement = {
    id: generateId(),
    name: `${type.charAt(0).toUpperCase() + type.slice(1)} ${elementCounter}`,
    elementType: type,
    x,
    y,
    width: 120,
    height: 80,
    rotation: 0,
    fillColor: type === 'text' ? '#1a1a1a' : '#4A90D9',
    strokeColor: 'transparent',
    strokeWidth: 0,
    opacity: 1,
    borderRadius: type === 'circle' ? 999 : 4,
    textContent: type === 'text' ? 'Double click to edit' : '',
    fontSize: 16,
    fontFamily: 'Inter, system-ui, sans-serif',
    zIndex: 0,
  };

  if (type === 'circle') {
    base.width = 100;
    base.height = 100;
  } else if (type === 'line') {
    base.width = 150;
    base.height = 2;
    base.fillColor = '#666666';
  }

  return base;
}

export const useDesignStore = create<DesignState>((set, _get) => ({
  document: {
    id: crypto.randomUUID(),
    name: 'Untitled Design',
    width: 1920,
    height: 1080,
    elements: [],
  },
  selectedElementIds: [],
  activeTool: 'select',

  setDocument: (doc) => set({ document: doc, selectedElementIds: [] }),

  updateDocumentSize: (width, height) =>
    set((state) => ({
      document: { ...state.document, width, height },
    })),

  selectElement: (id) => set({ selectedElementIds: [id] }),

  addToSelection: (id) =>
    set((state) => ({
      selectedElementIds: state.selectedElementIds.includes(id)
        ? state.selectedElementIds.filter((sid) => sid !== id)
        : [...state.selectedElementIds, id],
    })),

  clearSelection: () => set({ selectedElementIds: [] }),

  addElement: (type, x, y) =>
    set((state) => {
      const element = createNewElement(type, x, y);
      element.zIndex = state.document.elements.length;
      return {
        document: {
          ...state.document,
          elements: [...state.document.elements, element],
        },
        selectedElementIds: [element.id],
        activeTool: 'select' as ToolType,
      };
    }),

  updateElement: (id, updates) =>
    set((state) => ({
      document: {
        ...state.document,
        elements: state.document.elements.map((el) =>
          el.id === id ? { ...el, ...updates } : el
        ),
      },
    })),

  deleteSelectedElements: () =>
    set((state) => ({
      document: {
        ...state.document,
        elements: state.document.elements.filter(
          (el) => !state.selectedElementIds.includes(el.id)
        ),
      },
      selectedElementIds: [],
    })),

  duplicateSelectedElements: () =>
    set((state) => {
      const newElements = state.document.elements
        .filter((el) => state.selectedElementIds.includes(el.id))
        .map((el) => ({
          ...el,
          id: generateId(),
          name: `${el.name} Copy`,
          x: el.x + 20,
          y: el.y + 20,
          zIndex: state.document.elements.length,
        }));

      return {
        document: {
          ...state.document,
          elements: [...state.document.elements, ...newElements],
        },
        selectedElementIds: newElements.map((el) => el.id),
      };
    }),

  moveElement: (id, dx, dy) =>
    set((state) => ({
      document: {
        ...state.document,
        elements: state.document.elements.map((el) =>
          el.id === id ? { ...el, x: el.x + dx, y: el.y + dy } : el
        ),
      },
    })),

  resizeElement: (id, width, height) =>
    set((state) => ({
      document: {
        ...state.document,
        elements: state.document.elements.map((el) =>
          el.id === id ? { ...el, width: Math.max(1, width), height: Math.max(1, height) } : el
        ),
      },
    })),

  setActiveTool: (tool) => set({ activeTool: tool }),

  bringToFront: () =>
    set((state) => {
      const maxZ = state.document.elements.length;
      const elements = state.document.elements.map((el) =>
        state.selectedElementIds.includes(el.id)
          ? { ...el, zIndex: maxZ }
          : el
      );
      return {
        document: {
          ...state.document,
          elements: elements.sort((a, b) => a.zIndex - b.zIndex),
        },
      };
    }),

  sendToBack: () =>
    set((state) => {
      const minZ = -1;
      const elements = state.document.elements.map((el) =>
        state.selectedElementIds.includes(el.id)
          ? { ...el, zIndex: minZ }
          : el
      );
      return {
        document: {
          ...state.document,
          elements: elements.sort((a, b) => a.zIndex - b.zIndex),
        },
      };
    }),

  bringForward: () =>
    set((state) => {
      const maxZ = Math.max(...state.document.elements.map((e) => e.zIndex));
      return {
        document: {
          ...state.document,
          elements: state.document.elements.map((el) =>
            state.selectedElementIds.includes(el.id)
              ? { ...el, zIndex: Math.min(el.zIndex + 1, maxZ) }
              : el
          ),
        },
      };
    }),

  sendBackward: () =>
    set((state) => {
      const elements = state.document.elements.map((el) =>
        state.selectedElementIds.includes(el.id)
          ? { ...el, zIndex: el.zIndex - 1 }
          : el
      );
      return {
        document: {
          ...state.document,
          elements: elements.sort((a, b) => a.zIndex - b.zIndex),
        },
      };
    }),
}));
