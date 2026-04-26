'use strict';

/* ══════════════════════════════════════════════════
   কনফিগ
══════════════════════════════════════════════════ */
const DEFAULT_API_KEY = 'AIzaSyBx4VM-fLeKI_DWbb1E27EEQUsshLs0Gx4';
const GEMINI_MODEL    = 'gemini-3.1-flash-lite-preview';

function getApiKey()    { return localStorage.getItem('vn-api-key') || DEFAULT_API_KEY; }
function getGeminiUrl() { return `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${getApiKey()}`; }

/* ══════════════════════════════════════════════════
   Supabase কনফিগ
══════════════════════════════════════════════════ */
const SB_URL = 'https://iqjajofqaimnqvrxgdzc.supabase.co';
const SB_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxamFqb2ZxYWltbnF2cnhnZHpjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMDM0NzYsImV4cCI6MjA4Njc3OTQ3Nn0.epEV-GGXUsJe-u4Aumx284qGPrCFZcuXFGN9qmtwwbM';
let sb = null; // Supabase client instance

const TRANSCRIBE_PROMPT =
  'এই অডিওতে বাংলায় কথা বলা হয়েছে।\n' +
  'নির্দেশনা:\n' +
  '১. হুবহু যা বলা হয়েছে ঠিক তাই বাংলায় লিখবে — একটি শব্দও বাড়াবে না, বাদ দেবে না, পরিবর্তন করবে না।\n' +
  '২. নিজের পক্ষ থেকে কোনো মন্তব্য, ব্যাখ্যা বা অতিরিক্ত কিছু যোগ করবে না।\n' +
  '৩. উপযুক্ত জায়গায় দাড়ি (।), কমা (,), প্রশ্নবোধক চিহ্ন (?), বিস্ময়বোধক চিহ্ন (!) যোগ করবে।\n' +
  '৪. শুধু ট্রান্সক্রিপশনের টেক্সটটুকু দাও, আর কিছু লিখো না।';

/* ══════════════════════════════════════════════════
   অবস্থা
══════════════════════════════════════════════════ */
let notes           = {};
let folders         = {};
let currentNoteId   = null;
let currentFolderId = null; // কোন ফোল্ডারে আছি (null = root)
let pendingFolderId = null; // নতুন নোটের জন্য প্রি-অ্যাসাইন ফোল্ডার
let activeFolderActionId = null; // folder options sheet এর জন্য
let activeNoteActionId   = null; // note options sheet
let listSort = 'updated_desc';  // localStorage: vn-list-sort

let micStream      = null;
let mediaRecorder  = null;
let audioChunks    = [];
let segmentCount   = 0;
let isRecording    = false;
let isProcessing   = false;
let mimeType       = '';
let timerInterval  = null;
let timerSeconds   = 0;
let autoSaveTimer  = null;
let savedSelection = null;

/* ══════════════════════════════════════════════════
   DOM রেফারেন্স
══════════════════════════════════════════════════ */
const $ = id => document.getElementById(id);
/** null এলিমেন্টে addEventListener — ক্র্যাশ এড়ানো */
function on(el, type, fn) {
  if (el && typeof fn === 'function') el.addEventListener(type, fn);
}

const screenList          = $('screen-list');
const screenEditor        = $('screen-editor');
const screenSettings      = $('screen-settings');
const rootHeader          = $('root-header');
const folderHeader        = $('folder-header');
const notesList           = $('notes-list');
const emptyState          = $('empty-state');
const emptyIcon           = $('empty-icon');
const emptyTitle          = $('empty-title');
const emptyHint           = $('empty-hint');
const newNoteBtn          = $('new-note-btn');
const newFolderBtn        = $('new-folder-btn');
const settingsBtn         = $('settings-btn');
const folderBackBtn       = $('folder-back-btn');
const folderNameDisplay   = $('folder-name-display');
const renameFolderBtn     = $('rename-folder-btn');
const newNoteInFolderBtn  = $('new-note-in-folder-btn');
const backBtn             = $('back-btn');
const deleteNoteBtn       = $('delete-note-btn');
const noteTitleDisplay    = $('note-title-display');
const saveStatus          = $('save-status');
const editorFolderChip    = $('editor-folder-chip');
const editorFolderName    = $('editor-folder-name');
const noteTextarea        = $('note-textarea');
const segmentsDots        = $('segments-dots');
const segmentsText        = $('segments-text');
const clearRecBtn         = $('clear-rec-btn');
const recBtn              = $('rec-btn');
const doneBtn             = $('done-btn');
const recTimer            = $('rec-timer');
const replaceBanner       = $('replace-banner');
const cancelReplaceBtn    = $('cancel-replace-btn');
const processingOverlay   = $('processing-overlay');
const processingText      = $('processing-text');
const toastEl             = $('toast');

// ফোল্ডার মোডাল
const folderFormModal     = $('folder-form-modal');
const folderFormTitle     = $('folder-form-title');
const folderNameInput     = $('folder-name-input');
const folderFormSave      = $('folder-form-save');
const folderFormClose     = $('folder-form-close');
const folderFormCancel    = $('folder-form-cancel');

// ফোল্ডার অপশন শিট
const folderActionSheet   = $('folder-action-sheet');
const folderActionTitle   = $('folder-action-title');
const folderActionRename  = $('folder-action-rename');
const folderActionDelete  = $('folder-action-delete');
const folderActionCancel  = $('folder-action-cancel');

// Move-to-folder শিট
const moveSheet           = $('move-sheet');
const moveFolderList      = $('move-folder-list');
const moveSheetClose      = $('move-sheet-close');
const moveSheetTitleEl    = $('move-sheet-title');
const moveCurrentHint     = $('move-sheet-current');
const sortSheet           = $('sort-sheet');
const sortSheetClose      = $('sort-sheet-close');
const listSortBtn         = $('list-sort-btn');
const listSortLabel       = $('list-sort-label');
const settingsBackBtn     = $('settings-back-btn');
const settingsSortSummary = $('settings-sort-summary');
const noteActionSheet     = $('note-action-sheet');
const noteActionTitle     = $('note-action-title');
const noteActionRename    = $('note-action-rename');
const noteActionMove      = $('note-action-move');
const noteActionDelete    = $('note-action-delete');
const noteActionCancel    = $('note-action-cancel');
const noteFormModal       = $('note-form-modal');
const noteFormTitle       = $('note-form-title');
const noteNameInput       = $('note-name-input');
const noteFormSave        = $('note-form-save');
const noteFormClose       = $('note-form-close');
const noteFormCancel      = $('note-form-cancel');

// সেটিংস পেজ (API Key)
const apiKeyInput         = $('api-key-input');
const toggleEyeBtn        = $('toggle-eye-btn');
const keyStatus           = $('key-status');
const resetKeyBtn         = $('reset-key-btn');
const saveKeyBtn          = $('save-key-btn');

/* ══════════════════════════════════════════════════
   ডেটা লোড / সেভ (Hybrid: localStorage + Supabase)
══════════════════════════════════════════════════ */

/* ── লোকাল ক্যাশ (instant) ─────────────────────── */
function loadLocalCache() {
  try { notes   = JSON.parse(localStorage.getItem('vn-notes')   || '{}'); } catch { notes   = {}; }
  try { folders = JSON.parse(localStorage.getItem('vn-folders') || '{}'); } catch { folders = {}; }
}

function saveLocalCache() {
  try {
    localStorage.setItem('vn-notes',   JSON.stringify(notes));
    localStorage.setItem('vn-folders', JSON.stringify(folders));
  } catch { showToast('লোকাল স্টোরেজ পূর্ণ!', 'error'); }
}

/* ── Supabase sync (background) ─────────────────── */
async function initSupabase() {
  try {
    sb = window.supabase.createClient(SB_URL, SB_KEY);
    await pullFromSupabase(); // প্রথমবার cloud থেকে টেনে আনো
  } catch (err) {
    console.warn('[Supabase] Init failed, using local cache:', err.message);
    showToast('Cloud sync বন্ধ — লোকাল ডেটা ব্যবহার হচ্ছে', 'warning');
  }
}

async function pullFromSupabase() {
  if (!sb) return;
  const [{ data: nd, error: ne }, { data: fd, error: fe }] = await Promise.all([
    sb.from('vn_notes').select('*').order('updated_at', { ascending: false }),
    sb.from('vn_folders').select('*').order('name'),
  ]);
  if (ne || fe) throw new Error((ne || fe).message);

  // Cloud data → in-memory
  notes = {};
  for (const n of nd || []) {
    notes[n.id] = {
      title: n.title, content: n.content,
      folderId: n.folder_id || null,
      created: n.created_at, updated: n.updated_at,
      titleCustom: n.title_is_custom === true,
    };
  }
  folders = {};
  for (const f of fd || []) {
    folders[f.id] = { name: f.name, created: f.created_at };
  }

  saveLocalCache(); // localStorage-ও আপডেট করো
  updateListHeader();
  renderNotesList();
  console.log('[Supabase] Pulled:', Object.keys(notes).length, 'notes,', Object.keys(folders).length, 'folders');
}

/* ── individual sync helpers ────────────────────── */
function persistNotes()   { saveLocalCache(); }
function persistFolders() { saveLocalCache(); }

function sbUpsertNote(id) {
  if (!sb || !notes[id]) return;
  const n = notes[id];
  sb.from('vn_notes').upsert({
    id, title: n.title, content: n.content,
    folder_id: n.folderId || null,
    created_at: n.created, updated_at: n.updated,
    title_is_custom: n.titleCustom === true,
  }, { onConflict: 'id' }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert note:', error.message);
  });
}

function sbDeleteNote(id) {
  if (!sb) return;
  sb.from('vn_notes').delete().eq('id', id).then(({ error }) => {
    if (error) console.warn('[Supabase] delete note:', error.message);
  });
}

function sbUpsertFolder(id) {
  if (!sb || !folders[id]) return;
  const f = folders[id];
  sb.from('vn_folders').upsert({
    id, name: f.name, created_at: f.created,
  }, { onConflict: 'id' }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert folder:', error.message);
  });
}

function sbDeleteFolder(id) {
  if (!sb) return;
  sb.from('vn_folders').delete().eq('id', id).then(({ error }) => {
    if (error) console.warn('[Supabase] delete folder:', error.message);
  });
}

/* ══════════════════════════════════════════════════
   সহায়ক
══════════════════════════════════════════════════ */
function makeTitle(content) {
  for (const line of content.split('\n')) {
    const t = line.trim();
    if (t) return t.slice(0, 38) + (t.length > 38 ? '…' : '');
  }
  return 'নতুন নোট';
}

/** তালিকা ও এডিটরে দেখানোর শিরোনাম (কাস্টম না থাকলে কনটেন্ট থেকে) */
function getListTitle(note) {
  if (note && note.titleCustom) return (note.title && note.title.trim()) || 'নতুন নোট';
  if (note) return makeTitle(note.content || '') || note.title || 'নতুন নোট';
  return 'নতুন নোট';
}

function getEditorDisplayTitle() {
  if (currentNoteId && notes[currentNoteId]?.titleCustom) {
    return (notes[currentNoteId].title && notes[currentNoteId].title.trim()) || 'নতুন নোট';
  }
  return makeTitle(noteTextarea.value) || 'নতুন নোট';
}

function sortNoteEntries(entries) {
  const titleOf = n => getListTitle(n);
  return entries.slice().sort(([, a], [, b]) => {
    const cA = a.created || a.updated, cB = b.created || b.updated;
    switch (listSort) {
      case 'updated_asc':  return a.updated.localeCompare(b.updated);
      case 'created_desc': return cB.localeCompare(cA);
      case 'created_asc':  return cA.localeCompare(cB);
      case 'title_asc':    return titleOf(a).localeCompare(titleOf(b), 'bn');
      case 'title_desc':   return titleOf(b).localeCompare(titleOf(a), 'bn');
      case 'updated_desc':
      default:             return b.updated.localeCompare(a.updated);
    }
  });
}

const SORT_LABELS = {
  updated_desc: 'আপডেট · নতুন আগে',
  updated_asc:  'আপডেট · পুরনো আগে',
  created_desc: 'তৈরি · নতুন আগে',
  created_asc:  'তৈরি · পুরনো আগে',
  title_asc:    'শিরোনাম ক → হ',
  title_desc:   'শিরোনাম হ → ক',
};

function getSortLabel() {
  return SORT_LABELS[listSort] || SORT_LABELS.updated_desc;
}

function updateListSortButton() {
  if (listSortLabel) listSortLabel.textContent = getSortLabel();
  if (settingsSortSummary) {
    settingsSortSummary.textContent = 'বর্তমান: ' + getSortLabel() + ' — তালিকার সর্ট বাটন থেকেও বদলান।';
  }
}

function normFolderId(v) {
  if (v == null || v === '') return null;
  return v;
}

function fmtDate(iso) {
  try {
    const dt  = new Date(iso);
    const now = new Date();
    const d   = Math.floor((now - dt) / 86400000);
    const bn  = ['রবি','সোম','মঙ্গল','বুধ','বৃহ','শুক্র','শনি'];
    const hh  = dt.getHours(), mm = String(dt.getMinutes()).padStart(2,'0');
    const t   = `${hh%12||12}:${mm} ${hh>=12?'PM':'AM'}`;
    if (d===0) return `আজ ${t}`;
    if (d<7)   return `${bn[dt.getDay()]} ${t}`;
    return dt.toLocaleDateString('bn-BD',{day:'numeric',month:'short',year:'numeric'});
  } catch { return ''; }
}

function esc(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

/* ══════════════════════════════════════════════════
   নোট CRUD
══════════════════════════════════════════════════ */
function saveCurrentNote(silent = false) {
  const content = noteTextarea.value.trim();
  if (!content) return;

  const now = new Date().toISOString();
  if (!currentNoteId) {
    currentNoteId = crypto.randomUUID();
    notes[currentNoteId] = {
      title: '', content: '', titleCustom: false,
      created: now, updated: now,
      folderId: pendingFolderId,
    };
    pendingFolderId = null;
    updateEditorFolderChip();
  }

  const prev = notes[currentNoteId];
  const useCustomTitle = prev.titleCustom === true;
  const derivedTitle   = makeTitle(content) || 'নতুন নোট';

  notes[currentNoteId] = {
    ...prev,
    content: noteTextarea.value,
    title:   useCustomTitle ? ((prev.title && prev.title.trim()) || 'নতুন নোট') : derivedTitle,
    updated: now,
  };
  persistNotes();
  sbUpsertNote(currentNoteId); // Supabase background sync

  if (!silent) {
    saveStatus.textContent = '✓ সেভ';
    saveStatus.classList.add('visible');
    setTimeout(() => { saveStatus.textContent=''; saveStatus.classList.remove('visible'); }, 2000);
  }
}

function deleteCurrentNote() {
  if (currentNoteId) {
    if (!confirm('এই নোটটি স্থায়ীভাবে মুছে ফেলবেন?')) return;
    const deletedId = currentNoteId;
    delete notes[deletedId];
    persistNotes();
    sbDeleteNote(deletedId); // Supabase sync
  }
  currentNoteId = null;
  resetRecording();
  goToList();
}

function deleteNote(id) {
  if (!confirm('এই নোটটি স্থায়ীভাবে মুছে ফেলবেন?')) return;
  delete notes[id];
  persistNotes();
  sbDeleteNote(id); // Supabase sync
  if (currentNoteId === id) currentNoteId = null;
  renderNotesList();
}

function setNoteName(id, rawName) {
  if (!notes[id]) return;
  const t = (rawName || '').trim() || 'নতুন নোট';
  const now = new Date().toISOString();
  notes[id] = { ...notes[id], title: t, titleCustom: true, updated: now };
  persistNotes();
  sbUpsertNote(id);
  if (currentNoteId === id) noteTitleDisplay.textContent = getEditorDisplayTitle();
  renderNotesList();
}

let noteNameTargetId = null;
function openNoteNameModal(targetId) {
  const id = targetId != null ? targetId : currentNoteId;
  if (!id || !notes[id]) {
    showToast('নোট পাওয়া যায়নি', 'warning');
    return;
  }
  noteNameTargetId = id;
  noteNameInput.value = getListTitle(notes[id]);
  noteFormModal.classList.remove('hidden');
  setTimeout(() => { noteNameInput.focus(); noteNameInput.select(); }, 300);
}
function closeNoteNameModal() { noteFormModal.classList.add('hidden'); }

function showNoteActionSheet(id) {
  if (!notes[id] || !notes[id].content?.trim()) return;
  activeNoteActionId   = id;
  noteActionTitle.textContent = getListTitle(notes[id]);
  noteActionSheet.classList.remove('hidden');
}

/* ══════════════════════════════════════════════════
   ফোল্ডার CRUD
══════════════════════════════════════════════════ */
function createFolder(name) {
  const id  = crypto.randomUUID();
  const now = new Date().toISOString();
  folders[id] = { name: name.trim(), created: now };
  persistFolders();
  sbUpsertFolder(id); // Supabase sync
  return id;
}

function renameFolder(id, newName) {
  if (!folders[id]) return;
  folders[id].name = newName.trim();
  persistFolders();
  sbUpsertFolder(id); // Supabase sync
}

function deleteFolder(id) {
  for (const [noteId, note] of Object.entries(notes)) {
    if (note.folderId === id) {
      note.folderId = null;
      sbUpsertNote(noteId); // folder_id null করে Supabase-এ আপডেট
    }
  }
  persistNotes();
  delete folders[id];
  persistFolders();
  sbDeleteFolder(id); // Supabase sync
  if (currentFolderId === id) {
    currentFolderId = null;
    updateListHeader();
  }
}

function moveNoteToFolder(noteId, folderId) {
  if (!notes[noteId]) return;
  notes[noteId].folderId = folderId || null;
  notes[noteId].updated  = new Date().toISOString();
  persistNotes();
  sbUpsertNote(noteId); // Supabase sync
}

/* ══════════════════════════════════════════════════
   স্ক্রিন / নেভিগেশন
══════════════════════════════════════════════════ */
function goToList() {
  screenEditor.classList.remove('active');
  renderNotesList();
}

function openFolder(id) {
  if (!folders[id]) return;
  currentFolderId = id;
  updateListHeader();
  renderNotesList();
  history.pushState({ screen: 'folder', id }, '');
}

function closeFolder() {
  currentFolderId = null;
  updateListHeader();
  renderNotesList();
}

function updateListHeader() {
  if (currentFolderId && folders[currentFolderId]) {
    rootHeader.classList.add('hidden');
    folderHeader.classList.remove('hidden');
    folderNameDisplay.textContent = folders[currentFolderId].name;
  } else {
    currentFolderId = null;
    rootHeader.classList.remove('hidden');
    folderHeader.classList.add('hidden');
  }
}

/* ══════════════════════════════════════════════════
   নোট লিস্ট রেন্ডার
══════════════════════════════════════════════════ */
function renderNotesList() {
  notesList.innerHTML = '';

  if (currentFolderId) {
    // ফোল্ডার ভিউ — শুধু এই ফোল্ডারের নোট
    const items = sortNoteEntries(
      Object.entries(notes)
        .filter(([, n]) => n.content.trim() && n.folderId === currentFolderId),
    );

    if (!items.length) {
      showEmptyState('📁', 'ফোল্ডারে কোনো নোট নেই', 'উপরে + বাটন চাপুন');
      return;
    }
    hideEmptyState();
    for (const [id, note] of items) notesList.appendChild(buildNoteCard(id, note));
    return;
  }

  // রুট ভিউ — ফোল্ডার + আনফাইলড নোট
  const folderList   = Object.entries(folders).sort(([,a],[,b]) => a.name.localeCompare(b.name));
  const unfiledNotes = sortNoteEntries(
    Object.entries(notes)
      .filter(([, n]) => n.content.trim() && !n.folderId),
  );

  const hasFolders = folderList.length > 0;
  const hasNotes   = unfiledNotes.length > 0;

  if (!hasFolders && !hasNotes) {
    showEmptyState('🎙️', 'কোনো নোট নেই', 'উপরে + বাটন চাপুন\nনতুন নোট তৈরি করতে');
    return;
  }
  hideEmptyState();

  if (hasFolders) {
    addSectionLabel('ফোল্ডার');
    for (const [id, folder] of folderList) {
      const count = Object.values(notes).filter(n => n.folderId===id && n.content.trim()).length;
      notesList.appendChild(buildFolderCard(id, folder, count));
    }
  }

  if (hasNotes) {
    if (hasFolders) addSectionLabel('নোট');
    for (const [id, note] of unfiledNotes) notesList.appendChild(buildNoteCard(id, note));
  }
}

function showEmptyState(icon, title, hint) {
  emptyIcon.textContent  = icon;
  emptyTitle.textContent = title;
  emptyHint.innerHTML    = hint.replace('\n', '<br>');
  emptyState.classList.remove('hidden');
  notesList.classList.add('hidden');
}

function hideEmptyState() {
  emptyState.classList.add('hidden');
  notesList.classList.remove('hidden');
}

function addSectionLabel(text) {
  const el = document.createElement('div');
  el.className   = 'list-section-label';
  el.textContent = text;
  notesList.appendChild(el);
}

/* ── ফোল্ডার কার্ড ─────────────────────────────── */
function buildFolderCard(id, folder, noteCount) {
  const card = document.createElement('div');
  card.className = 'folder-card';
  card.innerHTML = `
    <div class="folder-card-body">
      <div class="folder-card-icon">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="#4a90d9">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
        </svg>
      </div>
      <div class="folder-card-info">
        <div class="folder-card-name">${esc(folder.name)}</div>
        <div class="folder-card-count">${noteCount}টি নোট</div>
      </div>
    </div>
    <button class="folder-options-btn" aria-label="অপশন">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/>
      </svg>
    </button>`;

  card.querySelector('.folder-card-body').addEventListener('click', () => openFolder(id));
  card.querySelector('.folder-options-btn').addEventListener('click', e => {
    e.stopPropagation();
    showFolderActionSheet(id);
  });
  return card;
}

/* ── নোট কার্ড ──────────────────────────────────── */
function buildNoteCard(id, note) {
  const preview = note.content.replace(/\n/g,' ').trim().slice(0, 90);
  const folderTag = note.folderId && folders[note.folderId]
    ? `<span class="note-folder-tag">📁 ${esc(folders[note.folderId].name)}</span>` : '';

  const card = document.createElement('div');
  card.className = 'note-card';
  const listTitle = getListTitle(note);
  card.innerHTML = `
    <div class="note-card-accent"></div>
    <div class="note-card-body">
      <div class="note-card-title">${esc(listTitle)}</div>
      <div class="note-card-date">${fmtDate(note.updated)}${folderTag}</div>
      ${preview ? `<div class="note-card-preview">${esc(preview)}</div>` : ''}
    </div>
    <button class="note-options-btn" aria-label="অপশন">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/>
      </svg>
    </button>`;

  card.querySelector('.note-card-body').addEventListener('click', () => openNote(id));
  card.querySelector('.note-options-btn').addEventListener('click', e => {
    e.stopPropagation();
    showNoteActionSheet(id);
  });
  return card;
}

/* ══════════════════════════════════════════════════
   নোট এডিটর খোলা
══════════════════════════════════════════════════ */
function openNote(id) {
  saveCurrentNote(true);
  currentNoteId   = id;
  pendingFolderId = null;
  const note = notes[id] || {};

  noteTextarea.value           = note.content || '';
  noteTitleDisplay.textContent = getListTitle(note);
  saveStatus.textContent       = '';
  saveStatus.classList.remove('visible');
  noteTextarea.scrollTop = noteTextarea.scrollHeight;

  updateEditorFolderChip();
  resetRecording();

  requestAnimationFrame(() => {
    screenEditor.classList.add('active');
    history.pushState({ screen: 'editor' }, '');
    setTimeout(() => noteTextarea.focus(), 340);
  });
}

function openNewNote(inFolderId = null) {
  saveCurrentNote(true);
  currentNoteId   = null;
  pendingFolderId = inFolderId;

  noteTextarea.value           = '';
  noteTitleDisplay.textContent = 'নতুন নোট';
  saveStatus.textContent       = '';
  saveStatus.classList.remove('visible');

  updateEditorFolderChip();
  resetRecording();

  requestAnimationFrame(() => {
    screenEditor.classList.add('active');
    history.pushState({ screen: 'editor' }, '');
    setTimeout(() => noteTextarea.focus(), 340);
  });
}

function updateEditorFolderChip() {
  if (!editorFolderChip || !editorFolderName) return;
  const fid = currentNoteId ? notes[currentNoteId]?.folderId : pendingFolderId;
  editorFolderChip.classList.remove('hidden');

  if (fid && folders[fid]) {
    editorFolderName.textContent = folders[fid].name;
    editorFolderChip.classList.add('has-folder');
  } else {
    editorFolderName.textContent = 'ফোল্ডারে রাখুন';
    editorFolderChip.classList.remove('has-folder');
  }
}

/* ══════════════════════════════════════════════════
   ফোল্ডার অপশন শিট
══════════════════════════════════════════════════ */
function showFolderActionSheet(id) {
  if (!folderActionSheet || !folderActionTitle) return;
  activeFolderActionId       = id;
  folderActionTitle.textContent = folders[id]?.name || 'ফোল্ডার';
  folderActionSheet.classList.remove('hidden');
}

on(folderActionRename, 'click', () => {
  if (folderActionSheet) folderActionSheet.classList.add('hidden');
  openFolderFormModal('rename', activeFolderActionId);
});

on(folderActionDelete, 'click', () => {
  if (folderActionSheet) folderActionSheet.classList.add('hidden');
  const name = folders[activeFolderActionId]?.name || 'ফোল্ডার';
  const count = Object.values(notes).filter(n => n.folderId === activeFolderActionId).length;
  const msg = count > 0
    ? `"${name}" ফোল্ডারটি মুছলে এর ${count}টি নোট মূল তালিকায় চলে যাবে। মুছবেন?`
    : `"${name}" ফোল্ডারটি মুছে ফেলবেন?`;
  if (confirm(msg)) {
    deleteFolder(activeFolderActionId);
    renderNotesList();
    showToast('ফোল্ডার মুছে গেছে');
  }
});

on(folderActionCancel, 'click', () => { if (folderActionSheet) folderActionSheet.classList.add('hidden'); });
on(folderActionSheet, 'click', e => { if (e.target === folderActionSheet) folderActionSheet.classList.add('hidden'); });

/* ══════════════════════════════════════════════════
   ফোল্ডার ফর্ম মোডাল (create / rename)
══════════════════════════════════════════════════ */
let folderFormMode     = 'create';
let folderFormTargetId = null;

function openFolderFormModal(mode, id = null) {
  if (!folderFormModal || !folderNameInput || !folderFormTitle || !folderFormSave) return;
  folderFormMode     = mode;
  folderFormTargetId = id;

  if (mode === 'create') {
    folderFormTitle.textContent   = 'নতুন ফোল্ডার';
    folderNameInput.value         = '';
    folderFormSave.textContent    = 'তৈরি করুন';
  } else {
    folderFormTitle.textContent   = 'ফোল্ডারের নাম পরিবর্তন';
    folderNameInput.value         = folders[id]?.name || '';
    folderFormSave.textContent    = 'সেভ করুন';
  }

  folderFormModal.classList.remove('hidden');
  setTimeout(() => { folderNameInput.focus(); folderNameInput.select(); }, 300);
}

function closeFolderFormModal() { if (folderFormModal) folderFormModal.classList.add('hidden'); }

on(folderFormSave, 'click', () => {
  if (!folderNameInput) return;
  const name = folderNameInput.value.trim();
  if (!name) { showToast('ফোল্ডারের নাম দিন', 'warning'); return; }

  if (folderFormMode === 'create') {
    createFolder(name);
    renderNotesList();
    showToast(`✓ "${name}" ফোল্ডার তৈরি হয়েছে`, 'success');
  } else {
    renameFolder(folderFormTargetId, name);
    if (currentFolderId === folderFormTargetId && folderNameDisplay) folderNameDisplay.textContent = name;
    renderNotesList();
    showToast('✓ নাম পরিবর্তন হয়েছে', 'success');
  }
  closeFolderFormModal();
});

on(folderFormClose, 'click',  closeFolderFormModal);
on(folderFormCancel, 'click', closeFolderFormModal);
on(folderFormModal, 'click', e => { if (e.target === folderFormModal) closeFolderFormModal(); });

on(folderNameInput, 'keydown', e => { if (e.key === 'Enter' && folderFormSave) folderFormSave.click(); });

/* ══════════════════════════════════════════════════
   Move-to-folder শিট
   (আর্গ: নির্দিষ্ট নোট ID — তালিকা থেকে; না হলে এডিটরের বর্তমান নোট)
══════════════════════════════════════════════════ */
function showMoveToFolderSheet(fromListId) {
  if (!moveFolderList || !moveSheet) return;
  const noteId = fromListId !== undefined ? fromListId : currentNoteId;
  if (moveSheetTitleEl) {
    moveSheetTitleEl.textContent = noteId ? 'নোট কোন ফোল্ডারে?' : 'ফোল্ডারে রাখুন';
  }
  moveFolderList.innerHTML = '';

  const currentFid = normFolderId(
    noteId ? notes[noteId]?.folderId : pendingFolderId,
  );

  if (moveCurrentHint) {
    if (!noteId) {
      if (pendingFolderId && folders[pendingFolderId]) {
        moveCurrentHint.textContent =
          'বর্তমানে: “' + folders[pendingFolderId].name + '” — সিলেক্ট করা (নতুন নোট)।';
      } else {
        moveCurrentHint.textContent =
          'বর্তমানে: কোনো ফোল্ডারে নেই (মূল তালিকা) — “ফোল্ডার ছাড়া” সিলেক্ট।';
      }
    } else {
      const cf = normFolderId(notes[noteId]?.folderId);
      if (cf && folders[cf]) {
        moveCurrentHint.textContent =
          'বর্তমানে: “' + folders[cf].name + '” — নিচের তালিকায় সেই সারিতে সিলেক্ট দেখা যাবে।';
      } else {
        moveCurrentHint.textContent =
          'বর্তমানে: কোনো ফোল্ডারে নেই (মূল তালিকা) — “ফোল্ডার ছাড়া” সিলেক্ট।';
      }
    }
  }

  const addItem = (fid, label, iconHtml) => {
    const fNorm = normFolderId(fid);
    const isActive = fNorm === currentFid;
    const btn = document.createElement('button');
    btn.className = 'move-folder-item' + (isActive ? ' active' : '');
    btn.innerHTML = `
      ${iconHtml}
      <span>${label}</span>
      ${isActive ? '<svg class="move-check" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#4caf7d" stroke-width="3" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>' : ''}`;

    btn.addEventListener('click', () => {
      if (noteId) {
        moveNoteToFolder(noteId, fid);
      } else {
        pendingFolderId = fid;
      }
      if (moveSheet) moveSheet.classList.add('hidden');
      updateEditorFolderChip();
      renderNotesList();
      showToast(fid ? `✓ "${folders[fid]?.name}" ফোল্ডারে রাখা হয়েছে` : 'ফোল্ডার থেকে সরানো হয়েছে', 'success');
    });
    moveFolderList.appendChild(btn);
  };

  // "ফোল্ডার ছাড়া" অপশন
  addItem(null, 'ফোল্ডার ছাড়া',
    `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
       <line x1="3" y1="8" x2="21" y2="8"/><line x1="3" y1="14" x2="21" y2="14"/><line x1="3" y1="20" x2="21" y2="20"/>
     </svg>`);

  // ফোল্ডারগুলো
  const sortedFolders = Object.entries(folders).sort(([,a],[,b]) => a.name.localeCompare(b.name));
  if (!sortedFolders.length) {
    const hint = document.createElement('p');
    hint.style.cssText = 'color:#6a7a9a;font-size:13px;padding:8px 14px 16px;';
    hint.textContent = 'কোনো ফোল্ডার নেই। প্রথমে ফোল্ডার তৈরি করুন।';
    moveFolderList.appendChild(hint);
  }

  for (const [fid, folder] of sortedFolders) {
    const sel = currentFid === normFolderId(fid);
    addItem(fid, folder.name,
      `<svg width="20" height="20" viewBox="0 0 24 24" fill="${sel?'#4a90d9':'none'}" stroke="${sel?'#4a90d9':'currentColor'}" stroke-width="2" stroke-linecap="round">
         <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
       </svg>`);
  }

  moveSheet.classList.remove('hidden');
}

on(moveSheetClose, 'click', () => { if (moveSheet) moveSheet.classList.add('hidden'); });
on(moveSheet, 'click', e => { if (e.target === moveSheet) moveSheet.classList.add('hidden'); });

/* ══════════════════════════════════════════════════
   সর্ট শিট
══════════════════════════════════════════════════ */
function closeSortSheet() {
  if (sortSheet) sortSheet.classList.add('hidden');
  if (listSortBtn) listSortBtn.setAttribute('aria-expanded', 'false');
}
function openSortSheet() {
  if (!sortSheet) return;
  document.querySelectorAll('.sort-pick').forEach(b => {
    b.classList.toggle('is-active', b.dataset.sort === listSort);
  });
  if (listSortBtn) listSortBtn.setAttribute('aria-expanded', 'true');
  sortSheet.classList.remove('hidden');
}
on(listSortBtn, 'click', openSortSheet);
on(sortSheetClose, 'click', closeSortSheet);
if (sortSheet) {
  sortSheet.addEventListener('click', e => {
    if (e.target === sortSheet) { closeSortSheet(); return; }
    const p = e.target.closest('.sort-pick');
    if (p && p.dataset.sort) {
      listSort = p.dataset.sort;
      try { localStorage.setItem('vn-list-sort', listSort); } catch {}
      updateListSortButton();
      closeSortSheet();
      renderNotesList();
    }
  });
}

/* ══════════════════════════════════════════════════
   নোট নাম / অ্যাকশন
══════════════════════════════════════════════════ */
on(noteFormSave, 'click', () => {
  if (!noteNameTargetId) { closeNoteNameModal(); return; }
  if (noteNameInput) setNoteName(noteNameTargetId, noteNameInput.value);
  closeNoteNameModal();
  showToast('✓ নাম সেভ হয়েছে', 'success');
});
on(noteFormClose, 'click',  closeNoteNameModal);
on(noteFormCancel, 'click', closeNoteNameModal);
on(noteFormModal, 'click', e => { if (e.target === noteFormModal) closeNoteNameModal(); });
on(noteNameInput, 'keydown', e => { if (e.key === 'Enter') noteFormSave?.click(); });

on(noteActionRename, 'click', () => {
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  openNoteNameModal(activeNoteActionId);
});
on(noteActionMove, 'click', () => {
  const id = activeNoteActionId;
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  if (id) showMoveToFolderSheet(id);
});
on(noteActionDelete, 'click', () => {
  const id = activeNoteActionId;
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  if (id) deleteNote(id);
});
on(noteActionCancel, 'click',  () => { if (noteActionSheet) noteActionSheet.classList.add('hidden'); });
on(noteActionSheet, 'click',  e => { if (e.target === noteActionSheet) noteActionSheet.classList.add('hidden'); });

on(noteTitleDisplay, 'click', () => {
  saveCurrentNote(true);
  openNoteNameModal(null);
});

/* ══════════════════════════════════════════════════
   রেকর্ডিং
══════════════════════════════════════════════════ */
function getSupportedMimeType() {
  return ['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4']
    .find(t => MediaRecorder.isTypeSupported(t)) || '';
}

async function ensureMic() {
  if (micStream && micStream.active) return true;
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
    mimeType  = getSupportedMimeType();
    return true;
  } catch {
    showToast('মাইক্রোফোন অনুমতি দিন!', 'error');
    return false;
  }
}

function captureSelection() {
  const s = noteTextarea.selectionStart, e = noteTextarea.selectionEnd;
  if (s !== e) { savedSelection = { start: s, end: e }; replaceBanner.classList.remove('hidden'); }
}

function clearSelection() { savedSelection = null; replaceBanner.classList.add('hidden'); }

async function toggleRecording() {
  if (isProcessing) return;
  if (isRecording) {
    stopSegment();
  } else {
    if (segmentCount === 0) captureSelection();
    const ok = await ensureMic();
    if (ok) startSegment();
  }
}

function startSegment() {
  const opts = mimeType ? { mimeType } : {};
  mediaRecorder = new MediaRecorder(micStream, opts);
  mediaRecorder.ondataavailable = e => { if (e.data && e.data.size > 0) audioChunks.push(e.data); };
  mediaRecorder.start(100);
  isRecording = true;
  recBtn.classList.add('recording');
  segmentsText.textContent = 'রেকর্ড হচ্ছে…';
  recTimer.classList.remove('hidden');
  startTimer();
}

function stopSegment() {
  if (!mediaRecorder || mediaRecorder.state === 'inactive') return;
  mediaRecorder.onstop = () => {
    isRecording  = false;
    segmentCount += 1;
    stopTimer();
    recBtn.classList.remove('recording');
    updateSegmentsUI();
  };
  mediaRecorder.stop();
}

function updateSegmentsUI() {
  if (segmentCount === 0) {
    segmentsDots.textContent = '';
    segmentsText.textContent = 'কোনো রেকর্ডিং নেই';
    clearRecBtn.classList.add('hidden');
    doneBtn.disabled = true;
  } else {
    segmentsDots.textContent = '●'.repeat(Math.min(segmentCount, 10));
    segmentsText.textContent = `${segmentCount}টি খণ্ড  —  আরও যোগ করুন বা DONE করুন`;
    clearRecBtn.classList.remove('hidden');
    doneBtn.disabled = false;
  }
}

function resetRecording() {
  if (isRecording && mediaRecorder) { mediaRecorder.onstop = null; try { mediaRecorder.stop(); } catch {} }
  isRecording = false; audioChunks = []; segmentCount = 0;
  stopTimer();
  recBtn.classList.remove('recording');
  recTimer.classList.add('hidden');
  updateSegmentsUI();
  clearSelection();
}

function startTimer() {
  timerSeconds = 0; updateTimerDisplay();
  timerInterval = setInterval(() => { timerSeconds++; updateTimerDisplay(); }, 1000);
}

function stopTimer() { clearInterval(timerInterval); timerInterval = null; recTimer.classList.add('hidden'); }
function updateTimerDisplay() {
  recTimer.textContent = `${Math.floor(timerSeconds/60)}:${String(timerSeconds%60).padStart(2,'0')}`;
}

/* ══════════════════════════════════════════════════
   DONE — ট্রান্সক্রিপশন
══════════════════════════════════════════════════ */
async function onDone() {
  if (isProcessing) return;
  if (audioChunks.length === 0 && !isRecording) { showToast('কোনো রেকর্ডিং নেই', 'warning'); return; }

  if (isRecording) {
    await new Promise(resolve => {
      mediaRecorder.onstop = () => { isRecording = false; segmentCount++; stopTimer(); recBtn.classList.remove('recording'); resolve(); };
      mediaRecorder.stop();
    });
  }

  await transcribeAndInsert();
}

async function transcribeAndInsert() {
  isProcessing = true; recBtn.disabled = true; doneBtn.disabled = true;
  showProcessing(true, 'AI শুনছে…');

  try {
    const effectiveMime = mimeType || 'audio/webm';
    const blob   = new Blob(audioChunks, { type: effectiveMime });
    const base64 = await blobToBase64(blob);

    showProcessing(true, `বিশ্লেষণ হচ্ছে… (${(blob.size/1024).toFixed(0)} KB)`);

    const res = await fetch(getGeminiUrl(), {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contents: [{ parts: [
        { inline_data: { mime_type: effectiveMime, data: base64 } },
        { text: TRANSCRIBE_PROMPT },
      ]}]}),
    });

    if (!res.ok) {
      const e = await res.json().catch(() => ({}));
      throw new Error(e?.error?.message || `HTTP ${res.status}`);
    }

    const data = await res.json();
    const text = data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

    if (!text) { showToast('কিছু শোনা যায়নি — আবার চেষ্টা করুন', 'warning'); return; }

    const existing = noteTextarea.value;

    if (savedSelection) {
      noteTextarea.value = existing.slice(0, savedSelection.start) + text + existing.slice(savedSelection.end);
      const pos = savedSelection.start + text.length;
      noteTextarea.setSelectionRange(pos, pos);
      clearSelection();
      showToast('✓ সিলেক্ট করা অংশ রিপ্লেস হয়েছে', 'success');
    } else {
      noteTextarea.value = existing ? existing.trimEnd() + '\n' + text : text;
      noteTextarea.scrollTop = noteTextarea.scrollHeight;
      showToast('✓ ট্রান্সক্রিপশন সম্পন্ন', 'success');
    }

    if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
    saveCurrentNote(true);
    audioChunks = []; segmentCount = 0; updateSegmentsUI();

  } catch (err) {
    console.error('[Gemini]', err);
    showToast(`ত্রুটি: ${(err.message||'').slice(0,60)}`, 'error');
  } finally {
    isProcessing = false; recBtn.disabled = false; showProcessing(false);
  }
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload  = () => resolve(r.result.split(',')[1]);
    r.onerror = reject;
    r.readAsDataURL(blob);
  });
}

/* ══════════════════════════════════════════════════
   UI সহায়ক
══════════════════════════════════════════════════ */
function showProcessing(show, text = '') {
  if (processingOverlay) processingOverlay.classList.toggle('hidden', !show);
  if (text && processingText) processingText.textContent = text;
}

let _toastTimer;
function showToast(msg, type = 'info') {
  if (!toastEl) return;
  toastEl.textContent = msg;
  toastEl.className   = `toast toast-${type}`;
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => { toastEl.className = 'toast hidden'; }, 3200);
}

/* ══════════════════════════════════════════════════
   সেটিংস পেজ
══════════════════════════════════════════════════ */
function openSettingsPage() {
  if (apiKeyInput) apiKeyInput.value = localStorage.getItem('vn-api-key') || DEFAULT_API_KEY;
  updateKeyStatus();
  updateListSortButton();
  if (screenSettings) {
    screenSettings.classList.add('active');
    history.pushState({ screen: 'settings' }, '');
  }
}

function closeSettingsPage() {
  if (screenSettings) screenSettings.classList.remove('active');
}

function updateKeyStatus() {
  if (!keyStatus) return;
  const saved = localStorage.getItem('vn-api-key');
  if (saved && saved !== DEFAULT_API_KEY) {
    keyStatus.textContent = '✓ কাস্টম API Key ব্যবহার হচ্ছে';
    keyStatus.className   = 'key-status using-custom';
  } else {
    keyStatus.textContent = 'ডিফল্ট API Key ব্যবহার হচ্ছে';
    keyStatus.className   = 'key-status using-default';
  }
}

on(settingsBtn, 'click', openSettingsPage);
on(settingsBackBtn, 'click', () => { if (screenSettings?.classList.contains('active')) history.back(); });

on(toggleEyeBtn, 'click', () => {
  if (!apiKeyInput) return;
  apiKeyInput.type = apiKeyInput.type === 'password' ? 'text' : 'password';
  if (toggleEyeBtn) toggleEyeBtn.style.color = apiKeyInput.type === 'text' ? 'var(--accent)' : '';
});

on(saveKeyBtn, 'click', () => {
  if (!apiKeyInput) return;
  const key = apiKeyInput.value.trim();
  if (!key) { showToast('API Key খালি রাখা যাবে না', 'error'); return; }
  localStorage.setItem('vn-api-key', key);
  updateKeyStatus();
  showToast('✓ API Key স্থায়ীভাবে সেভ হয়েছে', 'success');
});

on(resetKeyBtn, 'click', () => {
  if (!apiKeyInput) return;
  localStorage.removeItem('vn-api-key');
  apiKeyInput.value = DEFAULT_API_KEY;
  updateKeyStatus();
  showToast('ডিফল্ট Key-তে ফেরত গেছে', 'info');
});

/* ══════════════════════════════════════════════════
   ইভেন্ট বাইন্ডিং
══════════════════════════════════════════════════ */
on(newNoteBtn, 'click', () => openNewNote(null));
on(newFolderBtn, 'click', () => openFolderFormModal('create'));
on(folderBackBtn, 'click', closeFolder);
on(renameFolderBtn, 'click', () => { if (currentFolderId) openFolderFormModal('rename', currentFolderId); });
on(newNoteInFolderBtn, 'click', () => openNewNote(currentFolderId));

on(backBtn, 'click', () => {
  saveCurrentNote(true);
  resetRecording();
  goToList();
});

on(deleteNoteBtn, 'click', deleteCurrentNote);

on(editorFolderChip, 'click', () => {
  if (currentNoteId || (noteTextarea && noteTextarea.value.trim())) saveCurrentNote(true);
  showMoveToFolderSheet();
});

on(recBtn, 'click', toggleRecording);
on(doneBtn, 'click', onDone);

on(clearRecBtn, 'click', () => {
  if (isRecording) { mediaRecorder.onstop=null; try{mediaRecorder.stop();}catch{} isRecording=false; if (recBtn) recBtn.classList.remove('recording'); stopTimer(); }
  audioChunks=[]; segmentCount=0; updateSegmentsUI(); showToast('রেকর্ডিং মুছে গেছে');
});

on(cancelReplaceBtn, 'click', () => { clearSelection(); audioChunks=[]; segmentCount=0; updateSegmentsUI(); });

on(noteTextarea, 'input', () => {
  if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
  clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => saveCurrentNote(true), 1500);
});

document.addEventListener('visibilitychange', () => { if (document.hidden && currentNoteId) saveCurrentNote(true); });

/* অ্যান্ড্রয়েড ব্যাক বাটন */
window.addEventListener('popstate', () => {
  if (screenSettings && screenSettings.classList.contains('active')) {
    closeSettingsPage();
    return;
  }
  if (screenEditor && screenEditor.classList.contains('active')) {
    saveCurrentNote(true); resetRecording(); screenEditor.classList.remove('active');
    history.pushState(null, '');
  } else if (currentFolderId) {
    closeFolder(); history.pushState(null, '');
  }
});

/* ══════════════════════════════════════════════════
   শুরু
══════════════════════════════════════════════════ */
// প্রথমে localStorage থেকে instant load → UI দেখাও
loadLocalCache();
try {
  const s = localStorage.getItem('vn-list-sort');
  if (s) listSort = s;
} catch { /* */ }
updateListHeader();
updateListSortButton();
renderNotesList();
history.replaceState({ screen: 'list' }, '');

// তারপর Supabase থেকে sync (background)
initSupabase();

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(console.warn));
}
