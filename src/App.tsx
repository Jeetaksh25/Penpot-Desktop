import { useState } from 'react';
import { Canvas } from './components/Canvas/Canvas';
import { Toolbar } from './components/Toolbar/Toolbar';
import { PropertiesPanel } from './components/Properties/PropertiesPanel';
import { LayersPanel } from './components/Layers/LayersPanel';
import { CodeGenPanel } from './components/CodeGen/CodeGenPanel';
import './App.css';

function App() {
  const [activePanel, setActivePanel] = useState<'properties' | 'layers' | 'codegen'>('properties');

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-left">
          <div className="app-logo">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
              <rect width="24" height="24" rx="6" fill="url(#logo-gradient)" />
              <path d="M7 7h10v10H7z" fill="white" opacity="0.9" />
              <path d="M10 10h4v4h-4z" fill="url(#logo-gradient)" />
              <defs>
                <linearGradient id="logo-gradient" x1="0" y1="0" x2="24" y2="24">
                  <stop offset="0%" stopColor="#6366f1" />
                  <stop offset="100%" stopColor="#8b5cf6" />
                </linearGradient>
              </defs>
            </svg>
            <span className="app-title">Ovion Desktop</span>
          </div>
          <span className="app-subtitle">Design & Code</span>
        </div>
        <div className="app-header-center">
          <span className="doc-name">Untitled Design</span>
        </div>
        <div className="app-header-right">
          <button className="header-btn" title="Undo" disabled>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M3 7v6h6" /><path d="M21 17a9 9 0 00-9-9 9 9 0 00-6 2.3L3 13" />
            </svg>
          </button>
          <button className="header-btn" title="Redo" disabled>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 7v6h-6" /><path d="M3 17a9 9 0 019-9 9 9 0 016 2.3L21 13" />
            </svg>
          </button>
          <div className="header-divider" />
          <button className="header-btn" title="Zoom out" disabled>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" /><path d="M8 11h6" />
            </svg>
          </button>
          <span className="zoom-level">100%</span>
          <button className="header-btn" title="Zoom in" disabled>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="8" /><path d="M21 21l-4.35-4.35" /><path d="M8 11h6" /><path d="M11 8v6" />
            </svg>
          </button>
        </div>
      </header>

      <div className="app-body">
        <div className="toolbar-container">
          <Toolbar />
        </div>

        <div className="canvas-container">
          <Canvas />
        </div>

        <div className="panels-container">
          <div className="panel-tabs">
            <button
              className={`panel-tab ${activePanel === 'properties' ? 'active' : ''}`}
              onClick={() => setActivePanel('properties')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
              </svg>
              Properties
            </button>
            <button
              className={`panel-tab ${activePanel === 'layers' ? 'active' : ''}`}
              onClick={() => setActivePanel('layers')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M12 2L2 7l10 5 10-5-10-5z" />
                <path d="M2 17l10 5 10-5" />
                <path d="M2 12l10 5 10-5" />
              </svg>
              Layers
            </button>
            <button
              className={`panel-tab ${activePanel === 'codegen' ? 'active' : ''}`}
              onClick={() => setActivePanel('codegen')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="16 18 22 12 16 6" />
                <polyline points="8 6 2 12 8 18" />
              </svg>
              Code
            </button>
          </div>
          <div className="panel-content">
            {activePanel === 'properties' && <PropertiesPanel />}
            {activePanel === 'layers' && <LayersPanel />}
            {activePanel === 'codegen' && <CodeGenPanel />}
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;
