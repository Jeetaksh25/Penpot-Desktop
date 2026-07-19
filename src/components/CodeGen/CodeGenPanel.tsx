import { useState, useCallback } from 'react';
import { useDesignStore } from '../../store/designStore';
import type { DesignElement, CodegenOptions } from '../../types/design';
import './CodeGenPanel.css';

// Extend Window type for Tauri internals
declare global {
  interface Window {
    __TAURI_INTERNALS__?: Record<string, unknown>;
  }
}

export function CodeGenPanel() {
  const { document: designDoc } = useDesignStore();
  const [generatedCode, setGeneratedCode] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [options, setOptions] = useState<CodegenOptions>({
    framework: 'react',
    includeStyles: true,
    responsive: true,
    typescript: true,
    componentName: designDoc.name || 'MyComponent',
  });

  const handleGenerate = useCallback(async () => {
    if (designDoc.elements.length === 0) {
      setGeneratedCode('// Add some elements to your canvas first, then generate code!');
      return;
    }

    setIsGenerating(true);
    try {
      if (window.__TAURI_INTERNALS__) {
        const { invoke } = await import('@tauri-apps/api/core');
        const code: string = await invoke('generate_code', {
          document: {
            id: designDoc.id,
            name: designDoc.name,
            width: designDoc.width,
            height: designDoc.height,
            elements: designDoc.elements.map((el) => ({
              id: el.id,
              name: el.name,
              element_type: el.elementType,
              x: el.x,
              y: el.y,
              width: el.width,
              height: el.height,
              rotation: el.rotation,
              fill_color: el.fillColor,
              stroke_color: el.strokeColor,
              stroke_width: el.strokeWidth,
              opacity: el.opacity,
              border_radius: el.borderRadius,
              text_content: el.textContent,
              font_size: el.fontSize,
              font_family: el.fontFamily,
              z_index: el.zIndex,
            })),
          },
          options: {
            framework: options.framework,
            include_styles: options.includeStyles,
            responsive: options.responsive,
            typescript: options.typescript,
            component_name: options.componentName,
          },
        });
        setGeneratedCode(code);
      } else {
        setGeneratedCode(generateCodeLocal(designDoc, options));
      }
    } catch (error) {
      setGeneratedCode(`// Error generating code: ${error}`);
    } finally {
      setIsGenerating(false);
    }
  }, [designDoc, options]);

  const handleCopyClipboard = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(generatedCode);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = generatedCode;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
  }, [generatedCode]);

  const handleDownload = useCallback(() => {
    const ext = options.framework === 'winui3' ? 'xaml' : options.typescript ? 'tsx' : 'jsx';
    const blob = new Blob([generatedCode], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${options.componentName}.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
  }, [generatedCode, options]);

  const frameworkOptions = [
    { value: 'react' as const, label: 'React', icon: '⚛️' },
    { value: 'nextjs' as const, label: 'Next.js', icon: '▲' },
    { value: 'winui3' as const, label: 'WinUI3 XML', icon: '🪟' },
  ];

  return (
    <div className="codegen-panel">
      <div className="panel-section">
        <div className="panel-section-title">Code Generation</div>
        <div className="form-group">
          <label className="form-label">Framework</label>
          <div className="framework-selector">
            {frameworkOptions.map((fw) => (
              <button
                key={fw.value}
                className={`fw-btn ${options.framework === fw.value ? 'active' : ''}`}
                onClick={() => setOptions((prev) => ({ ...prev, framework: fw.value }))}
              >
                <span className="fw-icon">{fw.icon}</span>
                <span className="fw-label">{fw.label}</span>
              </button>
            ))}
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">Component Name</label>
          <input
            className="form-input"
            value={options.componentName}
            onChange={(e) => setOptions((prev) => ({ ...prev, componentName: e.target.value }))}
            placeholder="MyComponent"
          />
        </div>
      </div>

      <div className="panel-section">
        <div className="panel-section-title">Options</div>
        <label className="checkbox-group">
          <input
            type="checkbox"
            checked={options.typescript}
            onChange={(e) => setOptions((prev) => ({ ...prev, typescript: e.target.checked }))}
          />
          <span>TypeScript</span>
        </label>
        <label className="checkbox-group">
          <input
            type="checkbox"
            checked={options.responsive}
            onChange={(e) => setOptions((prev) => ({ ...prev, responsive: e.target.checked }))}
          />
          <span>Responsive layout</span>
        </label>
        <label className="checkbox-group">
          <input
            type="checkbox"
            checked={options.includeStyles}
            onChange={(e) => setOptions((prev) => ({ ...prev, includeStyles: e.target.checked }))}
          />
          <span>Include inline styles</span>
        </label>
      </div>

      <div className="panel-section">
        <button
          className="btn btn-primary generate-btn"
          onClick={handleGenerate}
          disabled={isGenerating}
        >
          {isGenerating ? (
            <>
              <span className="spinner" />
              Generating...
            </>
          ) : (
            <>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="16 18 22 12 16 6" />
                <polyline points="8 6 2 12 8 18" />
              </svg>
              Generate Code
            </>
          )}
        </button>
      </div>

      {generatedCode && (
        <div className="panel-section code-output-section">
          <div className="code-output-header">
            <span className="panel-section-title">Output</span>
            <div className="code-actions">
              <button className="code-action-btn" onClick={handleCopyClipboard} title="Copy to clipboard">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="9" y="9" width="13" height="13" rx="2" />
                  <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
                </svg>
              </button>
              <button className="code-action-btn" onClick={handleDownload} title="Download file">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
                  <polyline points="7 10 12 15 17 10" />
                  <line x1="12" y1="15" x2="12" y2="3" />
                </svg>
              </button>
            </div>
          </div>
          <pre className="code-output">
            <code>{generatedCode}</code>
          </pre>
        </div>
      )}
    </div>
  );
}

// Local fallback code generation
function generateCodeLocal(doc: { width: number; height: number; elements: DesignElement[] }, options: CodegenOptions): string {
  const elements = doc.elements;
  let code = '';

  if (options.framework === 'react' || options.framework === 'nextjs') {
    code += options.typescript ? "import React from 'react';\n\n" : '';
    code += `const ${options.componentName}: React.FC = () => {\n`;
    code += '  return (\n';
    code += `    <div style={{ position: 'relative', width: '${doc.width}px', height: '${doc.height}px' }}>\n`;

    elements.forEach((el) => {
      const styles: string[] = [
        `position: 'absolute'`,
        `left: '${el.x}px'`,
        `top: '${el.y}px'`,
        `width: '${el.width}px'`,
        `height: '${el.height}px'`,
      ];
      if (el.fillColor && el.fillColor !== 'transparent') styles.push(`backgroundColor: '${el.fillColor}'`);
      if (el.opacity < 1) styles.push(`opacity: ${el.opacity}`);
      if (el.rotation) styles.push(`transform: 'rotate(${el.rotation}deg)'`);
      if (el.borderRadius) styles.push(`borderRadius: '${el.borderRadius}px'`);

      code += `      <div style={{ ${styles.join(', ')} }} />\n`;
    });

    code += '    </div>\n';
    code += '  );\n};\n\n';
    code += `export default ${options.componentName};\n`;
  } else if (options.framework === 'winui3') {
    code += `<UserControl\n`;
    code += `  x:Class="PenpotDesign.${options.componentName}"\n`;
    code += `  xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"\n`;
    code += `  xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">\n\n`;
    code += `  <Canvas Width="${doc.width}" Height="${doc.height}" Background="#FFFFFF">\n\n`;

    elements.forEach((el) => {
      const fill = el.fillColor && el.fillColor !== 'transparent' ? el.fillColor : '#4A90D9';
      code += `    <Rectangle\n`;
      code += `      Width="${el.width}"\n`;
      code += `      Height="${el.height}"\n`;
      code += `      Fill="${fill}"\n`;
      code += `      Canvas.Left="${el.x}"\n`;
      code += `      Canvas.Top="${el.y}"\n`;
      code += `      Opacity="${el.opacity}" />\n\n`;
    });

    code += '  </Canvas>\n';
    code += '</UserControl>\n';
  }

  return code;
}
