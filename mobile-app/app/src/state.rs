//! UI state around the chat store. Secrets stay inside `settings.json`.

use std::path::PathBuf;

use omni_core::{storage_dir, Store};
use tokio::sync::watch;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Chats,
    Thread,
    Settings,
}

pub struct Ui {
    pub tab: Tab,
    pub draft: String,
    pub query: String,
    pub toast: String,
    pub model_sheet: bool,
    pub search_chip: bool,
    pub code_mode: bool,
    pub show_keys: bool,
    pub confirm_reset: bool,
    pub renaming: bool,
    pub rename_draft: String,
    pub pending_delete: Option<String>,
    pub custom_model_id: String,
    pub custom_model_name: String,
    /// When true, new tokens keep the thread pinned to the bottom.
    pub follow: bool,
}

impl Default for Ui {
    fn default() -> Self {
        Self {
            tab: Tab::Chats,
            draft: String::new(),
            query: String::new(),
            toast: String::new(),
            model_sheet: false,
            search_chip: false,
            code_mode: false,
            show_keys: false,
            confirm_reset: false,
            renaming: false,
            rename_draft: String::new(),
            pending_delete: None,
            custom_model_id: String::new(),
            custom_model_name: String::new(),
            follow: true,
        }
    }
}

pub struct AppState {
    pub store: Store,
    pub data_dir: PathBuf,
    pub ui: Ui,
    pub cancel: Option<watch::Sender<bool>>,
    pub generation: u64,
}

impl AppState {
    pub fn load() -> Self {
        let data_dir = storage_dir();
        let store = Store::load(&data_dir);
        Self {
            store,
            data_dir,
            ui: Ui::default(),
            cancel: None,
            generation: 0,
        }
    }

    pub fn persist(&mut self) {
        if let Err(err) = self.store.save(&self.data_dir) {
            self.ui.toast = err;
        }
    }
}
