'use strict';

/* ══════════════════════════════════════════════════
   কনফিগ
══════════════════════════════════════════════════ */
const TRANSCRIBE_ENDPOINT = '/api/transcribe';
const SONIOX_KEY_ENDPOINT = '/api/soniox-key';
const SONIOX_CLIENT_CDN = 'https://esm.sh/@soniox/client@2';
const LOCAL_DEV_API_HINT = ' — লোকাল API নেই। টার্মিনালে: npx vercel dev';

function localDevApiHint(status) {
  if (status !== 404) return '';
  const h = location.hostname;
  if (h === 'localhost' || h === '127.0.0.1' || h === '[::1]') return LOCAL_DEV_API_HINT;
  return '';
}

function micContextHint() {
  if (window.isSecureContext) return '';
  return ' — মাইক HTTPS বা localhost-এ কাজ করে (LAN IP-তে npx serve চালাবেন না)';
}
try { localStorage.removeItem('vn-api-key'); } catch { /* পুরনো leaked key cache থাকলে মুছে দাও */ }

/* ══════════════════════════════════════════════════
   Supabase কনফিগ
══════════════════════════════════════════════════ */
const SB_URL = 'https://iqjajofqaimnqvrxgdzc.supabase.co';
const SB_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxamFqb2ZxYWltbnF2cnhnZHpjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMDM0NzYsImV4cCI6MjA4Njc3OTQ3Nn0.epEV-GGXUsJe-u4Aumx284qGPrCFZcuXFGN9qmtwwbM';

let sb = null;
/** auth.users.id — লোকাল ক্যাশ কী */
let appUserId = null;
let appUsername = '';
let appIsAdmin = false;
let appMustChangePin = false;

const NOTE_LABEL_KEYS = ['', 'slate', 'blue', 'green', 'amber', 'rose', 'violet'];
const NOTE_LABEL_BAR = {
  '': null,
  slate: '#64748b',
  blue: '#3b82f6',
  green: '#22c55e',
  amber: '#f59e0b',
  rose: '#f43f5e',
  violet: '#8b5cf6',
};

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
const TRANSCRIPTION_MODELS = [
  'gemini-2.5-flash',
  'gemini-2.5-flash-lite',
  'gemini-2.5-pro',
  'gemini-3.1-flash-lite',
  'gemini-3-flash-preview',
  'gemini-3.1-pro-preview',
];

const VOICE_INPUT_MODES = ['live', 'record', 'fast'];
const VOICE_MODE_LABELS = { live: 'লাইভ', record: 'রেকর্ড', fast: 'দ্রুত' };

let appPrefs = {
  theme: 'system',
  fontSize: 'medium',
  listDensity: 'comfortable',
  autoSaveDelay: 2000,
  transcribeAutoSave: true,
  transcriptionModel: 'gemini-2.5-flash',
  editorPaper: 'default',
  accent: 'blue',
  lineHeight: 'normal',
  editorWidth: 'full',
};

let confirmResolver = null;
let pinResetResolver = null;

let micStream      = null;
let mediaRecorder  = null;
let audioChunks    = [];
let segmentCount   = 0;
let isRecording    = false;
let isProcessing   = false;
let instantRecording = false;
let recordingStartedAt = 0;
let recordingMode = '';
let mimeType       = '';
let timerInterval  = null;
let timerSeconds   = 0;
let autoSaveTimer  = null;
let periodicSaveInterval = null;
const PERIODIC_SAVE_MS = 60000;
let liveStopping = false;
let savedSelection = null;

let sonioxClient     = null;
let sonioxClientLoad = null;
let liveRecording    = null;
let liveFinalText    = '';
let livePartialText  = '';
/** @type {{ prefix: string, suffix: string, start: number, end: number } | null} */
let liveInsertAnchor = null;
let liveConnectTimeout = null;
let voiceInputMode = 'record';
let voiceModeMenuOpen = false;
let createFabMenuOpen = false;
let cardActionOpen = false;
let cardActionAnchor = null;

/* ══════════════════════════════════════════════════
   DOM রেফারেন্স
══════════════════════════════════════════════════ */
const $ = id => document.getElementById(id);
/** null এলিমেন্টে addEventListener — ক্র্যাশ এড়ানো */
function on(el, type, fn, opts) {
  if (el && typeof fn === 'function') el.addEventListener(type, fn, opts);
}

const screenList          = $('screen-list');
const screenEditor        = $('screen-editor');
const screenSettings      = $('screen-settings');
const rootHeader          = $('root-header');
const folderHeader        = $('folder-header');
const notesList           = $('notes-list');
const listSearchWrap      = $('list-search-wrap');
const listSearchInput     = $('list-search-input');
const listSearchClear     = $('list-search-clear');
const tagsModal           = $('tags-modal');
const tagsInput           = $('tags-input');
const tagsModalClose      = $('tags-modal-close');
const tagsModalCancel     = $('tags-modal-cancel');
const tagsModalSave       = $('tags-modal-save');
const labelModal          = $('label-modal');
const labelModalClose     = $('label-modal-close');
const labelModalCancel    = $('label-modal-cancel');
const labelSwatchRow      = $('label-swatch-row');
const emptyState          = $('empty-state');
const emptyIcon           = $('empty-icon');
const emptyTitle          = $('empty-title');
const emptyHint           = $('empty-hint');
const settingsBtn         = $('settings-btn');
const rootSignOutBtn      = $('root-sign-out-btn');
const folderBackBtn       = $('folder-back-btn');
const folderNameDisplay   = $('folder-name-display');
const renameFolderBtn     = $('rename-folder-btn');
const folderSignOutBtn    = $('folder-sign-out-btn');
const backBtn             = $('back-btn');
const deleteNoteBtn       = $('delete-note-btn');
const noteTitleDisplay    = $('note-title-display');
const saveStatus          = $('save-status');
const noteWordCountEl     = $('note-word-count');
const editorFolderChip    = $('editor-folder-chip');
const editorFolderName    = $('editor-folder-name');
const noteTextarea        = $('note-textarea');
const segmentsDots        = $('segments-dots');
const segmentsText        = $('segments-text');
const clearRecBtn         = $('clear-rec-btn');
const bottomBar           = $('bottom-bar');
const voiceCombo          = $('voice-combo');
const voiceMainBtn        = $('voice-main-btn');
const voiceModeBtn        = $('voice-mode-btn');
const voiceModeMenu       = $('voice-mode-menu');
const voiceModeLabel      = $('voice-mode-label');
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

// ফোল্ডার অপশন পপওভার
const cardActionPopover     = $('card-action-popover');
const cardActionTitle       = $('card-action-title');
const cardActionNotePanel   = $('card-action-note-panel');
const cardActionFolderPanel = $('card-action-folder-panel');
const folderActionRename    = $('folder-action-rename');
const folderActionLabel     = $('folder-action-label');
const folderActionDelete    = $('folder-action-delete');

// Move-to-folder শিট
const moveSheet           = $('move-sheet');
const moveFolderList      = $('move-folder-list');
const moveSheetClose      = $('move-sheet-close');
const moveSheetTitleEl    = $('move-sheet-title');
const moveCurrentHint     = $('move-sheet-current');
const sortSheet           = $('sort-sheet');
const sortSheetClose      = $('sort-sheet-close');
const listSortBtn         = $('list-sort-btn');
const folderSortBtn       = $('folder-sort-btn');
const listSortLabel       = $('list-sort-label');
const createFabWrap       = $('create-fab-wrap');
const createFabBtn        = $('create-fab-btn');
const createFabMenu       = $('create-fab-menu');
const settingsBackBtn     = $('settings-back-btn');
const settingsSortSummary = $('settings-sort-summary');
const themeValue          = $('theme-value');
const fontSizeValue       = $('font-size-value');
const editorPaperValue    = $('editor-paper-value');
const accentValue         = $('accent-value');
const listDensityValue    = $('list-density-value');
const autoSaveDelayValue  = $('autosave-delay-value');
const noteActionRename    = $('note-action-rename');
const noteActionMove      = $('note-action-move');
const noteActionDelete    = $('note-action-delete');
const noteActionPin       = $('note-action-pin');
const noteActionPinLabel  = $('note-action-pin-label');
const noteActionTags      = $('note-action-tags');
const noteActionLabel     = $('note-action-label');
const noteFormModal       = $('note-form-modal');
const noteFormTitle       = $('note-form-title');
const noteNameInput       = $('note-name-input');
const noteFormSave        = $('note-form-save');
const noteFormClose       = $('note-form-close');
const noteFormCancel      = $('note-form-cancel');

const transcribeAutoSaveToggle = $('pref-transcribe-autosave');
const lineHeightValue         = $('line-height-value');
const editorWidthValue        = $('editor-width-value');
const backupExportBtn         = $('backup-export-btn');
const backupImportBtn         = $('backup-import-btn');
const backupImportInput       = $('backup-import-input');
const screenAuth                = $('screen-auth');
const mainShell                 = $('main-shell');
const authUsername              = $('auth-username');
const authPassword              = $('auth-password');
const authPinToggle               = $('auth-pin-toggle');
const authSigninBtn             = $('auth-signin-btn');
const authSheetMessage          = $('auth-sheet-message');
const accountCurrentPin         = $('account-current-pin');
const accountNewPin             = $('account-new-pin');
const accountConfirmPin         = $('account-confirm-pin');
const accountChangePinBtn       = $('account-change-pin-btn');
const accountPinMessage         = $('account-pin-message');
const accountPinRequiredAlert   = $('account-pin-required-alert');
const adminUserCreateBlock      = $('admin-user-create-block');
const adminNewUsername          = $('admin-new-username');
const adminNewPin               = $('admin-new-pin');
const adminNewPinToggle         = $('admin-new-pin-toggle');
const adminCreateUserBtn        = $('admin-create-user-btn');
const adminCreateMessage        = $('admin-create-message');
const adminRefreshUsersBtn      = $('admin-refresh-users-btn');
const adminUsersList            = $('admin-users-list');
const cloudSyncSummary          = $('cloud-sync-summary');
const authSignOutBtn            = $('auth-sign-out-btn');
const appConfirmDialog          = $('app-confirm-dialog');
const appConfirmIcon            = $('app-confirm-icon');
const appConfirmTitle           = $('app-confirm-title');
const appConfirmMessage         = $('app-confirm-message');
const appConfirmCancel          = $('app-confirm-cancel');
const appConfirmOk              = $('app-confirm-ok');
const pinResetDialog            = $('pin-reset-dialog');
const pinResetTitle             = $('pin-reset-title');
const pinResetMessage           = $('pin-reset-message');
const pinResetInput             = $('pin-reset-input');
const pinResetError             = $('pin-reset-error');
const pinResetCancel            = $('pin-reset-cancel');
const pinResetOk                = $('pin-reset-ok');

/* ══════════════════════════════════════════════════
   ডেটা লোড / সেভ (Hybrid: localStorage + Supabase)
══════════════════════════════════════════════════ */

/* ── লোকাল ক্যাশ (instant) ─────────────────────── */
function normalizeNote(raw) {
  const n = raw && typeof raw === 'object' ? raw : {};
  let tags = [];
  if (Array.isArray(n.tags)) {
    tags = n.tags.map(t => String(t).trim()).filter(Boolean);
  } else if (typeof n.tags === 'string' && n.tags.trim()) {
    tags = n.tags.split(/[,\n]/).map(s => s.trim()).filter(Boolean);
  }
  tags = [...new Set(tags)].slice(0, 5);
  const lc = NOTE_LABEL_KEYS.includes(n.labelColor) ? n.labelColor : '';
  return {
    ...n,
    content: typeof n.content === 'string' ? n.content : '',
    title: typeof n.title === 'string' ? n.title : '',
    titleCustom: n.titleCustom === true,
    folderId: n.folderId == null || n.folderId === '' ? null : n.folderId,
    pinned: n.pinned === true,
    tags,
    labelColor: lc,
  };
}

function normalizeFolder(raw) {
  const f = raw && typeof raw === 'object' ? raw : {};
  const lc = NOTE_LABEL_KEYS.includes(f.labelColor) ? f.labelColor : '';
  return {
    ...f,
    name: typeof f.name === 'string' ? f.name : '',
    created: f.created || new Date().toISOString(),
    labelColor: lc,
  };
}

function cacheNotesKey() {
  return appUserId ? `vn-notes-${appUserId}` : null;
}

function cacheFoldersKey() {
  return appUserId ? `vn-folders-${appUserId}` : null;
}

function loadLocalCache() {
  if (!appUserId) {
    notes = {};
    folders = {};
    return;
  }
  const nk = cacheNotesKey();
  const fk = cacheFoldersKey();
  try { notes = JSON.parse(localStorage.getItem(nk) || '{}'); } catch { notes = {}; }
  try { folders = JSON.parse(localStorage.getItem(fk) || '{}'); } catch { folders = {}; }

  for (const id of Object.keys(notes)) {
    notes[id] = normalizeNote(notes[id]);
  }
  for (const id of Object.keys(folders)) {
    folders[id] = normalizeFolder(folders[id]);
  }
}

function saveLocalCache() {
  if (!appUserId) return;
  const nk = cacheNotesKey();
  const fk = cacheFoldersKey();
  if (!nk || !fk) return;
  try {
    localStorage.setItem(nk, JSON.stringify(notes));
    localStorage.setItem(fk, JSON.stringify(folders));
  } catch { showToast('লোকাল স্টোরেজ পূর্ণ!', 'error'); }
}

const BACKUP_FORMAT_VERSION = 1;

function exportVoiceNotesBackup() {
  const payload = {
    vnBackupVersion: BACKUP_FORMAT_VERSION,
    exportedAt: new Date().toISOString(),
    notes: { ...notes },
    folders: { ...folders },
    listSort,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' });
  const a = document.createElement('a');
  const d = new Date();
  const pad = n => String(n).padStart(2, '0');
  a.download = `voice-notes-backup-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}.json`;
  a.href = URL.createObjectURL(blob);
  a.click();
  URL.revokeObjectURL(a.href);
  showToast('ব্যাকআপ ডাউনলোড হয়েছে', 'success');
}

function validateBackupNotes(obj) {
  if (!obj || typeof obj !== 'object') return false;
  for (const [, n] of Object.entries(obj)) {
    if (!n || typeof n !== 'object' || typeof n.content !== 'string') return false;
  }
  return true;
}

function validateBackupFolders(obj) {
  if (!obj || typeof obj !== 'object') return false;
  for (const [, f] of Object.entries(obj)) {
    if (!f || typeof f !== 'object' || typeof f.name !== 'string') return false;
  }
  return true;
}

function resetLocalEditorAfterImport() {
  currentNoteId = null;
  pendingFolderId = null;
  if (noteTextarea) noteTextarea.value = '';
  if (noteTitleDisplay) noteTitleDisplay.textContent = 'নতুন নোট';
  updateNoteWordCount();
  if (saveStatus) {
    saveStatus.textContent = '';
    saveStatus.classList.remove('visible');
  }
  if (screenEditor) screenEditor.classList.remove('active');
  stopEditorPeriodicSave();
  updateEditorFolderChip();
  resetRecording();
}

function onBackupImportFile(ev) {
  const input = ev.target;
  const f = input.files && input.files[0];
  input.value = '';
  if (!f) return;
  const reader = new FileReader();
  reader.onload = async () => {
    try {
      const data = JSON.parse(reader.result);
      if (!validateBackupNotes(data.notes) || !validateBackupFolders(data.folders)) {
        showToast('ফাইলটা বৈধ ব্যাকআপ নয়', 'error');
        return;
      }
      const ok = await appConfirm({
        title: 'ব্যাকআপ পুনরুদ্ধার?',
        message: 'বর্তমান সব নোট ও ফোল্ডার মুছে এই ফাইল দিয়ে পূরণ হবে।',
        okText: 'পুনরুদ্ধার করুন',
        icon: '↑',
      });
      if (!ok) return;
      notes = JSON.parse(JSON.stringify(data.notes));
      folders = JSON.parse(JSON.stringify(data.folders));
      for (const nid of Object.keys(notes)) {
        notes[nid] = normalizeNote(notes[nid]);
      }
      for (const fid of Object.keys(folders)) {
        folders[fid] = normalizeFolder(folders[fid]);
      }
      if (typeof data.listSort === 'string' && data.listSort) {
        listSort = data.listSort;
        try { localStorage.setItem('vn-list-sort', listSort); } catch { /* */ }
      }
      saveLocalCache();
      currentFolderId = null;
      resetLocalEditorAfterImport();
      updateListHeader();
      updateListSortButton();
      renderNotesList();
      history.replaceState({
        screen: (screenSettings && screenSettings.classList.contains('active')) ? 'settings' : 'list',
      }, '');
      showToast('ইমপোর্ট সম্পন্ন', 'success');
    } catch {
      showToast('JSON পড়তে ব্যর্থ', 'error');
    }
  };
  reader.onerror = () => showToast('ফাইল পড়তে ব্যর্থ', 'error');
  reader.readAsText(f, 'UTF-8');
}

/* ── Supabase: কাস্টম PIN ইউজার + RPC (vn_app_users) ── */
function setAuthSheetMessage(text, isError) {
  if (!authSheetMessage) return;
  authSheetMessage.textContent = text || '';
  authSheetMessage.classList.toggle('auth-sheet-message-error', !!isError);
}

function setAdminCreateMessage(text, isError) {
  if (!adminCreateMessage) return;
  adminCreateMessage.textContent = text || '';
  adminCreateMessage.classList.toggle('auth-sheet-message-error', !!isError);
}

function setAccountPinMessage(text, isError) {
  if (!accountPinMessage) return;
  accountPinMessage.textContent = text || '';
  accountPinMessage.classList.toggle('auth-sheet-message-error', !!isError);
}

function userRpcMessage(code) {
  const map = {
    invalid_session: 'সেশন শেষ — আবার লগইন করুন',
    not_admin: 'শুধু অ্যাডমিন এই কাজ করতে পারবেন',
    pin_too_short: 'পিন কমপক্ষে ৪ অক্ষর দিন',
    invalid_current_pin: 'বর্তমান পিন ঠিক নয়',
    duplicate_username: 'এই ইউজারনেম আগে থেকেই আছে',
    user_not_found: 'ইউজার পাওয়া যায়নি',
    user_disabled: 'এই ইউজার বন্ধ করা আছে',
    cannot_disable_self: 'নিজের অ্যাকাউন্ট বন্ধ করা যাবে না',
  };
  return map[code] || code || 'কাজটি সম্পন্ন হয়নি';
}

function formatUserDate(iso) {
  if (!iso) return 'কখনো নয়';
  try {
    return new Intl.DateTimeFormat('bn-BD', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function persistAppSessionFromLogin(_data) {
  /* legacy — Supabase Auth ব্যবহার হয় */
}

async function loadVnProfile(userId) {
  const { data, error } = await sb.from('vn_profiles')
    .select('email, display_name, role, is_active')
    .eq('user_id', userId)
    .maybeSingle();
  if (error) throw new Error(error.message);
  if (!data || !data.is_active) {
    const err = new Error('no_vn_access');
    throw err;
  }
  appUserId = userId;
  appUsername = data.display_name || data.email || '';
  appIsAdmin = data.role === 'admin';
  appMustChangePin = false;
  try {
    localStorage.setItem('vn-app-user-id', appUserId);
    localStorage.setItem('vn-app-username', appUsername);
    localStorage.setItem('vn-app-is-admin', appIsAdmin ? '1' : '0');
    localStorage.removeItem('vn-app-session-token');
    localStorage.removeItem('vn-app-must-change-pin');
  } catch { /* */ }
}

async function applySupabaseAuthSession(session) {
  if (!session?.user?.id) throw new Error('no_session');
  await loadVnProfile(session.user.id);
}

function clearAppSession() {
  appUserId = null;
  appUsername = '';
  appIsAdmin = false;
  appMustChangePin = false;
  try {
    localStorage.removeItem('vn-app-session-token');
    localStorage.removeItem('vn-app-user-id');
    localStorage.removeItem('vn-app-username');
    localStorage.removeItem('vn-app-is-admin');
    localStorage.removeItem('vn-app-must-change-pin');
  } catch { /* */ }
}

function showAuthGate() {
  if (mainShell) mainShell.classList.add('hidden');
  if (screenAuth) screenAuth.classList.remove('hidden');
}

function hideAuthGate() {
  if (screenAuth) screenAuth.classList.add('hidden');
  if (mainShell) mainShell.classList.remove('hidden');
}

function showHomeScreen({ replaceHistory = true } = {}) {
  if (screenSettings) screenSettings.classList.remove('active');
  if (screenEditor) screenEditor.classList.remove('active');
  stopEditorPeriodicSave();
  resetRecording();
  currentFolderId = null;
  updateListHeader();
  renderNotesList();
  if (replaceHistory) history.replaceState({ screen: 'list' }, '');
}

function updateCloudSyncSummary() {
  if (!cloudSyncSummary) return;
  cloudSyncSummary.textContent = appUserId ? (appUsername || 'সংযুক্ত') : '—';
  if (authSignOutBtn) authSignOutBtn.classList.toggle('hidden', !appUserId);
  if (adminUserCreateBlock) adminUserCreateBlock.classList.toggle('hidden', !appIsAdmin);
  if (accountPinRequiredAlert) accountPinRequiredAlert.classList.toggle('hidden', !appMustChangePin);
}

function renderAdminUsers(users) {
  if (!adminUsersList) return;
  const list = Array.isArray(users) ? users : [];
  if (!appIsAdmin) {
    adminUsersList.innerHTML = '';
    return;
  }
  if (!list.length) {
    adminUsersList.innerHTML = '<p class="settings-user-empty">কোনো ইউজার নেই</p>';
    return;
  }

  adminUsersList.innerHTML = '';
  for (const user of list) {
    const row = document.createElement('div');
    row.className = 'settings-user-card' + (user.is_active === false ? ' is-disabled' : '');
    row.innerHTML = `
      <div class="settings-user-card-main">
        <div class="settings-user-card-title">
          <span>${esc(user.username || 'নামহীন')}</span>
          ${user.is_admin ? '<span class="settings-user-badge">Admin</span>' : ''}
          ${user.is_active === false ? '<span class="settings-user-badge settings-user-badge-muted">বন্ধ</span>' : ''}
      ${user.must_change_pin ? '<span class="settings-user-badge settings-user-badge-warn">পিন বদলাবে</span>' : ''}
        </div>
        <p class="settings-user-card-sub">শেষ লগইন: ${esc(formatUserDate(user.last_login_at))}</p>
      </div>
      <div class="settings-user-actions">
        <button type="button" class="settings-mini-btn" data-user-action="reset-pin">পিন রিসেট</button>
        <button type="button" class="settings-mini-btn ${user.is_active === false ? '' : 'settings-mini-btn-danger'}" data-user-action="toggle-active">
          ${user.is_active === false ? 'চালু' : 'বন্ধ'}
        </button>
      </div>`;

    row.querySelector('[data-user-action="reset-pin"]')?.addEventListener('click', () => resetUserPin(user));
    row.querySelector('[data-user-action="toggle-active"]')?.addEventListener('click', () => toggleUserActive(user));
    adminUsersList.appendChild(row);
  }
}

async function loadAdminUsers() {
  if (!adminUsersList) return;
  if (!appIsAdmin) {
    adminUsersList.innerHTML = '';
    return;
  }
  adminUsersList.innerHTML = '<p class="settings-user-empty">নতুন Voice Notes user Supabase Dashboard → Authentication ও <code>vn_profiles</code> টেবিল থেকে যোগ করুন।</p>';
}

async function resetUserPin(_user) {
  showToast('পিন রিসেট এখন Supabase Dashboard থেকে করুন', 'info');
}

async function toggleUserActive(_user) {
  showToast('user active/inactive এখন vn_profiles.is_active থেকে করুন', 'info');
}

async function enterAppAfterSession() {
  hideAuthGate();
  loadLocalCache();
  try {
    await pullFromSupabase();
  } catch (err) {
    console.warn('[Supabase] Pull failed:', err.message || err);
    showToast('ক্লাউড থেকে আনতে ব্যর্থ — লোকাল ক্যাশ দেখাচ্ছি', 'warning');
  }
  updateListHeader();
  updateListSortButton();
  renderNotesList();
  updateCloudSyncSummary();
  if (appIsAdmin) loadAdminUsers();
  showHomeScreen();
  if (appMustChangePin) showToast('নিরাপত্তার জন্য Settings থেকে পিন বদলে নিন', 'warning');
}

function guidePinChange() {
  setAccountPinMessage('প্রথমে নতুন পিন সেট করুন। বর্তমান পিন লিখে নতুন পিন দিন।', true);
  openSettingsPage();
  activateSettingsTab('user');
  setTimeout(() => accountCurrentPin?.focus(), 260);
}

async function exitAppSession() {
  notes = {};
  folders = {};
  currentNoteId = null;
  currentFolderId = null;
  pendingFolderId = null;
  resetLocalEditorAfterImport();
  if (sb) {
    try {
      await sb.auth.signOut();
    } catch { /* */ }
  }
  clearAppSession();
  showAuthGate();
  updateListHeader();
  updateListSortButton();
  renderNotesList();
  updateCloudSyncSummary();
}

async function initSupabase() {
  try {
    if (!window.supabase?.createClient) throw new Error('supabase-js লোড হয়নি');
    sb = window.supabase.createClient(SB_URL, SB_KEY, {
      auth: { persistSession: true, autoRefreshToken: true },
    });
    sb.auth.onAuthStateChange((event) => {
      if (event === 'SIGNED_OUT') {
        clearAppSession();
        showAuthGate();
      }
    });
    const { data: { session } } = await sb.auth.getSession();
    if (session) {
      try {
        await applySupabaseAuthSession(session);
        await enterAppAfterSession();
        return;
      } catch (err) {
        await sb.auth.signOut();
        clearAppSession();
        showAuthGate();
        setAuthSheetMessage(
          err.message === 'no_vn_access'
            ? 'এই অ্যাকাউন্টে Voice Notes access নেই'
            : (err.message || 'লগইন প্রয়োজন'),
          true,
        );
        return;
      }
    }
    showAuthGate();
  } catch (err) {
    console.warn('[Supabase] Init failed:', err.message || err);
    showToast('Supabase চালু হয়নি', 'error');
    showAuthGate();
    setAuthSheetMessage(err.message || 'কনফিগ চেক করুন', true);
  }
}

async function pullFromSupabase() {
  if (!sb || !appUserId) return;
  const [notesRes, foldersRes] = await Promise.all([
    sb.from('vn_notes').select('*').eq('owner_id', appUserId).order('updated_at', { ascending: false }),
    sb.from('vn_folders').select('*').eq('owner_id', appUserId).order('created_at', { ascending: true }),
  ]);
  if (notesRes.error) throw new Error(notesRes.error.message);
  if (foldersRes.error) throw new Error(foldersRes.error.message);
  const nd = Array.isArray(notesRes.data) ? notesRes.data : [];
  const fd = Array.isArray(foldersRes.data) ? foldersRes.data : [];

  notes = {};
  for (const n of nd) {
    let tags = [];
    if (Array.isArray(n.tags)) tags = n.tags.map(t => String(t).trim()).filter(Boolean);
    else if (n.tags && typeof n.tags === 'string') {
      try { tags = JSON.parse(n.tags); } catch { tags = []; }
      if (!Array.isArray(tags)) tags = [];
    }
    notes[n.id] = normalizeNote({
      title: n.title, content: n.content,
      folderId: n.folder_id || null,
      created: n.created_at, updated: n.updated_at,
      titleCustom: n.title_is_custom === true,
      pinned: n.pinned === true,
      tags,
      labelColor: n.label_color || n.labelColor || '',
    });
  }
  folders = {};
  for (const f of fd) {
    folders[f.id] = normalizeFolder({
      name: f.name,
      created: f.created_at,
      labelColor: f.label_color || f.labelColor || '',
    });
  }

  saveLocalCache();
  updateListHeader();
  renderNotesList();
  console.log('[Supabase] Pulled:', Object.keys(notes).length, 'notes,', Object.keys(folders).length, 'folders');
}

/* ── individual sync helpers ────────────────────── */
function persistNotes()   { saveLocalCache(); }
function persistFolders() { saveLocalCache(); }

function notePayloadForRpc(id) {
  const n = notes[id];
  return {
    id,
    title: n.title,
    content: n.content,
    folder_id: n.folderId || null,
    created_at: n.created,
    updated_at: n.updated,
    title_is_custom: n.titleCustom === true,
    pinned: n.pinned === true,
    tags: Array.isArray(n.tags) ? n.tags : [],
    label_color: n.labelColor || '',
  };
}

function notePayloadForDb(id) {
  return { ...notePayloadForRpc(id), owner_id: appUserId };
}

function sbUpsertNote(id) {
  if (!sb || !appUserId || !notes[id]) return;
  sb.from('vn_notes').upsert(notePayloadForDb(id), { onConflict: 'id' }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert note:', error.message);
  });
}

function sbDeleteNote(id) {
  if (!sb || !appUserId) return;
  sb.from('vn_notes').delete().eq('id', id).eq('owner_id', appUserId).then(({ error }) => {
    if (error) console.warn('[Supabase] delete note:', error.message);
  });
}

function sbUpsertFolder(id) {
  if (!sb || !appUserId || !folders[id]) return;
  const f = folders[id];
  sb.from('vn_folders').upsert({
    id,
    owner_id: appUserId,
    name: f.name,
    created_at: f.created,
    label_color: f.labelColor || '',
  }, { onConflict: 'id' }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert folder:', error.message);
  });
}

function sbDeleteFolder(id) {
  if (!sb || !appUserId) return;
  sb.from('vn_folders').delete().eq('id', id).eq('owner_id', appUserId).then(({ error }) => {
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

/** হোয়াইটস্পেস-বিচ্ছিন্ন শব্দ গণনা (বাংলা/মিশ্র লেখা) */
function countWordsInNoteText(text) {
  const t = String(text || '').trim();
  if (!t) return 0;
  return t.split(/\s+/).filter(Boolean).length;
}

function updateNoteWordCount() {
  if (!noteWordCountEl || !noteTextarea) return;
  const n = countWordsInNoteText(noteTextarea.value);
  try {
    noteWordCountEl.textContent = `${n.toLocaleString('bn-BD')}টি শব্দ`;
  } catch {
    noteWordCountEl.textContent = `${n}টি শব্দ`;
  }
}

function sortNoteEntries(entries) {
  const titleOf = n => getListTitle(n);
  return entries.slice().sort(([, a], [, b]) => {
    const pA = a.pinned ? 1 : 0;
    const pB = b.pinned ? 1 : 0;
    if (pB !== pA) return pB - pA;
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
  const sortTitle = 'বর্তমান সর্ট: ' + getSortLabel();
  if (listSortBtn) listSortBtn.title = sortTitle;
  if (folderSortBtn) folderSortBtn.title = sortTitle;
  if (listSortLabel) listSortLabel.textContent = getSortLabel();
  if (settingsSortSummary) {
    settingsSortSummary.textContent = getSortLabel();
  }
  syncSettingsControls();
}

function normFolderId(v) {
  if (v == null || v === '') return null;
  return v;
}

function loadPrefs() {
  const get = (key, fallback) => {
    try { return localStorage.getItem(key) || fallback; } catch { return fallback; }
  };
  const paper = get('vn-editor-paper', 'default');
  const acc = get('vn-accent', 'blue');
  const lh = get('vn-line-height', 'normal');
  const ew = get('vn-editor-width', 'full');
  appPrefs = {
    theme: get('vn-theme', 'system'),
    fontSize: get('vn-font-size', 'medium'),
    listDensity: get('vn-list-density', 'comfortable'),
    autoSaveDelay: Number(get('vn-autosave-delay', '2000')) || 2000,
    transcribeAutoSave: get('vn-transcribe-autosave', 'true') !== 'false',
    transcriptionModel: TRANSCRIPTION_MODELS.includes(get('vn-transcription-model', '')) ? get('vn-transcription-model', '') : 'gemini-2.5-flash',
    editorPaper: ['default', 'cream', 'cool', 'oled'].includes(paper) ? paper : 'default',
    accent: ['blue', 'teal', 'violet'].includes(acc) ? acc : 'blue',
    lineHeight: ['compact', 'normal', 'relaxed'].includes(lh) ? lh : 'normal',
    editorWidth: ['full', 'balanced', 'narrow'].includes(ew) ? ew : 'full',
  };
}

function savePref(key, value) {
  appPrefs[key] = value;
  const storageMap = {
    theme: 'vn-theme',
    fontSize: 'vn-font-size',
    listDensity: 'vn-list-density',
    autoSaveDelay: 'vn-autosave-delay',
    transcribeAutoSave: 'vn-transcribe-autosave',
    transcriptionModel: 'vn-transcription-model',
    editorPaper: 'vn-editor-paper',
    accent: 'vn-accent',
    lineHeight: 'vn-line-height',
    editorWidth: 'vn-editor-width',
  };
  try { localStorage.setItem(storageMap[key], String(value)); } catch { /* */ }
  applyPrefs();
  syncSettingsControls();
}

function applyPrefs() {
  const root = document.documentElement;
  root.dataset.theme = appPrefs.theme || 'system';
  root.dataset.fontSize = appPrefs.fontSize || 'medium';
  root.dataset.listDensity = appPrefs.listDensity || 'comfortable';
  if (!appPrefs.accent || appPrefs.accent === 'blue') delete root.dataset.accent;
  else root.dataset.accent = appPrefs.accent;
  if (!appPrefs.editorPaper || appPrefs.editorPaper === 'default') delete root.dataset.editorPaper;
  else root.dataset.editorPaper = appPrefs.editorPaper;
  if (!appPrefs.lineHeight || appPrefs.lineHeight === 'normal') delete root.dataset.lineHeight;
  else root.dataset.lineHeight = appPrefs.lineHeight;
  if (!appPrefs.editorWidth || appPrefs.editorWidth === 'full') delete root.dataset.editorWidth;
  else root.dataset.editorWidth = appPrefs.editorWidth;
}

function syncSettingsControls() {
  document.querySelectorAll('[data-setting]').forEach(group => {
    const setting = group.dataset.setting;
    const activeValue = setting === 'defaultSort' ? listSort : String(appPrefs[setting]);
    group.querySelectorAll('[data-value]').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.value === activeValue);
      btn.setAttribute('aria-pressed', btn.dataset.value === activeValue ? 'true' : 'false');
    });
  });
  if (transcribeAutoSaveToggle) {
    transcribeAutoSaveToggle.checked = appPrefs.transcribeAutoSave !== false;
  }
  const modelSelect = document.getElementById('pref-transcription-model');
  if (modelSelect) modelSelect.value = appPrefs.transcriptionModel || 'gemini-2.5-flash';
  const modelValueEl = document.getElementById('transcription-model-value');
  if (modelValueEl) {
    const labels = {
      'gemini-2.5-flash': '2.5 Flash',
      'gemini-2.5-flash-lite': '2.5 Flash Lite',
      'gemini-2.5-pro': '2.5 Pro',
      'gemini-3.1-flash-lite': '3.1 Flash Lite',
      'gemini-3-flash-preview': '3 Flash Preview',
      'gemini-3.1-pro-preview': '3.1 Pro Preview',
    };
    modelValueEl.textContent = labels[appPrefs.transcriptionModel] || '2.5 Flash';
  }
  if (themeValue) {
    themeValue.textContent = ({ system: 'সিস্টেম ডিফল্ট', light: 'লাইট', dark: 'ডার্ক' })[appPrefs.theme] || 'সিস্টেম ডিফল্ট';
  }
  if (fontSizeValue) {
    fontSizeValue.textContent = ({ small: 'ছোট', medium: 'মাঝারি', large: 'বড়' })[appPrefs.fontSize] || 'মাঝারি';
  }
  if (editorPaperValue) {
    editorPaperValue.textContent = ({
      default: 'ডিফল্ট',
      cream: 'ক্রিম',
      cool: 'ঠান্ডা ধূসর',
      oled: 'OLED কালো',
    })[appPrefs.editorPaper] || 'ডিফল্ট';
  }
  if (accentValue) {
    accentValue.textContent = ({ blue: 'নীল', teal: 'টিল', violet: 'বেগুনি' })[appPrefs.accent] || 'নীল';
  }
  if (lineHeightValue) {
    lineHeightValue.textContent = ({
      compact: 'কম ফাঁক',
      normal: 'মাঝারি',
      relaxed: 'বেশি ফাঁক',
    })[appPrefs.lineHeight] || 'মাঝারি';
  }
  if (editorWidthValue) {
    editorWidthValue.textContent = ({
      full: 'পুরো',
      balanced: 'মাঝখানে',
      narrow: 'সরু',
    })[appPrefs.editorWidth] || 'পুরো';
  }
  if (listDensityValue) {
    listDensityValue.textContent = ({ compact: 'কম প্রিভিউ', comfortable: 'স্বাভাবিক প্রিভিউ', detailed: 'বেশি প্রিভিউ' })[appPrefs.listDensity] || 'স্বাভাবিক প্রিভিউ';
  }
  if (autoSaveDelayValue) {
    autoSaveDelayValue.textContent = ((appPrefs.autoSaveDelay || 2000) / 1000) + ' সেকেন্ড';
  }
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
function startEditorPeriodicSave() {
  stopEditorPeriodicSave();
  periodicSaveInterval = setInterval(() => {
    if (!screenEditor?.classList.contains('active')) return;
    if (!noteTextarea?.value.trim()) return;
    saveCurrentNote(true);
  }, PERIODIC_SAVE_MS);
}

function stopEditorPeriodicSave() {
  if (periodicSaveInterval) {
    clearInterval(periodicSaveInterval);
    periodicSaveInterval = null;
  }
}

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
      pinned: false,
      tags: [],
      labelColor: '',
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

async function deleteCurrentNote() {
  if (currentNoteId) {
    const ok = await appConfirm({
      title: 'নোট মুছবেন?',
      message: 'এই নোটটি স্থায়ীভাবে মুছে যাবে।',
      okText: 'মুছে ফেলুন',
      icon: '×',
    });
    if (!ok) return;
    const deletedId = currentNoteId;
    delete notes[deletedId];
    persistNotes();
    sbDeleteNote(deletedId); // Supabase sync
  }
  currentNoteId = null;
  resetRecording();
  goToList();
}

async function deleteNote(id) {
  const ok = await appConfirm({
    title: 'নোট মুছবেন?',
    message: 'এই নোটটি স্থায়ীভাবে মুছে যাবে।',
    okText: 'মুছে ফেলুন',
    icon: '×',
  });
  if (!ok) return;
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

function closeCardActionPopover() {
  cardActionOpen = false;
  cardActionAnchor = null;
  cardActionPopover?.classList.add('hidden');
}

function positionCardActionPopover(anchorEl) {
  if (!cardActionPopover || !anchorEl) return;
  cardActionPopover.classList.remove('hidden');
  cardActionPopover.style.visibility = 'hidden';
  requestAnimationFrame(() => {
    const rect = anchorEl.getBoundingClientRect();
    const pop = cardActionPopover.getBoundingClientRect();
    const m = 10;
    let top = rect.bottom + 6;
    let left = rect.right - pop.width;
    if (top + pop.height > window.innerHeight - m) {
      top = rect.top - pop.height - 6;
    }
    left = Math.max(m, Math.min(left, window.innerWidth - pop.width - m));
    top = Math.max(m, Math.min(top, window.innerHeight - pop.height - m));
    cardActionPopover.style.top = `${top}px`;
    cardActionPopover.style.left = `${left}px`;
    cardActionPopover.style.visibility = '';
    cardActionOpen = true;
  });
}

function showNoteActionSheet(id, anchorEl) {
  if (!notes[id] || !notes[id].content?.trim()) return;
  if (cardActionOpen && cardActionAnchor === anchorEl) {
    closeCardActionPopover();
    return;
  }
  activeNoteActionId = id;
  if (cardActionTitle) cardActionTitle.textContent = getListTitle(notes[id]);
  if (noteActionPinLabel) {
    noteActionPinLabel.textContent = notes[id].pinned ? 'পিন খুলুন' : 'পিন করুন';
  }
  cardActionNotePanel?.classList.remove('hidden');
  cardActionFolderPanel?.classList.add('hidden');
  cardActionAnchor = anchorEl;
  positionCardActionPopover(anchorEl);
}

function toggleNotePinned(id) {
  if (!notes[id]) return;
  notes[id] = { ...notes[id], pinned: !notes[id].pinned, updated: new Date().toISOString() };
  notes[id] = normalizeNote(notes[id]);
  persistNotes();
  sbUpsertNote(id);
  renderNotesList();
  showToast(notes[id].pinned ? 'পিন করা হয়েছে' : 'পিন খোলা হয়েছে', 'success');
}

let tagsModalTargetId = null;

function openTagsModalForNote(id) {
  if (!notes[id] || !tagsModal || !tagsInput) return;
  tagsModalTargetId = id;
  const t = Array.isArray(notes[id].tags) ? notes[id].tags : [];
  tagsInput.value = t.join(', ');
  tagsModal.classList.remove('hidden');
  setTimeout(() => { tagsInput.focus(); tagsInput.select(); }, 200);
}

function closeTagsModal() {
  if (tagsModal) tagsModal.classList.add('hidden');
  tagsModalTargetId = null;
}

function saveTagsFromModal() {
  if (!tagsModalTargetId || !notes[tagsModalTargetId]) {
    closeTagsModal();
    return;
  }
  const raw = (tagsInput && tagsInput.value) || '';
  const parts = raw.split(/[,\n]/).map(s => s.trim()).filter(Boolean);
  const uniq = [...new Set(parts)].slice(0, 5);
  const id = tagsModalTargetId;
  notes[id] = normalizeNote({ ...notes[id], tags: uniq, updated: new Date().toISOString() });
  persistNotes();
  sbUpsertNote(id);
  closeTagsModal();
  renderNotesList();
  showToast('ট্যাগ সেভ হয়েছে', 'success');
}

/** @type {{ kind: 'note'|'folder', id: string } | null} */
let labelModalContext = null;

function openLabelModalForNote(id) {
  if (!notes[id] || !labelModal) return;
  labelModalContext = { kind: 'note', id };
  labelModal.classList.remove('hidden');
}

function openLabelModalForFolder(id) {
  if (!folders[id] || !labelModal) return;
  labelModalContext = { kind: 'folder', id };
  labelModal.classList.remove('hidden');
}

function closeLabelModal() {
  if (labelModal) labelModal.classList.add('hidden');
  labelModalContext = null;
}

function setNoteLabelColor(id, colorKey) {
  if (!notes[id]) return;
  const key = NOTE_LABEL_KEYS.includes(colorKey) ? colorKey : '';
  notes[id] = normalizeNote({ ...notes[id], labelColor: key, updated: new Date().toISOString() });
  persistNotes();
  sbUpsertNote(id);
  closeLabelModal();
  closeCardActionPopover();
  renderNotesList();
  showToast(key ? 'লেবেল সেভ হয়েছে' : 'লেবেল সরানো হয়েছে', 'success');
}

function setFolderLabelColor(id, colorKey) {
  if (!folders[id]) return;
  const key = NOTE_LABEL_KEYS.includes(colorKey) ? colorKey : '';
  folders[id] = normalizeFolder({ ...folders[id], labelColor: key });
  persistFolders();
  sbUpsertFolder(id);
  closeLabelModal();
  closeCardActionPopover();
  renderNotesList();
  showToast(key ? 'ফোল্ডারের রং সেভ হয়েছে' : 'রং সরানো হয়েছে', 'success');
}

function buildLabelSwatches() {
  if (!labelSwatchRow) return;
  const specs = [
    { key: '', label: 'নেই', style: 'background:linear-gradient(135deg,#ccc,#888)' },
    { key: 'slate', label: '', style: 'background:#64748b' },
    { key: 'blue', label: '', style: 'background:#3b82f6' },
    { key: 'green', label: '', style: 'background:#22c55e' },
    { key: 'amber', label: '', style: 'background:#f59e0b' },
    { key: 'rose', label: '', style: 'background:#f43f5e' },
    { key: 'violet', label: '', style: 'background:#8b5cf6' },
  ];
  labelSwatchRow.innerHTML = '';
  for (const s of specs) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'label-swatch';
    b.dataset.color = s.key;
    b.title = s.key ? s.key : 'কোনো রঙ নয়';
    b.setAttribute('aria-label', s.key ? `রঙ ${s.key}` : 'রঙ সরান');
    b.style.cssText = s.style + ';width:40px;height:40px;border-radius:50%;border:2px solid rgba(0,0,0,0.12);cursor:pointer;flex-shrink:0';
    if (!s.key) b.textContent = '—';
    b.addEventListener('click', () => {
      const ctx = labelModalContext;
      if (!ctx) return;
      if (ctx.kind === 'note') setNoteLabelColor(ctx.id, s.key);
      else setFolderLabelColor(ctx.id, s.key);
    });
    labelSwatchRow.appendChild(b);
  }
}

/* ══════════════════════════════════════════════════
   ফোল্ডার CRUD
══════════════════════════════════════════════════ */
function createFolder(name) {
  const id  = crypto.randomUUID();
  const now = new Date().toISOString();
  folders[id] = normalizeFolder({ name: name.trim(), created: now, labelColor: '' });
  persistFolders();
  sbUpsertFolder(id); // Supabase sync
  return id;
}

function renameFolder(id, newName) {
  if (!folders[id]) return;
  folders[id] = normalizeFolder({ ...folders[id], name: newName.trim() });
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
  stopEditorPeriodicSave();
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
  syncCreateFabMenu();
}

function closeCreateFabMenu() {
  createFabMenuOpen = false;
  createFabMenu?.classList.add('hidden');
  createFabBtn?.setAttribute('aria-expanded', 'false');
  createFabBtn?.classList.remove('open');
}

function toggleCreateFabMenu() {
  createFabMenuOpen = !createFabMenuOpen;
  if (createFabMenuOpen) syncCreateFabMenu();
  createFabMenu?.classList.toggle('hidden', !createFabMenuOpen);
  createFabBtn?.setAttribute('aria-expanded', createFabMenuOpen ? 'true' : 'false');
  createFabBtn?.classList.toggle('open', createFabMenuOpen);
}

function syncCreateFabMenu() {
  const folderOpt = createFabMenu?.querySelector('[data-create="folder"]');
  if (folderOpt) folderOpt.classList.toggle('hidden', !!currentFolderId);
}

function handleCreateFabAction(action) {
  closeCreateFabMenu();
  if (action === 'note') {
    openNewNote(currentFolderId || null);
    return;
  }
  if (action === 'folder') openFolderFormModal('create');
}

/* ══════════════════════════════════════════════════
   নোট লিস্ট রেন্ডার
══════════════════════════════════════════════════ */
function listSearchRaw() {
  return listSearchInput && listSearchInput.value ? listSearchInput.value : '';
}

function listSearchTrimLower() {
  return listSearchRaw().trim().toLowerCase();
}

function noteMatchesSearch(note, q) {
  if (!q) return true;
  try {
    const t = getListTitle(note).toLowerCase();
    const c = (note.content || '').toLowerCase();
    return t.includes(q) || c.includes(q);
  } catch {
    return false;
  }
}

function updateSearchClearVisibility() {
  if (!listSearchClear) return;
  listSearchClear.classList.toggle('hidden', !listSearchRaw().trim());
}

let _searchDebounce;
function scheduleListRerender() {
  clearTimeout(_searchDebounce);
  _searchDebounce = setTimeout(() => renderNotesList(), 140);
}

function renderNotesList() {
  closeCardActionPopover();
  notesList.innerHTML = '';
  updateSearchClearVisibility();

  const q = listSearchTrimLower();

  if (currentFolderId) {
    const entries = Object.entries(notes).filter(([, n]) =>
      n.content && n.content.trim() && n.folderId === currentFolderId && noteMatchesSearch(n, q),
    );
    const items = sortNoteEntries(entries);

    if (!items.length) {
      if (q) {
        showEmptyState('🔎', 'কিছু মিলল না', 'অন্য শব্দ দিয়ে খুঁজুন');
      } else {
        showEmptyState('📁', 'ফোল্ডারে কোনো নোট নেই', 'উপরে + বাটন চাপুন');
      }
      return;
    }
    hideEmptyState();
    if (q) addSectionLabel('খোঁজার ফল');
    for (const [id, note] of items) notesList.appendChild(buildNoteCard(id, note));
    return;
  }

  if (q) {
    const entries = Object.entries(notes).filter(([, n]) =>
      n.content && n.content.trim() && noteMatchesSearch(n, q),
    );
    const items = sortNoteEntries(entries);
    if (!items.length) {
      showEmptyState('🔎', 'কিছু মিলল না', 'অন্য শব্দ দিয়ে খুঁজুন');
      return;
    }
    hideEmptyState();
    addSectionLabel('খোঁজার ফল');
    for (const [id, note] of items) notesList.appendChild(buildNoteCard(id, note));
    return;
  }

  const folderList = Object.entries(folders).sort(([, a], [, b]) => a.name.localeCompare(b.name));
  const unfiledAll = sortNoteEntries(
    Object.entries(notes).filter(([, n]) => n.content.trim() && !n.folderId),
  );
  const pinnedU = unfiledAll.filter(([, n]) => n.pinned);
  const restU = unfiledAll.filter(([, n]) => !n.pinned);

  const hasFolders = folderList.length > 0;
  const hasNotes = unfiledAll.length > 0;

  if (!hasFolders && !hasNotes) {
    showEmptyState('🎙️', 'কোনো নোট নেই', 'উপরে + বাটন চাপুন\nনতুন নোট তৈরি করতে');
    return;
  }
  hideEmptyState();

  if (pinnedU.length) {
    addSectionLabel('পিন করা');
    for (const [id, note] of pinnedU) notesList.appendChild(buildNoteCard(id, note));
  }

  if (hasFolders) {
    addSectionLabel('ফোল্ডার');
    for (const [id, folder] of folderList) {
      const count = Object.values(notes).filter(n => n.folderId === id && n.content.trim()).length;
      notesList.appendChild(buildFolderCard(id, folder, count));
    }
  }

  if (restU.length) {
    if (pinnedU.length) {
      addSectionLabel('অন্যান্য নোট');
    } else if (hasFolders) {
      addSectionLabel('নোট');
    }
    for (const [id, note] of restU) notesList.appendChild(buildNoteCard(id, note));
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
  const iconFill = NOTE_LABEL_BAR[folder.labelColor] || '#0b8276';

  const card = document.createElement('div');
  card.className = 'folder-card';
  card.innerHTML = `
    <div class="folder-card-body">
      <div class="folder-card-icon">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="${iconFill}">
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
    showFolderActionSheet(id, e.currentTarget);
  });
  return card;
}

/* ── নোট কার্ড ──────────────────────────────────── */
function buildNoteCard(id, note) {
  const preview = note.content.replace(/\n/g,' ').trim().slice(0, 90);
  const folderTag = note.folderId && folders[note.folderId]
    ? `<span class="note-folder-tag">📁 ${esc(folders[note.folderId].name)}</span>` : '';

  const barColor = NOTE_LABEL_BAR[note.labelColor];
  const accentStyle = barColor ? ` style="background:${barColor}"` : '';

  const pinMark = note.pinned ? '<span class="note-card-pin" aria-hidden="true">📌</span>' : '';
  const tags = Array.isArray(note.tags) ? note.tags : [];
  const tagsRow = tags.length
    ? `<div class="note-card-tags-row">${tags.map(t => `<span class="note-card-tag">${esc(t)}</span>`).join('')}</div>`
    : '';

  const card = document.createElement('div');
  card.className = 'note-card' + (note.pinned ? ' note-card--pinned' : '');
  const listTitle = getListTitle(note);
  card.innerHTML = `
    <div class="note-card-accent"${accentStyle}></div>
    <div class="note-card-body">
      <div class="note-card-title">${pinMark}${esc(listTitle)}</div>
      <div class="note-card-date">${fmtDate(note.updated)}${folderTag}</div>
      ${tagsRow}
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
    showNoteActionSheet(id, e.currentTarget);
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
  updateNoteWordCount();

  updateEditorFolderChip();
  resetRecording();

  requestAnimationFrame(() => {
    screenEditor.classList.add('active');
    startEditorPeriodicSave();
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

  updateNoteWordCount();
  updateEditorFolderChip();
  resetRecording();

  requestAnimationFrame(() => {
    screenEditor.classList.add('active');
    startEditorPeriodicSave();
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
   ফোল্ডার অপশন পপওভার
══════════════════════════════════════════════════ */
function showFolderActionSheet(id, anchorEl) {
  if (!folders[id]) return;
  if (cardActionOpen && cardActionAnchor === anchorEl) {
    closeCardActionPopover();
    return;
  }
  activeFolderActionId = id;
  if (cardActionTitle) cardActionTitle.textContent = folders[id]?.name || 'ফোল্ডার';
  cardActionFolderPanel?.classList.remove('hidden');
  cardActionNotePanel?.classList.add('hidden');
  cardActionAnchor = anchorEl;
  positionCardActionPopover(anchorEl);
}

on(folderActionRename, 'click', () => {
  closeCardActionPopover();
  openFolderFormModal('rename', activeFolderActionId);
});

on(folderActionLabel, 'click', () => {
  const id = activeFolderActionId;
  closeCardActionPopover();
  if (id) openLabelModalForFolder(id);
});

on(folderActionDelete, 'click', async () => {
  closeCardActionPopover();
  const name = folders[activeFolderActionId]?.name || 'ফোল্ডার';
  const count = Object.values(notes).filter(n => n.folderId === activeFolderActionId).length;
  const msg = count > 0
    ? `"${name}" ফোল্ডারটি মুছলে এর ${count}টি নোট মূল তালিকায় চলে যাবে।`
    : `"${name}" ফোল্ডারটি মুছে যাবে।`;
  const ok = await appConfirm({
    title: 'ফোল্ডার মুছবেন?',
    message: msg,
    okText: 'মুছে ফেলুন',
    icon: '×',
  });
  if (!ok) return;
  deleteFolder(activeFolderActionId);
  renderNotesList();
  showToast('ফোল্ডার মুছে গেছে');
});

on(noteActionRename, 'click', () => {
  closeCardActionPopover();
  openNoteNameModal(activeNoteActionId);
});
on(noteActionMove, 'click', () => {
  const id = activeNoteActionId;
  closeCardActionPopover();
  if (id) showMoveToFolderSheet(id);
});
on(noteActionDelete, 'click', () => {
  const id = activeNoteActionId;
  closeCardActionPopover();
  if (id) deleteNote(id);
});
on(noteActionPin, 'click', () => {
  const id = activeNoteActionId;
  closeCardActionPopover();
  if (id) toggleNotePinned(id);
});
on(noteActionTags, 'click', () => {
  const id = activeNoteActionId;
  closeCardActionPopover();
  if (id) openTagsModalForNote(id);
});
on(noteActionLabel, 'click', () => {
  const id = activeNoteActionId;
  closeCardActionPopover();
  if (id) openLabelModalForNote(id);
});

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
      ${isActive ? '<svg class="move-check" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#0b8276" stroke-width="3" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>' : ''}`;

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
      `<svg width="20" height="20" viewBox="0 0 24 24" fill="${sel?'#0b8276':'none'}" stroke="${sel?'#0b8276':'currentColor'}" stroke-width="2" stroke-linecap="round">
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
  if (folderSortBtn) folderSortBtn.setAttribute('aria-expanded', 'false');
}
function openSortSheet(ev) {
  if (!sortSheet) return;
  document.querySelectorAll('.sort-pick').forEach(b => {
    b.classList.toggle('is-active', b.dataset.sort === listSort);
  });
  if (listSortBtn) listSortBtn.setAttribute('aria-expanded', 'false');
  if (folderSortBtn) folderSortBtn.setAttribute('aria-expanded', 'false');
  const opener = ev && ev.currentTarget;
  if (opener && (opener === listSortBtn || opener === folderSortBtn)) {
    opener.setAttribute('aria-expanded', 'true');
  } else if (listSortBtn) {
    listSortBtn.setAttribute('aria-expanded', 'true');
  }
  sortSheet.classList.remove('hidden');
}
on(listSortBtn, 'click', openSortSheet);
on(folderSortBtn, 'click', openSortSheet);
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

on(listSearchInput, 'input', () => {
  updateSearchClearVisibility();
  scheduleListRerender();
});
on(listSearchClear, 'click', () => {
  if (listSearchInput) listSearchInput.value = '';
  updateSearchClearVisibility();
  renderNotesList();
});

on(tagsModalClose, 'click', closeTagsModal);
on(tagsModalCancel, 'click', closeTagsModal);
on(tagsModalSave, 'click', saveTagsFromModal);
on(tagsModal, 'click', e => { if (e.target === tagsModal) closeTagsModal(); });
on(tagsInput, 'keydown', e => { if (e.key === 'Enter') saveTagsFromModal(); });

on(labelModalClose, 'click', closeLabelModal);
on(labelModalCancel, 'click', closeLabelModal);
on(labelModal, 'click', e => { if (e.target === labelModal) closeLabelModal(); });

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
  if (!window.isSecureContext) {
    showToast(`মাইক্রোফোন এই ঠিকানায় কাজ করবে না${micContextHint()}`, 'error');
    return false;
  }
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

/** টেক্সটএরিয়ার ইনসার্ট/রিপ্লেস রেঞ্জ সেভ (কার্সার = start===end)। রেকর্ড বাটনে ক্লিকের আগে ব্লার হলে সিলেকশন হারায় — তাই pointerdown capture-এ ডাকতে হবে। */
function captureInsertRange() {
  if (!noteTextarea) return;
  const len = noteTextarea.value.length;
  let s = noteTextarea.selectionStart;
  let e = noteTextarea.selectionEnd;
  s = Math.max(0, Math.min(Number(s) || 0, len));
  e = Math.max(s, Math.min(Number(e) || 0, len));
  savedSelection = { start: s, end: e };
  if (replaceBanner) {
    if (s !== e) replaceBanner.classList.remove('hidden');
    else replaceBanner.classList.add('hidden');
  }
}

function clearSelection() { savedSelection = null; if (replaceBanner) replaceBanner.classList.add('hidden'); }

function loadVoiceInputMode() {
  try {
    const v = localStorage.getItem('vn-voice-input-mode');
    if (VOICE_INPUT_MODES.includes(v)) voiceInputMode = v;
  } catch { /* */ }
}

function persistVoiceInputMode() {
  try { localStorage.setItem('vn-voice-input-mode', voiceInputMode); } catch { /* */ }
}

function closeVoiceModeMenu() {
  voiceModeMenuOpen = false;
  voiceModeMenu?.classList.add('hidden');
  voiceModeBtn?.setAttribute('aria-expanded', 'false');
}

function toggleVoiceModeMenu() {
  if (isRecording || isProcessing) return;
  voiceModeMenuOpen = !voiceModeMenuOpen;
  voiceModeMenu?.classList.toggle('hidden', !voiceModeMenuOpen);
  voiceModeBtn?.setAttribute('aria-expanded', voiceModeMenuOpen ? 'true' : 'false');
}

function setVoiceInputMode(mode, { persist = true, closeMenu = true } = {}) {
  if (!VOICE_INPUT_MODES.includes(mode) || isRecording || isProcessing) return;
  voiceInputMode = mode;
  if (persist) persistVoiceInputMode();
  syncVoiceModeUi();
  if (closeMenu) closeVoiceModeMenu();
}

function syncVoiceModeUi() {
  voiceModeMenu?.querySelectorAll('.voice-mode-option').forEach(btn => {
    const active = btn.dataset.mode === voiceInputMode;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-checked', active ? 'true' : 'false');
  });
  const modeLabel = VOICE_MODE_LABELS[voiceInputMode] || voiceInputMode;
  if (voiceModeLabel) voiceModeLabel.textContent = modeLabel;
  if (voiceModeBtn) voiceModeBtn.setAttribute('aria-label', `মোড: ${modeLabel}. বদলাতে চাপুন`);
  if (voiceMainBtn) {
    voiceMainBtn.setAttribute('aria-label', `${modeLabel} — ${isRecording ? 'বন্ধ করুন' : 'শুরু করুন'}`);
  }
  if (bottomBar) bottomBar.dataset.voiceMode = voiceInputMode;
  syncVoiceFloatUi();
}

function syncVoiceFloatUi() {
  if (bottomBar) bottomBar.classList.toggle('voice-active', isRecording || segmentCount > 0);
}

function setVoiceRecordingUi(active) {
  voiceMainBtn?.classList.toggle('recording', active);
  syncVoiceFloatUi();
}

function setVoiceControlsDisabled(disabled) {
  if (voiceMainBtn) voiceMainBtn.disabled = disabled;
  if (voiceModeBtn) voiceModeBtn.disabled = disabled || isRecording;
}

function isLiveSession() {
  return recordingMode === 'live';
}

function isMicrophoneSupported() {
  return !!(navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function');
}

async function loadSonioxClientModule() {
  if (!sonioxClientLoad) {
    sonioxClientLoad = import(SONIOX_CLIENT_CDN);
  }
  return sonioxClientLoad;
}

async function getSonioxClient() {
  if (sonioxClient) return sonioxClient;
  const { SonioxClient } = await loadSonioxClientModule();
  sonioxClient = new SonioxClient({
    config: async () => {
      const res = await fetch(SONIOX_KEY_ENDPOINT, { method: 'POST' });
      if (!res.ok) {
        const e = await res.json().catch(() => ({}));
        throw new Error((e?.error || `HTTP ${res.status}`) + localDevApiHint(res.status));
      }
      const data = await res.json();
      if (!data?.api_key) throw new Error('Soniox temporary API key পাওয়া যায়নি');
      return { api_key: data.api_key };
    },
  });
  return sonioxClient;
}

function beginLiveInsertAnchor() {
  if (!noteTextarea) return;
  const len = noteTextarea.value.length;
  let start = savedSelection ? savedSelection.start : len;
  let end = savedSelection ? savedSelection.end : len;
  start = Math.max(0, Math.min(start, len));
  end = Math.max(start, Math.min(end, len));
  const existing = noteTextarea.value;
  liveInsertAnchor = {
    prefix: existing.slice(0, start),
    suffix: existing.slice(end),
    start,
    end,
  };
}

function getLiveDisplayText() {
  return liveFinalText + livePartialText;
}

function updateLiveTextareaInsert() {
  if (!liveInsertAnchor || !noteTextarea) return;
  const liveText = getLiveDisplayText();
  noteTextarea.value = liveInsertAnchor.prefix + liveText + liveInsertAnchor.suffix;
  updateNoteWordCount();
  if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
  const cursorPos = liveInsertAnchor.start + liveText.length;
  try {
    noteTextarea.setSelectionRange(cursorPos, cursorPos);
  } catch { /* */ }
}

function revertLiveTextareaInsert() {
  if (!liveInsertAnchor || !noteTextarea) return;
  noteTextarea.value = liveInsertAnchor.prefix + liveInsertAnchor.suffix;
  updateNoteWordCount();
  if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
}

function resetLiveTranscriptionState() {
  liveFinalText = '';
  livePartialText = '';
}

function clearLiveConnectTimeout() {
  if (liveConnectTimeout) {
    clearTimeout(liveConnectTimeout);
    liveConnectTimeout = null;
  }
}

function updateLiveProcessingLabel(state) {
  const labels = {
    starting: 'Soniox প্রস্তুত…',
    connecting: 'সংযুক্ত হচ্ছে…',
    recording: 'লাইভ শুনছে — কথা বলুন',
    stopping: 'শেষ হচ্ছে…',
  };
  if (labels[state] && segmentsText) segmentsText.textContent = labels[state];
}

async function startLiveRecording() {
  if (isProcessing || isRecording) return;
  if (!isMicrophoneSupported()) {
    showToast('মাইক্রোফোন পাওয়া যায়নি — HTTPS সাইট থেকে খুলুন (localhost বা Vercel URL)', 'error');
    return;
  }
  resetLiveTranscriptionState();
  beginLiveInsertAnchor();
  closeVoiceModeMenu();
  voiceModeBtn.disabled = true;
  if (segmentsText) segmentsText.textContent = 'লাইভ শুরু…';

  try {
    const client = await getSonioxClient();
    liveRecording = client.realtime.record({
      model: 'stt-rt-v5',
      language_hints: ['bn'],
      language_hints_strict: true,
      enable_endpoint_detection: true,
      auto_reconnect: true,
    });

    liveRecording.on('result', (result) => {
      let partial = '';
      for (const token of result.tokens) {
        if (token.translation_status && token.translation_status !== 'none' && token.translation_status !== 'original') {
          continue;
        }
        if (token.is_final) liveFinalText += token.text;
        else partial += token.text;
      }
      livePartialText = partial;
      updateLiveTextareaInsert();
    });

    liveRecording.on('state_change', ({ new_state }) => {
      updateLiveProcessingLabel(new_state);
      if (new_state === 'recording') clearLiveConnectTimeout();
      if (new_state === 'error') {
        clearLiveConnectTimeout();
        void stopLiveRecording({ disconnected: true });
      }
    });

    liveRecording.on('error', (err) => {
      console.error('[Soniox]', err);
      const msg = err?.message || String(err);
      if (/getUserMedia|microphone|AudioUnavailable/i.test(msg)) {
        showToast('মাইক্রোফোন পাওয়া যায়নি — HTTPS সাইট থেকে খুলুন ও অনুমতি দিন', 'error');
        void stopLiveRecording({ cancel: true, silent: true });
        return;
      }
      if (/session duration|temp_api_key_session_expired|403/i.test(msg)) {
        showToast('লাইভ সেশন শেষ — লেখা সেভ করা হয়েছে', 'warning');
      } else {
        showToast(`Soniox ত্রুটি: ${msg.slice(0, 80)}`, 'error');
      }
      void stopLiveRecording({ disconnected: true, silent: true });
    });

    clearLiveConnectTimeout();
    liveConnectTimeout = setTimeout(() => {
      if (!liveRecording || !isRecording) return;
      showToast('Soniox সংযোগ টাইমআউট — Vercel-এ SONIOX_API_KEY আছে কিনা দেখুন', 'error');
      void stopLiveRecording({ cancel: true, silent: true });
    }, 20000);

    isRecording = true;
    recordingMode = 'live';
    recordingStartedAt = Date.now();
    setVoiceRecordingUi(true);
    if (segmentsText) segmentsText.textContent = 'লাইভ… আবার চাপলে বন্ধ';
    recTimer.classList.remove('hidden');
    startTimer();
  } catch (err) {
    console.error('[Soniox]', err);
    revertLiveTextareaInsert();
    resetLiveTranscriptionState();
    liveRecording = null;
    showToast(`Soniox শুরু ব্যর্থ: ${(err.message || '').slice(0, 60)}`, 'error');
    voiceModeBtn.disabled = false;
    syncVoiceModeUi();
  }
}

async function stopLiveRecording(options = {}) {
  const { silent = false, cancel = false, disconnected = false } = options;
  if (liveStopping) return;
  if (!liveRecording && !isRecording) return;
  liveStopping = true;

  try {
    clearLiveConnectTimeout();
    voiceModeBtn.disabled = true;

    try {
      if (liveRecording) {
        if (cancel) liveRecording.cancel();
        else await liveRecording.stop();
      }
    } catch (err) {
      console.error('[Soniox stop]', err);
      if (!cancel && !disconnected) showToast(`Soniox বন্ধ করতে সমস্যা: ${(err.message || '').slice(0, 50)}`, 'warning');
    }

    livePartialText = '';
    if (cancel) {
      revertLiveTextareaInsert();
      resetLiveTranscriptionState();
    } else {
      updateLiveTextareaInsert();
      const spoken = getLiveDisplayText().trim();
      if (spoken) {
        saveCurrentNote(true);
        if (!silent) {
          const toastMsg = disconnected
            ? 'লাইভ সংযোগ শেষ — লেখা সেভ হয়েছে'
            : '✓ লাইভ ট্রান্সক্রিপশন সম্পন্ন';
          showToast(toastMsg, disconnected ? 'warning' : 'success');
        }
      } else if (!silent) {
        revertLiveTextareaInsert();
        resetLiveTranscriptionState();
        showToast('কিছু শোনা যায়নি — আবার চেষ্টা করুন', 'warning');
      }
      resetLiveTranscriptionState();
    }

    liveRecording = null;
    isRecording = false;
    instantRecording = false;
    recordingMode = '';
    recordingStartedAt = 0;
    setVoiceRecordingUi(false);
    stopTimer();
    updateSegmentsUI();
    clearSelection();
    voiceModeBtn.disabled = false;
    syncVoiceModeUi();
  } finally {
    liveStopping = false;
  }
}

async function toggleVoiceInput() {
  if (isProcessing) return;

  if (isRecording) {
    if (isLiveSession()) {
      if (Date.now() - recordingStartedAt < 700) {
        await stopLiveRecording({ cancel: true, silent: true });
        showToast('আরেকটু বেশি সময় ধরে রেকর্ড করুন', 'warning');
        return;
      }
      await stopLiveRecording();
      return;
    }
    if (recordingMode === 'instant') {
      if (Date.now() - recordingStartedAt < 700) {
        mediaRecorder.onstop = () => {
          isRecording = false;
          instantRecording = false;
          recordingMode = '';
          recordingStartedAt = 0;
          audioChunks = [];
          segmentCount = 0;
          stopTimer();
          setVoiceRecordingUi(false);
          updateSegmentsUI();
          syncVoiceModeUi();
          showToast('আরেকটু বেশি সময় ধরে রেকর্ড করুন', 'warning');
        };
        try { mediaRecorder.stop(); } catch {}
        return;
      }
      instantRecording = false;
      setVoiceRecordingUi(false);
      await onDone();
      return;
    }
    stopSegment();
    return;
  }

  if (voiceInputMode === 'live') {
    await startLiveRecording();
    return;
  }

  if (voiceInputMode === 'fast') {
    audioChunks = [];
    segmentCount = 0;
    updateSegmentsUI();
    const ok = await ensureMic();
    if (!ok) return;
    instantRecording = true;
    startSegment('instant');
    if (segmentsText) segmentsText.textContent = 'রেকর্ড হচ্ছে… আবার চাপলে ট্রান্সক্রাইব';
    return;
  }

  const ok = await ensureMic();
  if (ok) startSegment('manual');
}

function startSegment(mode = 'manual') {
  const opts = mimeType ? { mimeType } : {};
  mediaRecorder = new MediaRecorder(micStream, opts);
  mediaRecorder.ondataavailable = e => { if (e.data && e.data.size > 0) audioChunks.push(e.data); };
  mediaRecorder.start(100);
  isRecording = true;
  recordingMode = mode;
  recordingStartedAt = Date.now();
  setVoiceRecordingUi(true);
  closeVoiceModeMenu();
  voiceModeBtn.disabled = true;
  segmentsText.textContent = mode === 'instant' ? 'রেকর্ড হচ্ছe…' : 'রেকর্ড হচ্ছে…';
  recTimer.classList.remove('hidden');
  startTimer();
}

function stopSegment() {
  if (!mediaRecorder || mediaRecorder.state === 'inactive') return;
  mediaRecorder.onstop = () => {
    isRecording  = false;
    segmentCount += 1;
    recordingMode = '';
    recordingStartedAt = 0;
    stopTimer();
    instantRecording = false;
    setVoiceRecordingUi(false);
    voiceModeBtn.disabled = false;
    syncVoiceModeUi();
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
    segmentsText.textContent = `${segmentCount}টি খণ্ড  —  আরও যোগ করুন বা শেষ চাপুন`;
    clearRecBtn.classList.remove('hidden');
    doneBtn.disabled = false;
  }
  syncVoiceFloatUi();
}

function resetRecording() {
  clearLiveConnectTimeout();
  if (voiceInputMode === 'live' && (isRecording || liveRecording)) {
    if (liveRecording) {
      try { liveRecording.cancel(); } catch {}
      liveRecording = null;
    }
    revertLiveTextareaInsert();
    resetLiveTranscriptionState();
    isRecording = false;
    instantRecording = false;
    recordingMode = '';
    recordingStartedAt = 0;
    setVoiceRecordingUi(false);
    stopTimer();
    isProcessing = false;
    voiceMainBtn.disabled = false;
    voiceModeBtn.disabled = false;
    doneBtn.disabled = segmentCount === 0;
    showProcessing(false);
    syncVoiceModeUi();
  }
  if (isRecording && mediaRecorder) { mediaRecorder.onstop = null; try { mediaRecorder.stop(); } catch {} }
  isRecording = false; audioChunks = []; segmentCount = 0;
  instantRecording = false;
  recordingMode = '';
  recordingStartedAt = 0;
  stopTimer();
  setVoiceRecordingUi(false);
  voiceModeBtn.disabled = false;
  recTimer.classList.add('hidden');
  updateSegmentsUI();
  clearSelection();
  syncVoiceModeUi();
}

function startTimer() {
  timerSeconds = 0; updateTimerDisplay();
  timerInterval = setInterval(() => { timerSeconds++; updateTimerDisplay(); }, 1000);
}

function stopTimer() { clearInterval(timerInterval); timerInterval = null; recTimer.classList.add('hidden'); }
function updateTimerDisplay() {
  recTimer.textContent = `${Math.floor(timerSeconds/60)}:${String(timerSeconds%60).padStart(2,'0')}`;
}

/** ট্রান্সক্রিপশন বসানোর পর নতুন অংশ সিলেক্ট; স্ক্রল — সিলেকশনের শেষ দৃশ্যমান রেখে পুরো হাইলাইট ঢোকানো */
function focusNoteEditorWithInsertedRange(insertStart, insertEnd) {
  const ta = noteTextarea;
  if (!ta) return;
  const len = ta.value.length;
  const a = Math.max(0, Math.min(insertStart, len));
  const b = Math.max(a, Math.min(insertEnd, len));
  ta.focus({ preventScroll: true });
  try {
    ta.setSelectionRange(a, b, 'forward');
  } catch { /* */ }

  const cs = getComputedStyle(ta);
  const lh = Math.max(18, parseFloat(cs.lineHeight) || 24);
  const vh = ta.clientHeight;
  const pad = 28;

  const applyScroll = () => {
    const sh = ta.scrollHeight;
    const maxScroll = Math.max(0, sh - vh);
    if (maxScroll <= 0) return;

    const y0 = len > 0 ? (a / len) * sh : 0;
    let y1 = len > 0 ? (b / len) * sh : lh;
    if (y1 <= y0) y1 = y0 + lh * 0.5;

    const stLo = y1 + pad - vh;
    const stHi = y0 - pad;
    let st;
    if (stLo > stHi) {
      st = y0 - pad;
    } else {
      st = Math.max(0, Math.min(maxScroll, stLo));
      if (st > stHi) st = Math.max(0, Math.min(maxScroll, stHi));
    }
    st = Math.min(maxScroll, st + Math.round(lh * 0.65));
    ta.scrollTop = st;
  };

  applyScroll();
  requestAnimationFrame(applyScroll);
  requestAnimationFrame(() => requestAnimationFrame(applyScroll));
}

/* ══════════════════════════════════════════════════
   DONE — ট্রান্সক্রিপশন
══════════════════════════════════════════════════ */
async function onDone() {
  if (isProcessing) return;

  if (voiceInputMode === 'live') {
    if (isRecording) await stopLiveRecording();
    if (segmentCount === 0) {
      showToast('কোনো রেকর্ডিং নেই', 'warning');
      return;
    }
    segmentCount = 0;
    updateSegmentsUI();
    clearSelection();
    showToast('✓ সম্পন্ন', 'success');
    return;
  }

  if (audioChunks.length === 0 && !isRecording) { showToast('কোনো রেকর্ডিং নেই', 'warning'); return; }

  if (isRecording) {
    await new Promise(resolve => {
      mediaRecorder.onstop = () => {
        isRecording = false;
        segmentCount++;
        instantRecording = false;
        recordingMode = '';
        recordingStartedAt = 0;
        stopTimer();
        setVoiceRecordingUi(false);
        voiceModeBtn.disabled = false;
        syncVoiceModeUi();
        resolve();
      };
      mediaRecorder.stop();
    });
  }

  await transcribeAndInsert();
}

async function transcribeAndInsert() {
  isProcessing = true;
  voiceMainBtn.disabled = true;
  doneBtn.disabled = true;
  showProcessing(true, 'AI শুনছে…');

  try {
    const effectiveMime = mimeType || 'audio/webm';
    const blob   = new Blob(audioChunks, { type: effectiveMime });
    if (blob.size < 1200) {
      showToast('কোনো স্পষ্ট কথা শোনা যায়নি', 'warning');
      audioChunks = []; segmentCount = 0; updateSegmentsUI();
      return;
    }
    const base64 = await blobToBase64(blob);

    showProcessing(true, `বিশ্লেষণ হচ্ছে… (${(blob.size/1024).toFixed(0)} KB)`);

    const res = await fetch(TRANSCRIBE_ENDPOINT, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        audio: base64,
        mimeType: effectiveMime,
        model: appPrefs.transcriptionModel || 'gemini-2.5-flash',
      }),
    });

    if (!res.ok) {
      const e = await res.json().catch(() => ({}));
      const hint = e?.hint ? ` — ${e.hint}` : localDevApiHint(res.status);
      throw new Error((e?.error || `HTTP ${res.status}`) + hint);
    }

    const data = await res.json();
    const text = data?.text?.trim();

    if (!text) { showToast('কিছু শোনা যায়নি — আবার চেষ্টা করুন', 'warning'); return; }

    const existing = noteTextarea.value;

    if (savedSelection) {
      const { start, end } = savedSelection;
      noteTextarea.value = existing.slice(0, start) + text + existing.slice(end);
      const insertEnd = start + text.length;
      clearSelection();
      updateNoteWordCount();
      requestAnimationFrame(() => {
        requestAnimationFrame(() => focusNoteEditorWithInsertedRange(start, insertEnd));
      });
      showToast(start !== end ? '✓ সিলেক্ট করা অংশ রিপ্লেস হয়েছে' : '✓ কার্সারের জায়গায় যোগ হয়েছে', 'success');
    } else {
      const trimmed = existing.trimEnd();
      noteTextarea.value = existing ? trimmed + '\n' + text : text;
      const insertStart = existing ? trimmed.length + 1 : 0;
      const insertEnd = insertStart + text.length;
      updateNoteWordCount();
      requestAnimationFrame(() => {
        requestAnimationFrame(() => focusNoteEditorWithInsertedRange(insertStart, insertEnd));
      });
      showToast('✓ ট্রান্সক্রিপশন সম্পন্ন', 'success');
    }

    if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
    if (appPrefs.transcribeAutoSave !== false) saveCurrentNote(true);
    audioChunks = []; segmentCount = 0; updateSegmentsUI();

  } catch (err) {
    console.error('[Gemini]', err);
    showToast(`ত্রুটি: ${(err.message||'').slice(0,60)}`, 'error');
  } finally {
    isProcessing = false;
    voiceMainBtn.disabled = false;
    showProcessing(false);
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

function closeAppConfirm(result = false) {
  if (appConfirmDialog) appConfirmDialog.classList.add('hidden');
  const resolve = confirmResolver;
  confirmResolver = null;
  if (resolve) resolve(!!result);
}

function appConfirm({
  title = 'নিশ্চিত করবেন?',
  message = 'এই কাজটি চালিয়ে যেতে চান?',
  okText = 'নিশ্চিত করুন',
  cancelText = 'বাতিল',
  tone = 'danger',
  icon = '!',
} = {}) {
  if (!appConfirmDialog) return Promise.resolve(false);
  if (confirmResolver) closeAppConfirm(false);
  if (appConfirmTitle) appConfirmTitle.textContent = title;
  if (appConfirmMessage) appConfirmMessage.textContent = message;
  if (appConfirmOk) appConfirmOk.textContent = okText;
  if (appConfirmCancel) appConfirmCancel.textContent = cancelText;
  if (appConfirmIcon) appConfirmIcon.textContent = icon;
  if (appConfirmOk) appConfirmOk.classList.toggle('app-dialog-danger-btn', tone === 'danger');
  if (appConfirmIcon) appConfirmIcon.classList.toggle('app-dialog-icon-danger', tone === 'danger');
  appConfirmDialog.classList.remove('hidden');
  setTimeout(() => appConfirmCancel?.focus(), 40);
  return new Promise(resolve => { confirmResolver = resolve; });
}

function closePinResetDialog(value = null) {
  if (pinResetDialog) pinResetDialog.classList.add('hidden');
  const resolve = pinResetResolver;
  pinResetResolver = null;
  if (resolve) resolve(value);
}

function requestPinResetValue(username) {
  if (!pinResetDialog) {
    showToast('PIN reset ডায়ালগ লোড হয়নি', 'error');
    return Promise.resolve(null);
  }
  if (pinResetResolver) closePinResetDialog(null);
  if (pinResetTitle) pinResetTitle.textContent = `${username || 'ইউজার'} — পিন রিসেট`;
  if (pinResetMessage) pinResetMessage.textContent = 'নতুন পিন লিখুন। ইউজার পরের লগইনে নিজের পিন বদলাবে।';
  if (pinResetInput) pinResetInput.value = '';
  if (pinResetError) pinResetError.textContent = '';
  pinResetDialog.classList.remove('hidden');
  setTimeout(() => pinResetInput?.focus(), 80);
  return new Promise(resolve => { pinResetResolver = resolve; });
}

/* ══════════════════════════════════════════════════
   সেটিংস পেজ
══════════════════════════════════════════════════ */
function openSettingsPage() {
  updateListSortButton();
  syncSettingsControls();
  if (screenSettings) {
    screenSettings.classList.add('active');
    history.pushState({ screen: 'settings' }, '');
  }
}

function closeSettingsPage() {
  if (screenSettings) screenSettings.classList.remove('active');
}

const SETTINGS_TAB_IDS = ['look', 'list', 'editor', 'data', 'user'];

function getSettingsTabNodes() {
  return {
    tabs: document.querySelectorAll('.settings-tab[data-settings-tab]'),
    panels: document.querySelectorAll('.settings-tab-panel[data-settings-tab]'),
  };
}

function activateSettingsTab(tabId) {
  let id = tabId;
  if (!SETTINGS_TAB_IDS.includes(id)) id = 'look';
  const { tabs, panels } = getSettingsTabNodes();
  if (!tabs.length) return;
  tabs.forEach(t => {
    const on = t.dataset.settingsTab === id;
    t.setAttribute('aria-selected', on ? 'true' : 'false');
    t.tabIndex = on ? 0 : -1;
  });
  panels.forEach(p => {
    if (p.dataset.settingsTab === id) p.removeAttribute('hidden');
    else p.setAttribute('hidden', '');
  });
  if (id === 'user' && appIsAdmin) loadAdminUsers();
  try { localStorage.setItem('vn-settings-tab', id); } catch { /* */ }
}

function initSettingsTabs() {
  const { tabs } = getSettingsTabNodes();
  if (!tabs.length) return;
  tabs.forEach(tab => on(tab, 'click', () => activateSettingsTab(tab.dataset.settingsTab)));
  const page = $('settings-page');
  on(page, 'keydown', e => {
    const t = e.target;
    if (!t || !t.classList || !t.classList.contains('settings-tab')) return;
    const ix = SETTINGS_TAB_IDS.indexOf(t.dataset.settingsTab);
    if (ix < 0) return;
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const dir = e.key === 'ArrowRight' ? 1 : -1;
      const next = SETTINGS_TAB_IDS[(ix + dir + SETTINGS_TAB_IDS.length) % SETTINGS_TAB_IDS.length];
      activateSettingsTab(next);
      const nextBtn = [...getSettingsTabNodes().tabs].find(x => x.dataset.settingsTab === next);
      if (nextBtn) nextBtn.focus();
    }
  });
  let initial = 'look';
  try {
    const s = localStorage.getItem('vn-settings-tab');
    if (s && SETTINGS_TAB_IDS.includes(s)) initial = s;
  } catch { /* */ }
  activateSettingsTab(initial);
}

on(settingsBtn, 'click', openSettingsPage);
on(settingsBackBtn, 'click', () => { if (screenSettings?.classList.contains('active')) history.back(); });

on(backupExportBtn, 'click', exportVoiceNotesBackup);
on(backupImportBtn, 'click', () => { if (backupImportInput) backupImportInput.click(); });
on(backupImportInput, 'change', onBackupImportFile);

on(appConfirmCancel, 'click', () => closeAppConfirm(false));
on(appConfirmOk, 'click', () => closeAppConfirm(true));
on(appConfirmDialog, 'click', e => { if (e.target === appConfirmDialog) closeAppConfirm(false); });
on(appConfirmDialog, 'keydown', e => {
  if (e.key === 'Escape') closeAppConfirm(false);
  if (e.key === 'Enter') closeAppConfirm(true);
});

on(pinResetCancel, 'click', () => closePinResetDialog(null));
on(pinResetOk, 'click', () => {
  const value = pinResetInput?.value || '';
  if (value.length < 4) {
    if (pinResetError) {
      pinResetError.textContent = 'পিন কমপক্ষে ৪ অক্ষর দিন';
      pinResetError.classList.add('auth-sheet-message-error');
    }
    return;
  }
  closePinResetDialog(value);
});
on(pinResetDialog, 'click', e => { if (e.target === pinResetDialog) closePinResetDialog(null); });
on(pinResetInput, 'keydown', e => {
  if (e.key === 'Escape') closePinResetDialog(null);
  if (e.key === 'Enter') {
    e.preventDefault();
    pinResetOk?.click();
  }
});

async function confirmAndSignOut() {
  const ok = await appConfirm({
    title: 'সাইন আউট করবেন?',
    message: 'এই ডিভাইসে আপনার সেশন শেষ হবে।',
    okText: 'সাইন আউট',
    cancelText: 'থাক',
    tone: 'danger',
    icon: '⎋',
  });
  if (!ok) return;
  await exitAppSession();
  showToast('সাইন আউট হয়েছে', 'info');
}

on(authSignOutBtn, 'click', confirmAndSignOut);
on(rootSignOutBtn, 'click', confirmAndSignOut);
on(folderSignOutBtn, 'click', confirmAndSignOut);

on(authSigninBtn, 'click', async () => {
  if (!sb) {
    setAuthSheetMessage('ক্লায়েন্ট লোড হয়নি — পেজ রিফ্রেশ করুন', true);
    return;
  }
  const email = (authUsername && authUsername.value ? authUsername.value : '').trim();
  const password = authPassword && authPassword.value ? authPassword.value : '';
  if (!email) {
    setAuthSheetMessage('ইমেইল লিখুন', true);
    return;
  }
  if (!password) {
    setAuthSheetMessage('পাসওয়ার্ড লিখুন', true);
    return;
  }
  if (authSigninBtn) authSigninBtn.disabled = true;
  setAuthSheetMessage('লগইন…', false);
  const { data, error } = await sb.auth.signInWithPassword({ email, password });
  if (authSigninBtn) authSigninBtn.disabled = false;
  if (error) {
    setAuthSheetMessage(error.message === 'Invalid login credentials' ? 'ভুল ইমেইল বা পাসওয়ার্ড' : error.message, true);
    return;
  }
  try {
    await applySupabaseAuthSession(data.session);
  } catch (err) {
    await sb.auth.signOut();
    clearAppSession();
    setAuthSheetMessage(
      err.message === 'no_vn_access' ? 'এই অ্যাকাউন্টে Voice Notes access নেই' : (err.message || 'লগইন ব্যর্থ'),
      true,
    );
    return;
  }
  setAuthSheetMessage('', false);
  showToast('লগইন হয়েছে', 'success');
  await enterAppAfterSession();
});

on(adminCreateUserBtn, 'click', async () => {
  setAdminCreateMessage('Supabase Dashboard → Authentication ও vn_profiles থেকে user যোগ করুন', true);
});

on(accountChangePinBtn, 'click', async () => {
  setAccountPinMessage('পাসওয়ার্ড Supabase Dashboard → Authentication থেকে বদলান', true);
});

on(adminRefreshUsersBtn, 'click', loadAdminUsers);

on(authPassword, 'keydown', e => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (authSigninBtn) authSigninBtn.click();
});

on(authPinToggle, 'click', () => {
  if (!authPassword) return;
  authPassword.type = authPassword.type === 'password' ? 'text' : 'password';
  if (authPinToggle) authPinToggle.style.color = authPassword.type === 'text' ? 'var(--accent)' : '';
});

on(adminNewPinToggle, 'click', () => {
  if (!adminNewPin) return;
  adminNewPin.type = adminNewPin.type === 'password' ? 'text' : 'password';
  if (adminNewPinToggle) adminNewPinToggle.style.color = adminNewPin.type === 'text' ? 'var(--accent)' : '';
});

document.querySelectorAll('.segmented-control, .settings-list-options').forEach(group => {
  on(group, 'click', e => {
    const btn = e.target.closest('[data-value]');
    if (!btn || !group.contains(btn)) return;
    const setting = group.dataset.setting;
    const value = btn.dataset.value;

    if (setting === 'defaultSort') {
      listSort = value;
      try { localStorage.setItem('vn-list-sort', listSort); } catch { /* */ }
      updateListSortButton();
      renderNotesList();
      return;
    }

    if (setting === 'autoSaveDelay') {
      savePref(setting, Number(value) || 2000);
    } else if (setting) {
      savePref(setting, value);
    }
  });
});

on(transcribeAutoSaveToggle, 'change', () => {
  savePref('transcribeAutoSave', transcribeAutoSaveToggle.checked);
});

const transcriptionModelSelect = document.getElementById('pref-transcription-model');
on(transcriptionModelSelect, 'change', () => {
  if (TRANSCRIPTION_MODELS.includes(transcriptionModelSelect.value)) {
    savePref('transcriptionModel', transcriptionModelSelect.value);
  }
});

/* ══════════════════════════════════════════════════
   ইভেন্ট বাইন্ডিং
══════════════════════════════════════════════════ */
on(folderBackBtn, 'click', closeFolder);
on(renameFolderBtn, 'click', () => { if (currentFolderId) openFolderFormModal('rename', currentFolderId); });

on(createFabBtn, 'click', (e) => {
  e.stopPropagation();
  toggleCreateFabMenu();
});
createFabMenu?.querySelectorAll('.create-fab-option').forEach(btn => {
  on(btn, 'click', () => handleCreateFabAction(btn.dataset.create));
});

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

on(voiceMainBtn, 'click', toggleVoiceInput);
on(voiceMainBtn, 'pointerdown', () => {
  if (isProcessing || isRecording) return;
  if (voiceInputMode !== 'live' && segmentCount !== 0) return;
  captureInsertRange();
}, { capture: true });

on(voiceModeBtn, 'click', (e) => {
  e.stopPropagation();
  toggleVoiceModeMenu();
});
voiceModeMenu?.querySelectorAll('.voice-mode-option').forEach(btn => {
  on(btn, 'click', () => setVoiceInputMode(btn.dataset.mode));
});
document.addEventListener('click', (e) => {
  if (cardActionOpen) {
    if (!cardActionPopover?.contains(e.target) && !cardActionAnchor?.contains(e.target)) {
      closeCardActionPopover();
      if (e.target.closest('.note-card-body, .folder-card-body')) {
        e.preventDefault();
        e.stopPropagation();
      }
    }
  }
}, true);

document.addEventListener('click', (e) => {
  if (createFabMenuOpen && !createFabWrap?.contains(e.target)) {
    closeCreateFabMenu();
  }
  if (!voiceModeMenuOpen) return;
  if (voiceCombo?.contains(e.target)) return;
  closeVoiceModeMenu();
});

on(notesList, 'scroll', () => {
  if (cardActionOpen) closeCardActionPopover();
}, { passive: true });

on(doneBtn, 'click', onDone);

on(clearRecBtn, 'click', async () => {
  if (voiceInputMode === 'live') {
    if (isRecording || liveRecording) {
      await stopLiveRecording({ cancel: true, silent: true });
    }
    segmentCount = 0;
    updateSegmentsUI();
    showToast('রেকর্ডিং মুছে গেছে');
    return;
  }
  if (isRecording) {
    mediaRecorder.onstop = null;
    try { mediaRecorder.stop(); } catch {}
    isRecording = false;
    instantRecording = false;
    recordingMode = '';
    recordingStartedAt = 0;
    setVoiceRecordingUi(false);
    syncVoiceModeUi();
    stopTimer();
  }
  audioChunks=[]; segmentCount=0; updateSegmentsUI(); showToast('রেকর্ডিং মুছে গেছে');
});

on(cancelReplaceBtn, 'click', () => { clearSelection(); audioChunks=[]; segmentCount=0; updateSegmentsUI(); });

on(noteTextarea, 'input', () => {
  if (noteTitleDisplay) noteTitleDisplay.textContent = getEditorDisplayTitle();
  updateNoteWordCount();
  clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => saveCurrentNote(true), appPrefs.autoSaveDelay || 2000);
});

document.addEventListener('visibilitychange', () => { if (document.hidden && currentNoteId) saveCurrentNote(true); });

function syncVisualViewportHeight() {
  const vv = window.visualViewport;
  const height = vv ? vv.height : window.innerHeight;
  const top = vv ? vv.offsetTop : 0;
  document.documentElement.style.setProperty('--app-top', `${Math.round(top)}px`);
  document.documentElement.style.setProperty('--app-height', `${Math.round(height)}px`);

  const keyboardInset = vv ? Math.max(0, window.innerHeight - vv.height - top) : 0;
  document.documentElement.classList.toggle('keyboard-open', keyboardInset > 120);
}

syncVisualViewportHeight();
window.addEventListener('resize', syncVisualViewportHeight);
window.visualViewport?.addEventListener('resize', syncVisualViewportHeight);
window.visualViewport?.addEventListener('scroll', syncVisualViewportHeight);

/* ══════════════════════════════════════════════════
   ব্যাচ ট্রান্সক্রিপশন
══════════════════════════════════════════════════ */
const screenBatch        = $('screen-batch');
const batchBackBtn       = $('batch-back-btn');
const batchBtn           = $('batch-btn');
const batchFolderBtn     = $('batch-folder-btn');
const batchChangeFolderBtn = $('batch-change-folder-btn');
const batchStartBtn      = $('batch-start-btn');
const batchDoneBtn       = $('batch-done-btn');
const batchAgainBtn      = $('batch-again-btn');
const batchStepPick      = $('batch-step-pick');
const batchStepFiles     = $('batch-step-files');
const batchStepProgress  = $('batch-step-progress');
const batchFileCount     = $('batch-file-count');
const batchFolderPath    = $('batch-folder-path');
const batchFileList      = $('batch-file-list');
const batchProgressFill  = $('batch-progress-fill');
const batchProgressLabel = $('batch-progress-label');
const batchDoneCount     = $('batch-done-count');
const batchTotalCount    = $('batch-total-count');
const batchErrBadge      = $('batch-err-badge');
const batchErrCount      = $('batch-err-count');
const batchProgressList  = $('batch-progress-list');
const batchProgressFooter = $('batch-progress-footer');
const batchBrowserNote   = $('batch-browser-note');

const AUDIO_EXTS = new Set(['mp3','wav','m4a','ogg','webm','mp4','flac','aac','opus','wma']);
const AUDIO_MIME = {
  mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4', ogg: 'audio/ogg',
  webm: 'audio/webm', mp4: 'audio/mp4', flac: 'audio/flac', aac: 'audio/aac',
  opus: 'audio/ogg', wma: 'audio/x-ms-wma',
};

let batchFiles = [];         // { file, name, path, ext }
let batchRunning = false;

function openBatchScreen() {
  if (!batchRunning) {
    batchFiles = [];
    batchShowStep('batch-step-pick');
  }
  if (screenBatch) screenBatch.classList.add('active');
  history.pushState({ screen: 'batch' }, '');
  if (batchBrowserNote) {
    batchBrowserNote.textContent = 'showDirectoryPicker' in window
      ? ''
      : 'আপনার ব্রাউজার File System Access API সাপোর্ট করে না। Chrome বা Edge ব্যবহার করুন।';
  }
}

function closeBatchScreen() {
  if (batchRunning) return;
  if (screenBatch) screenBatch.classList.remove('active');
}

function batchShowStep(step) {
  ['batch-step-pick','batch-step-files','batch-step-progress'].forEach(id => {
    const el = $(id);
    if (el) el.classList.toggle('hidden', id !== step);
  });
}

async function batchScanDir(dirHandle, pathPrefix = '') {
  const results = [];
  for await (const [name, handle] of dirHandle.entries()) {
    if (handle.kind === 'directory') {
      const sub = await batchScanDir(handle, pathPrefix ? `${pathPrefix}/${name}` : name);
      results.push(...sub);
    } else {
      const ext = name.split('.').pop().toLowerCase();
      if (AUDIO_EXTS.has(ext)) {
        results.push({ handle, name, path: pathPrefix ? `${pathPrefix}/${name}` : name, ext });
      }
    }
  }
  return results;
}

async function batchPickFolder() {
  if (!('showDirectoryPicker' in window)) {
    showToast('এই ব্রাউজার ফোল্ডার পিকার সাপোর্ট করে না। Chrome/Edge ব্যবহার করুন।');
    return;
  }
  let dirHandle;
  try {
    dirHandle = await window.showDirectoryPicker({ mode: 'read' });
  } catch { return; }

  showToast('ফাইল খুঁজছে…');
  try {
    const found = await batchScanDir(dirHandle);
    batchFiles = found;

    if (!found.length) {
      showToast('কোনো অডিও ফাইল পাওয়া যায়নি।');
      return;
    }

    if (batchFileCount)  batchFileCount.textContent = found.length;
    if (batchFolderPath) batchFolderPath.textContent = dirHandle.name;

    if (batchFileList) {
      batchFileList.innerHTML = '';
      found.forEach(f => {
        const li = document.createElement('li');
        li.className = 'batch-file-item';
        li.setAttribute('role', 'listitem');
        li.innerHTML = `
          <span class="batch-file-icon" aria-hidden="true">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
          </span>
          <span class="batch-file-info">
            <span class="batch-file-name">${escHtml(f.name)}</span>
            ${f.path !== f.name ? `<span class="batch-file-path">${escHtml(f.path)}</span>` : ''}
          </span>
        `;
        batchFileList.appendChild(li);
      });
    }

    batchShowStep('batch-step-files');
  } catch (err) {
    showToast('ফোল্ডার পড়তে পারেনি: ' + err.message);
  }
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function batchSaveNoteDirectly(title, content) {
  const now = new Date().toISOString();
  const id  = crypto.randomUUID();
  notes[id] = {
    title: title || 'নতুন নোট',
    content,
    titleCustom: !!title,
    created: now,
    updated: now,
    folderId: null,
    pinned: false,
    tags: [],
    labelColor: '',
  };
  persistNotes();
  sbUpsertNote(id);
  return id;
}

function batchUpdateProgress(index, status, sub = '') {
  const item = batchProgressList?.querySelector(`[data-batch-idx="${index}"]`);
  if (!item) return;
  const statusEl = item.querySelector('.batch-prog-status');
  const subEl    = item.querySelector('.batch-prog-sub');
  if (statusEl) {
    statusEl.className = `batch-prog-status ${status}`;
    statusEl.innerHTML = batchStatusIcon(status);
  }
  if (subEl && sub) subEl.textContent = sub;
}

function batchStatusIcon(status) {
  if (status === 'waiting')  return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/></svg>`;
  if (status === 'running')  return `<svg class="batch-spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4"/></svg>`;
  if (status === 'done')     return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>`;
  if (status === 'error')    return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`;
  if (status === 'skipped')  return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="5" y1="12" x2="19" y2="12"/></svg>`;
  return '';
}

async function batchUploadToGemini(file, mimeType) {
  const sessionRes = await fetch('/api/file-session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mimeType, size: file.size, displayName: file.name }),
  });
  if (!sessionRes.ok) {
    const e = await sessionRes.json().catch(() => ({}));
    throw new Error(e.error || `Session error ${sessionRes.status}`);
  }
  const { uploadUrl } = await sessionRes.json();

  const uploadRes = await fetch(uploadUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': mimeType,
      'X-Goog-Upload-Offset': '0',
      'X-Goog-Upload-Command': 'upload, finalize',
    },
    body: file,
  });
  if (!uploadRes.ok) throw new Error(`Upload failed: HTTP ${uploadRes.status}`);

  const data = await uploadRes.json();
  const fileUri = data?.file?.uri;
  if (!fileUri) throw new Error('File URI পাওয়া যায়নি');
  return { fileUri, mimeType };
}

async function batchTranscribeOne(fileEntry) {
  const file     = await fileEntry.handle.getFile();
  const mimeType = AUDIO_MIME[fileEntry.ext] || 'audio/mpeg';
  const { fileUri } = await batchUploadToGemini(file, mimeType);

  const res = await fetch(TRANSCRIBE_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      fileUri,
      mimeType,
      model: appPrefs.transcriptionModel,
    }),
  });
  if (!res.ok) {
    const e = await res.json().catch(() => ({}));
    throw new Error((e.error || `HTTP ${res.status}`) + localDevApiHint(res.status));
  }
  const { text } = await res.json();
  return text || '';
}

async function batchStartTranscription() {
  if (!batchFiles.length) return;
  batchRunning = true;
  if (batchStartBtn) batchStartBtn.disabled = true;

  batchShowStep('batch-step-progress');
  if (batchProgressFooter) batchProgressFooter.classList.add('hidden');
  if (batchErrBadge) batchErrBadge.classList.add('hidden');

  if (batchProgressList) {
    batchProgressList.innerHTML = '';
    batchFiles.forEach((f, i) => {
      const li = document.createElement('li');
      li.className = 'batch-prog-item';
      li.setAttribute('role', 'listitem');
      li.setAttribute('data-batch-idx', i);
      li.innerHTML = `
        <span class="batch-prog-status waiting">${batchStatusIcon('waiting')}</span>
        <span class="batch-prog-info">
          <span class="batch-prog-name">${escHtml(f.name)}</span>
          <span class="batch-prog-sub">অপেক্ষায়…</span>
        </span>
      `;
      batchProgressList.appendChild(li);
    });
  }

  if (batchTotalCount) batchTotalCount.textContent = batchFiles.length;
  if (batchDoneCount)  batchDoneCount.textContent  = 0;

  let doneCount = 0;
  let errCount  = 0;

  for (let i = 0; i < batchFiles.length; i++) {
    const f = batchFiles[i];
    batchUpdateProgress(i, 'running', 'আপলোড হচ্ছে…');
    if (batchProgressLabel) batchProgressLabel.textContent = `${f.name} ট্রান্সক্রাইব হচ্ছে…`;
    if (batchProgressFill)  batchProgressFill.style.width  = `${Math.round(i / batchFiles.length * 100)}%`;

    try {
      batchUpdateProgress(i, 'running', 'ট্রান্সক্রাইব হচ্ছে…');
      const text = await batchTranscribeOne(f);

      if (!text.trim()) {
        batchUpdateProgress(i, 'skipped', 'কোনো কথা পাওয়া যায়নি');
      } else {
        const titleBase = f.name.replace(/\.[^.]+$/, '');
        batchSaveNoteDirectly(titleBase, text.trim());
        batchUpdateProgress(i, 'done', 'সম্পন্ন — নোট সেভ হয়েছে');
      }
      doneCount++;
    } catch (err) {
      errCount++;
      batchUpdateProgress(i, 'error', 'ত্রুটি: ' + err.message);
    }

    if (batchDoneCount) batchDoneCount.textContent = doneCount + (errCount > 0 ? 0 : 0);
    if (batchErrCount)  batchErrCount.textContent  = errCount;
    if (errCount > 0 && batchErrBadge) batchErrBadge.classList.remove('hidden');
    if (batchDoneCount) batchDoneCount.textContent = i + 1 - errCount > 0 ? i + 1 - errCount : 0;

    if (i === batchFiles.length - 1) {
      if (batchProgressFill)  batchProgressFill.style.width  = '100%';
      if (batchProgressLabel) batchProgressLabel.textContent = `সম্পন্ন! ${doneCount}টি নোট সেভ হয়েছে।`;
      if (batchDoneCount) batchDoneCount.textContent = doneCount;
    }
  }

  batchRunning = false;
  renderNotesList();
  if (batchProgressFooter) batchProgressFooter.classList.remove('hidden');
  if (batchStartBtn) batchStartBtn.disabled = false;
}

on(batchBtn, 'click', openBatchScreen);
on(batchBackBtn, 'click', () => { if (screenBatch?.classList.contains('active')) history.back(); });
on(batchFolderBtn, 'click', batchPickFolder);
on(batchChangeFolderBtn, 'click', () => { batchFiles = []; batchShowStep('batch-step-pick'); });
on(batchStartBtn, 'click', batchStartTranscription);
on(batchDoneBtn, 'click', () => { closeBatchScreen(); history.back(); });
on(batchAgainBtn, 'click', () => { batchFiles = []; batchRunning = false; batchShowStep('batch-step-pick'); });

/* অ্যান্ড্রয়েড ব্যাক বাটন */
window.addEventListener('popstate', () => {
  if (screenBatch && screenBatch.classList.contains('active')) {
    closeBatchScreen();
    return;
  }
  if (screenSettings && screenSettings.classList.contains('active')) {
    closeSettingsPage();
    return;
  }
  if (screenEditor && screenEditor.classList.contains('active')) {
    saveCurrentNote(true); resetRecording(); stopEditorPeriodicSave(); screenEditor.classList.remove('active');
    history.pushState(null, '');
  } else if (currentFolderId) {
    closeFolder(); history.pushState(null, '');
  }
});

/* ══════════════════════════════════════════════════
   শুরু
══════════════════════════════════════════════════ */
loadPrefs();
applyPrefs();
loadVoiceInputMode();
syncVoiceModeUi();
syncCreateFabMenu();
try {
  const s = localStorage.getItem('vn-list-sort');
  if (s) listSort = s;
} catch { /* */ }
notes = {};
folders = {};
syncSettingsControls();
initSettingsTabs();
buildLabelSwatches();
updateListHeader();
updateListSortButton();
renderNotesList();
updateCloudSyncSummary();
history.replaceState({ screen: 'list' }, '');

void (async () => {
  await initSupabase();
})();

if ('serviceWorker' in navigator) {
  let refreshing = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (refreshing) return;
    refreshing = true;
    window.location.reload();
  });
  navigator.serviceWorker.addEventListener('message', event => {
    if (event.data?.type === 'APP_UPDATED') window.location.reload();
  });
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js').then(reg => reg.update()).catch(console.warn);
  });
}
