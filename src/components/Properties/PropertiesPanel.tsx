import { useDesignStore } from '../../store/designStore';
import './PropertiesPanel.css';

export function PropertiesPanel() {
  const { document, selectedElementIds, updateElement } = useDesignStore();
  const selectedElement = document.elements.find((el) => selectedElementIds.includes(el.id));

  if (!selectedElement) {
    return (
      <div className="properties-empty">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" opacity="0.3">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
        </svg>
        <p>Select an element to edit its properties</p>
      </div>
    );
  }

  const update = (updates: Partial<typeof selectedElement>) => {
    updateElement(selectedElement.id, updates);
  };

  return (
    <div className="properties-panel">
      <div className="panel-section">
        <div className="panel-section-title">Element</div>
        <div className="form-group">
          <label className="form-label">Name</label>
          <input
            className="form-input"
            value={selectedElement.name}
            onChange={(e) => update({ name: e.target.value })}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Type</label>
          <div className="form-input" style={{ opacity: 0.6 }}>
            {selectedElement.elementType.charAt(0).toUpperCase() + selectedElement.elementType.slice(1)}
          </div>
        </div>
      </div>

      <div className="panel-section">
        <div className="panel-section-title">Position & Size</div>
        <div className="form-row">
          <div className="form-group">
            <label className="form-label">X</label>
            <input
              className="form-input"
              type="number"
              value={Math.round(selectedElement.x)}
              onChange={(e) => update({ x: parseFloat(e.target.value) || 0 })}
            />
          </div>
          <div className="form-group">
            <label className="form-label">Y</label>
            <input
              className="form-input"
              type="number"
              value={Math.round(selectedElement.y)}
              onChange={(e) => update({ y: parseFloat(e.target.value) || 0 })}
            />
          </div>
        </div>
        <div className="form-row">
          <div className="form-group">
            <label className="form-label">W</label>
            <input
              className="form-input"
              type="number"
              value={Math.round(selectedElement.width)}
              onChange={(e) => update({ width: Math.max(1, parseFloat(e.target.value) || 1) })}
            />
          </div>
          <div className="form-group">
            <label className="form-label">H</label>
            <input
              className="form-input"
              type="number"
              value={Math.round(selectedElement.height)}
              onChange={(e) => update({ height: Math.max(1, parseFloat(e.target.value) || 1) })}
            />
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">Rotation</label>
          <input
            className="form-input"
            type="number"
            value={Math.round(selectedElement.rotation)}
            onChange={(e) => update({ rotation: parseFloat(e.target.value) || 0 })}
          />
        </div>
      </div>

      <div className="panel-section">
        <div className="panel-section-title">Fill & Stroke</div>
        <div className="form-row">
          <div className="form-group">
            <label className="form-label">Fill</label>
            <input
              className="form-color"
              type="color"
              value={selectedElement.fillColor === 'transparent' ? '#000000' : selectedElement.fillColor}
              onChange={(e) => update({ fillColor: e.target.value })}
            />
          </div>
          <div className="form-group">
            <label className="form-label">Stroke</label>
            <input
              className="form-color"
              type="color"
              value={selectedElement.strokeColor === 'transparent' ? '#000000' : selectedElement.strokeColor}
              onChange={(e) => update({ strokeColor: e.target.value })}
            />
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">Stroke Width</label>
          <input
            className="form-input"
            type="number"
            min="0"
            max="50"
            value={selectedElement.strokeWidth}
            onChange={(e) => update({ strokeWidth: parseFloat(e.target.value) || 0 })}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Opacity</label>
          <input
            className="form-range"
            type="range"
            min="0"
            max="1"
            step="0.01"
            value={selectedElement.opacity}
            onChange={(e) => update({ opacity: parseFloat(e.target.value) })}
          />
          <div className="form-range-value">{Math.round(selectedElement.opacity * 100)}%</div>
        </div>
      </div>

      {selectedElement.elementType === 'rectangle' && (
        <div className="panel-section">
          <div className="panel-section-title">Corners</div>
          <div className="form-group">
            <label className="form-label">Border Radius</label>
            <input
              className="form-input"
              type="number"
              min="0"
              value={Math.round(selectedElement.borderRadius)}
              onChange={(e) => update({ borderRadius: parseFloat(e.target.value) || 0 })}
            />
          </div>
        </div>
      )}

      {selectedElement.elementType === 'text' && (
        <div className="panel-section">
          <div className="panel-section-title">Text Style</div>
          <div className="form-group">
            <label className="form-label">Font Size</label>
            <input
              className="form-input"
              type="number"
              min="8"
              max="200"
              value={selectedElement.fontSize}
              onChange={(e) => update({ fontSize: parseFloat(e.target.value) || 16 })}
            />
          </div>
          <div className="form-group">
            <label className="form-label">Font Family</label>
            <select
              className="form-select"
              value={selectedElement.fontFamily}
              onChange={(e) => update({ fontFamily: e.target.value })}
            >
              <option value="Inter">Inter</option>
              <option value="Arial">Arial</option>
              <option value="Helvetica">Helvetica</option>
              <option value="Georgia">Georgia</option>
              <option value="'Times New Roman'">Times New Roman</option>
              <option value="monospace">Monospace</option>
            </select>
          </div>
        </div>
      )}
    </div>
  );
}
