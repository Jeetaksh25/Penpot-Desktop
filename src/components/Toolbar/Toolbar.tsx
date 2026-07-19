import { useDesignStore } from '../../store/designStore';
import type { ToolType } from '../../types/design';
import './Toolbar.css';

interface ToolItem {
  type: ToolType;
  icon: React.ReactNode;
  label: string;
  shortcut: string;
}

const tools: ToolItem[] = [
  {
    type: 'select',
    label: 'Select',
    shortcut: 'V',
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z" />
        <path d="M13 13l6 6" />
      </svg>
    ),
  },
  {
    type: 'rectangle',
    label: 'Rectangle',
    shortcut: 'R',
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="3" width="18" height="18" rx="2" />
      </svg>
    ),
  },
  {
    type: 'circle',
    label: 'Ellipse',
    shortcut: 'O',
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="9" />
      </svg>
    ),
  },
  {
    type: 'line',
    label: 'Line',
    shortcut: 'L',
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="5" y1="19" x2="19" y2="5" />
      </svg>
    ),
  },
  {
    type: 'text',
    label: 'Text',
    shortcut: 'T',
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="4 7 4 4 20 4 20 7" />
        <line x1="9" y1="20" x2="15" y2="20" />
        <line x1="12" y1="4" x2="12" y2="20" />
      </svg>
    ),
  },
];

export function Toolbar() {
  const { activeTool, setActiveTool } = useDesignStore();

  return (
    <div className="toolbar">
      <div className="toolbar-group">
        {tools.slice(0, 1).map((tool) => (
          <button
            key={tool.type}
            className={`tool-btn ${activeTool === tool.type ? 'active' : ''}`}
            onClick={() => setActiveTool(tool.type)}
            title={`${tool.label} (${tool.shortcut})`}
          >
            {tool.icon}
          </button>
        ))}
      </div>
      <div className="toolbar-divider" />
      <div className="toolbar-group">
        {tools.slice(1).map((tool) => (
          <button
            key={tool.type}
            className={`tool-btn ${activeTool === tool.type ? 'active' : ''}`}
            onClick={() => setActiveTool(tool.type)}
            title={`${tool.label} (${tool.shortcut})`}
          >
            {tool.icon}
          </button>
        ))}
      </div>
      <div className="toolbar-spacer" />
      <div className="toolbar-group">
        <button className="tool-btn" title="Zoom to fit">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M8 3H5a2 2 0 00-2 2v3" />
            <path d="M21 8V5a2 2 0 00-2-2h-3" />
            <path d="M3 16v3a2 2 0 002 2h3" />
            <path d="M16 21h3a2 2 0 002-2v-3" />
          </svg>
        </button>
      </div>
    </div>
  );
}
