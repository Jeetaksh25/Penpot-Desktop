import { useDesignStore } from '../../store/designStore';
import './LayersPanel.css';

const elementIcons: Record<string, React.ReactNode> = {
  rectangle: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="3" y="3" width="18" height="18" rx="2" />
    </svg>
  ),
  circle: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="9" />
    </svg>
  ),
  line: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <line x1="5" y1="19" x2="19" y2="5" />
    </svg>
  ),
  text: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="4 7 4 4 20 4 20 7" />
      <line x1="9" y1="20" x2="15" y2="20" />
      <line x1="12" y1="4" x2="12" y2="20" />
    </svg>
  ),
};

export function LayersPanel() {
  const {
    document,
    selectedElementIds,
    selectElement,
    bringToFront,
    sendToBack,
    bringForward,
    sendBackward,
    deleteSelectedElements,
    duplicateSelectedElements,
  } = useDesignStore();

  const sortedElements = [...document.elements].reverse();

  return (
    <div className="layers-panel">
      <div className="panel-section">
        <div className="panel-section-title">Layers</div>
        <div className="layers-actions">
          <button className="layer-action-btn" onClick={bringToFront} title="Bring to front">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 5v14" /><path d="M19 12l-7 7-7-7" />
            </svg>
          </button>
          <button className="layer-action-btn" onClick={sendToBack} title="Send to back">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 19V5" /><path d="M5 12l7-7 7 7" />
            </svg>
          </button>
          <button className="layer-action-btn" onClick={bringForward} title="Bring forward">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 3v10" /><path d="M18 8l-6 6-6-6" />
            </svg>
          </button>
          <button className="layer-action-btn" onClick={sendBackward} title="Send backward">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 21V11" /><path d="M6 16l6-6 6 6" />
            </svg>
          </button>
          <div className="layers-actions-spacer" />
          <button className="layer-action-btn" onClick={duplicateSelectedElements} title="Duplicate">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="9" y="9" width="13" height="13" rx="2" />
              <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
            </svg>
          </button>
          <button className="layer-action-btn danger" onClick={deleteSelectedElements} title="Delete">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="3 6 5 6 21 6" />
              <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
            </svg>
          </button>
        </div>
      </div>
      <div className="layers-list">
        {sortedElements.length === 0 && (
          <div className="layers-empty">
            <p>No elements yet</p>
            <p className="layers-empty-hint">Select a tool from the toolbar to add shapes</p>
          </div>
        )}
        {sortedElements.map((element) => {
          const isSelected = selectedElementIds.includes(element.id);
          return (
            <div
              key={element.id}
              className={`layer-item ${isSelected ? 'selected' : ''}`}
              onClick={() => selectElement(element.id)}
            >
              <span className="layer-icon">
                {elementIcons[element.elementType] || elementIcons.rectangle}
              </span>
              <span className="layer-name">{element.name}</span>
              <span className="layer-visibility" title="Toggle visibility">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
