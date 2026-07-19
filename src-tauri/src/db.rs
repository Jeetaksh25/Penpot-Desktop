use rusqlite::{Connection, params};
use crate::{DesignDocument, DesignElement};

pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn new() -> Result<Self, String> {
        let conn = Connection::open("penpot_desktop.db")
            .map_err(|e| format!("Failed to open database: {}", e))?;

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS documents (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                width REAL NOT NULL DEFAULT 1920,
                height REAL NOT NULL DEFAULT 1080,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS elements (
                id TEXT PRIMARY KEY,
                doc_id TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT 'Element',
                element_type TEXT NOT NULL DEFAULT 'rectangle',
                x REAL NOT NULL DEFAULT 0,
                y REAL NOT NULL DEFAULT 0,
                width REAL NOT NULL DEFAULT 100,
                height REAL NOT NULL DEFAULT 100,
                rotation REAL NOT NULL DEFAULT 0,
                fill_color TEXT NOT NULL DEFAULT '#4A90D9',
                stroke_color TEXT NOT NULL DEFAULT 'transparent',
                stroke_width REAL NOT NULL DEFAULT 0,
                opacity REAL NOT NULL DEFAULT 1,
                border_radius REAL NOT NULL DEFAULT 0,
                text_content TEXT NOT NULL DEFAULT '',
                font_size REAL NOT NULL DEFAULT 16,
                font_family TEXT NOT NULL DEFAULT 'Inter',
                z_index INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE
            );"
        ).map_err(|e| format!("Failed to create tables: {}", e))?;

        Ok(Database { conn })
    }

    pub fn save_document(&self, doc: &DesignDocument) -> Result<(), String> {
        self.conn.execute(
            "INSERT OR REPLACE INTO documents (id, name, width, height, updated_at) 
             VALUES (?1, ?2, ?3, ?4, CURRENT_TIMESTAMP)",
            params![doc.id, doc.name, doc.width, doc.height],
        ).map_err(|e| format!("Failed to save document: {}", e))?;

        // Save all elements
        for element in &doc.elements {
            self.conn.execute(
                "INSERT OR REPLACE INTO elements (id, doc_id, name, element_type, x, y, width, height, 
                 rotation, fill_color, stroke_color, stroke_width, opacity, border_radius, 
                 text_content, font_size, font_family, z_index)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)",
                params![
                    element.id, doc.id, element.name, element.element_type,
                    element.x, element.y, element.width, element.height,
                    element.rotation, element.fill_color, element.stroke_color,
                    element.stroke_width, element.opacity, element.border_radius,
                    element.text_content, element.font_size, element.font_family,
                    element.z_index
                ],
            ).map_err(|e| format!("Failed to save element: {}", e))?;
        }

        Ok(())
    }

    pub fn get_document(&self, id: &str) -> Result<Option<DesignDocument>, String> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, width, height FROM documents WHERE id = ?1"
        ).map_err(|e| format!("Failed to prepare query: {}", e))?;

        let doc_result = stmt.query_row(params![id], |row| {
            Ok(DesignDocument {
                id: row.get(0)?,
                name: row.get(1)?,
                width: row.get(2)?,
                height: row.get(3)?,
                elements: Vec::new(),
            })
        });

        match doc_result {
            Ok(mut doc) => {
                let elements = self.get_elements(&doc.id)?;
                doc.elements = elements;
                Ok(Some(doc))
            }
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Failed to get document: {}", e)),
        }
    }

    pub fn list_documents(&self) -> Result<Vec<DesignDocument>, String> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, width, height FROM documents ORDER BY updated_at DESC"
        ).map_err(|e| format!("Failed to prepare query: {}", e))?;

        let docs = stmt.query_map([], |row| {
            Ok(DesignDocument {
                id: row.get(0)?,
                name: row.get(1)?,
                width: row.get(2)?,
                height: row.get(3)?,
                elements: Vec::new(),
            })
        }).map_err(|e| format!("Failed to query documents: {}", e))?;

        let mut result = Vec::new();
        for doc in docs {
            let mut doc = doc.map_err(|e| format!("Failed to read document: {}", e))?;
            doc.elements = self.get_elements(&doc.id)?;
            result.push(doc);
        }
        Ok(result)
    }

    pub fn add_element(&self, doc_id: &str, element: &DesignElement) -> Result<(), String> {
        self.conn.execute(
            "INSERT INTO elements (id, doc_id, name, element_type, x, y, width, height, 
             rotation, fill_color, stroke_color, stroke_width, opacity, border_radius, 
             text_content, font_size, font_family, z_index)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)",
            params![
                element.id, doc_id, element.name, element.element_type,
                element.x, element.y, element.width, element.height,
                element.rotation, element.fill_color, element.stroke_color,
                element.stroke_width, element.opacity, element.border_radius,
                element.text_content, element.font_size, element.font_family,
                element.z_index
            ],
        ).map_err(|e| format!("Failed to add element: {}", e))?;
        Ok(())
    }

    pub fn update_element(&self, doc_id: &str, element: &DesignElement) -> Result<(), String> {
        self.conn.execute(
            "UPDATE elements SET name=?3, element_type=?4, x=?5, y=?6, width=?7, height=?8,
             rotation=?9, fill_color=?10, stroke_color=?11, stroke_width=?12, opacity=?13,
             border_radius=?14, text_content=?15, font_size=?16, font_family=?17, z_index=?18
             WHERE id=?1 AND doc_id=?2",
            params![
                element.id, doc_id, element.name, element.element_type,
                element.x, element.y, element.width, element.height,
                element.rotation, element.fill_color, element.stroke_color,
                element.stroke_width, element.opacity, element.border_radius,
                element.text_content, element.font_size, element.font_family,
                element.z_index
            ],
        ).map_err(|e| format!("Failed to update element: {}", e))?;
        Ok(())
    }

    pub fn delete_element(&self, doc_id: &str, element_id: &str) -> Result<(), String> {
        self.conn.execute(
            "DELETE FROM elements WHERE id=?1 AND doc_id=?2",
            params![element_id, doc_id],
        ).map_err(|e| format!("Failed to delete element: {}", e))?;
        Ok(())
    }

    fn get_elements(&self, doc_id: &str) -> Result<Vec<DesignElement>, String> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, element_type, x, y, width, height, rotation, fill_color, 
             stroke_color, stroke_width, opacity, border_radius, text_content, font_size, 
             font_family, z_index FROM elements WHERE doc_id = ?1 ORDER BY z_index ASC"
        ).map_err(|e| format!("Failed to prepare query: {}", e))?;

        let elements = stmt.query_map(params![doc_id], |row| {
            Ok(DesignElement {
                id: row.get(0)?,
                name: row.get(1)?,
                element_type: row.get(2)?,
                x: row.get(3)?,
                y: row.get(4)?,
                width: row.get(5)?,
                height: row.get(6)?,
                rotation: row.get(7)?,
                fill_color: row.get(8)?,
                stroke_color: row.get(9)?,
                stroke_width: row.get(10)?,
                opacity: row.get(11)?,
                border_radius: row.get(12)?,
                text_content: row.get(13)?,
                font_size: row.get(14)?,
                font_family: row.get(15)?,
                z_index: row.get(16)?,
            })
        }).map_err(|e| format!("Failed to query elements: {}", e))?;

        let mut result = Vec::new();
        for element in elements {
            result.push(element.map_err(|e| format!("Failed to read element: {}", e))?);
        }
        Ok(result)
    }
}
