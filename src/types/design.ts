export type ElementType = 'rectangle' | 'circle' | 'line' | 'text';

export interface DesignElement {
  id: string;
  name: string;
  elementType: ElementType;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
  fillColor: string;
  strokeColor: string;
  strokeWidth: number;
  opacity: number;
  borderRadius: number;
  textContent: string;
  fontSize: number;
  fontFamily: string;
  zIndex: number;
}

export interface DesignDocument {
  id: string;
  name: string;
  width: number;
  height: number;
  elements: DesignElement[];
}

export type ToolType = 'select' | 'rectangle' | 'circle' | 'line' | 'text';

export interface CodegenOptions {
  framework: 'react' | 'nextjs' | 'winui3';
  includeStyles: boolean;
  responsive: boolean;
  typescript: boolean;
  componentName: string;
}
