use crate::{DesignDocument, DesignElement, CodegenOptions};
use crate::codegen;

pub fn generate_react_code(document: &DesignDocument, options: &CodegenOptions) -> Result<String, String> {
    let component_name = if options.component_name.is_empty() {
        codegen::to_pascal_case(&document.name)
    } else {
        codegen::to_pascal_case(&options.component_name)
    };

    let mut code = String::new();
    let ext = if options.typescript { "tsx" } else { "jsx" };

    // Imports
    code.push_str(&generate_imports(options));

    // Component definition
    code.push_str(&format!("\n\nconst {}: React.FC = () => {{\n", component_name));
    code.push_str("  return (\n");
    code.push_str(&generate_container_div(document, options));
    code.push_str("  );\n");
    code.push_str("};\n\n");

    // Export
    if options.typescript {
        code.push_str(&format!("export default {};\n", component_name));
    } else {
        code.push_str(&format!("export default {};\n", component_name));
    }

    // Responsive styles
    if options.responsive {
        code.push_str(&format!("\nconst styles: Record<string, React.CSSProperties> = {{\n"));
        code.push_str("  container: {\n");
        code.push_str(&format!("    width: '100%',\n"));
        code.push_str(&format!("    maxWidth: '{}px',\n", document.width));
        code.push_str("    height: 'auto',\n");
        code.push_str("    aspectRatio: `${16 / 9}`,\n");
        code.push_str("    position: 'relative',\n");
        code.push_str("    overflow: 'hidden',\n");
        code.push_str("  },\n");
        code.push_str("};\n");
    }

    Ok(code)
}

fn generate_imports(options: &CodegenOptions) -> String {
    let mut imports = String::new();
    if options.typescript {
        imports.push_str("import React from 'react';\n");
    }
    imports
}

fn generate_container_div(document: &DesignDocument, options: &CodegenOptions) -> String {
    let mut div = String::new();
    let width = if options.responsive { "100%" } else { &format!("{}px", document.width) };
    let height = if options.responsive { "auto" } else { &format!("{}px", document.height) };

    if options.responsive {
        div.push_str(&format!(
            "    <div style={{\n\
             ...styles.container,\n\
             maxWidth: '{}px',\n\
             minHeight: '{}px',\n\
             position: 'relative' as const,\n\
             }}>\n",
            document.width,
            document.height
        ));
    } else {
        div.push_str(&format!(
            "    <div style={{\n\
             width: '{}',\n\
             height: '{}',\n\
             position: 'relative',\n\
             overflow: 'hidden',\n\
             backgroundColor: '#ffffff',\n\
             }}>\n",
            width, height
        ));
    }

    for element in &document.elements {
        div.push_str(&format!("{}\n", generate_element_jsx(element, 2)));
    }

    div.push_str("    </div>");
    div
}

fn generate_element_jsx(element: &DesignElement, indent: usize) -> String {
    let indent_str = "  ".repeat(indent);
    let inner_indent = "  ".repeat(indent + 1);
    let mut jsx = String::new();

    let common_props = format!(
        "style={{\n\
         {}position: 'absolute',\n\
         {}left: '{}px',\n\
         {}top: '{}px',\n\
         {}width: '{}px',\n\
         {}height: '{}px',{}\n\
         {}}}",
        inner_indent, inner_indent, element.x,
        inner_indent, element.y,
        inner_indent, element.width,
        inner_indent, element.height,
        generate_style_props(element, indent + 2),
        inner_indent
    );

    match element.element_type.as_str() {
        "text" => {
            jsx.push_str(&format!(
                "{}<div\n{}>\n{}{}\n{}{}</div>",
                indent_str, common_props,
                inner_indent, element.text_content,
                indent_str, inner_indent
            ));
        }
        "circle" | "ellipse" => {
            jsx.push_str(&format!(
                "{}<div\n{}\n{}{}borderRadius: '50%',\n{}{}></div>",
                indent_str, common_props,
                inner_indent,
                inner_indent,
                indent_str, inner_indent
            ));
        }
        "line" => {
            jsx.push_str(&format!(
                "{}<div\n{}></div>",
                indent_str, common_props
            ));
        }
        _ => {
            // rectangle and default
            jsx.push_str(&format!(
                "{}<div\n{}></div>",
                indent_str, common_props
            ));
        }
    }

    jsx
}

fn generate_style_props(element: &DesignElement, indent: usize) -> String {
    let ind = "  ".repeat(indent);
    let mut props = String::new();

    if element.fill_color != "transparent" {
        props.push_str(&format!("\n{}backgroundColor: '{}',", ind, element.fill_color));
    }
    if element.opacity < 1.0 {
        props.push_str(&format!("\n{}opacity: {},", ind, element.opacity));
    }
    if element.rotation != 0.0 {
        props.push_str(&format!("\n{}transform: 'rotate({}deg)',", ind, element.rotation));
    }
    if element.stroke_color != "transparent" && element.stroke_width > 0.0 {
        props.push_str(&format!(
            "\n{}border: '{}px solid {}',",
            ind, element.stroke_width, element.stroke_color
        ));
    }
    if element.border_radius > 0.0 {
        props.push_str(&format!("\n{}borderRadius: '{}px',", ind, element.border_radius));
    }
    if element.element_type == "text" {
        props.push_str(&format!(
            "\n{}fontSize: '{}px',\n{}fontFamily: '{}',\n{}display: 'flex',\n{}alignItems: 'center',",
            ind, element.font_size, ind, element.font_family, ind, ind
        ));
    }

    props
}
