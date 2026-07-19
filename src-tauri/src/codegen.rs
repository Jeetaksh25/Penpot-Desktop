use crate::{DesignElement, CodegenOptions};

/// Shared utilities for code generation
pub fn sanitize_name(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_alphanumeric() || c == '_' { c } else { '_' })
        .collect::<String>()
        .trim_matches('_')
        .to_string()
}

pub fn to_pascal_case(name: &str) -> String {
    let sanitized = sanitize_name(name);
    let mut result = String::new();
    let mut next_upper = true;
    for c in sanitized.chars() {
        if c == '_' || c == ' ' || c == '-' {
            next_upper = true;
        } else if next_upper {
            result.push(c.to_ascii_uppercase());
            next_upper = false;
        } else {
            result.push(c);
        }
    }
    result
}

pub fn to_camel_case(name: &str) -> String {
    let pascal = to_pascal_case(name);
    let mut chars = pascal.chars();
    match chars.next() {
        Some(c) => c.to_ascii_lowercase().to_string() + chars.as_str(),
        None => String::new(),
    }
}

// Style utilities are handled inline in codegen_react.rs::generate_style_props()
// and codegen_winui.rs for each framework's specific format.
