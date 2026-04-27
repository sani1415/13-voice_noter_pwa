'use strict';

/* ══════════════════════════════════════════════════
   কনফিগ
══════════════════════════════════════════════════ */
const TRANSCRIBE_ENDPOINT = '/.netlify/functions/transcribe';
try { localStorage.removeItem('vn-api-key'); } catch { /* পুরনো leaked key cache থাকলে মুছে দাও */ }

/* ══════════════════════════════════════════════════
   Supabase কনফিগ
══════════════════════════════════════════════════ */
const SB_URL = 'https://iqjajofqaimnqvrxgdzc.supabase.co';
const SB_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxamFqb2ZxYWltbnF2cnhnZHpjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMDM0NzYsImV4cCI6MjA4Njc3OTQ3Nn0.epEV-GGXUsJe-u4Aumx284qGPrCFZcuXFGN9qmtwwbM';

let sb = null; // Supabase client (কাস্টম PIN ইউজার + RPC; Supabase Auth নয়)
/** vn_app_users.id — লোকাল ক্যাশ কী */
let appUserId = null;
let appUsername = '';
let appIsAdmin = false;
let appMustChangePin = false;
let sessionToken = null;

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
let appPrefs = {
  theme: 'system',
  fontSize: 'medium',
  listDensity: 'comfortable',
  autoSaveDelay: 2000,
  transcribeAutoSave: true,
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
const folderActionLabel   = $('folder-action-label');
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
const folderSortBtn       = $('folder-sort-btn');
const listSortLabel       = $('list-sort-label');
const settingsBackBtn     = $('settings-back-btn');
const settingsSortSummary = $('settings-sort-summary');
const themeValue          = $('theme-value');
const fontSizeValue       = $('font-size-value');
const editorPaperValue    = $('editor-paper-value');
const accentValue         = $('accent-value');
const listDensityValue    = $('list-density-value');
const autoSaveDelayValue  = $('autosave-delay-value');
const noteActionSheet     = $('note-action-sheet');
const noteActionTitle     = $('note-action-title');
const noteActionRename    = $('note-action-rename');
const noteActionMove      = $('note-action-move');
const noteActionDelete    = $('note-action-delete');
const noteActionCancel    = $('note-action-cancel');
const noteActionPin       = $('note-action-pin');
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
  if (saveStatus) {
    saveStatus.textContent = '';
    saveStatus.classList.remove('visible');
  }
  if (screenEditor) screenEditor.classList.remove('active');
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

function persistAppSessionFromLogin(data) {
  if (!data || data.ok !== true) return;
  sessionToken = data.session_token || null;
  appUserId = data.user_id || null;
  appUsername = data.username || '';
  appIsAdmin = !!data.is_admin;
  appMustChangePin = !!data.must_change_pin;
  try {
    if (sessionToken) localStorage.setItem('vn-app-session-token', sessionToken);
    if (appUserId) localStorage.setItem('vn-app-user-id', appUserId);
    localStorage.setItem('vn-app-username', appUsername);
    localStorage.setItem('vn-app-is-admin', appIsAdmin ? '1' : '0');
    localStorage.setItem('vn-app-must-change-pin', appMustChangePin ? '1' : '0');
  } catch { /* */ }
}

function clearAppSession() {
  sessionToken = null;
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
  if (!sb || !sessionToken || !appIsAdmin || !adminUsersList) return;
  adminUsersList.innerHTML = '<p class="settings-user-empty">ইউজার লোড হচ্ছে…</p>';
  const { data, error } = await sb.rpc('vn_admin_list_users', { p_session_token: sessionToken });
  if (error || !data || data.ok !== true) {
    adminUsersList.innerHTML = `<p class="settings-user-empty settings-user-empty-error">${esc(error?.message || userRpcMessage(data?.error))}</p>`;
    return;
  }
  renderAdminUsers(data.users);
}

async function resetUserPin(user) {
  if (!sb || !sessionToken || !user?.id) return;
  const newPin = await requestPinResetValue(user.username);
  if (newPin == null) return;
  if (newPin.length < 4) {
    showToast('পিন কমপক্ষে ৪ অক্ষর দিন', 'warning');
    return;
  }
  const { data, error } = await sb.rpc('vn_admin_reset_user_pin', {
    p_session_token: sessionToken,
    p_user_id: user.id,
    p_new_pin: newPin,
  });
  if (error || !data || data.ok !== true) {
    showToast(error?.message || userRpcMessage(data?.error), 'error');
    return;
  }
  showToast('পিন রিসেট হয়েছে — ইউজারকে লগইনে নতুন পিন বদলাতে হবে', 'success');
  await loadAdminUsers();
}

async function toggleUserActive(user) {
  if (!sb || !sessionToken || !user?.id) return;
  const nextActive = user.is_active === false;
  const action = nextActive ? 'চালু' : 'বন্ধ';
  const ok = await appConfirm({
    title: `ইউজার ${action} করবেন?`,
    message: `"${user.username}" ইউজারটি ${action} করা হবে।`,
    okText: action,
    tone: nextActive ? 'normal' : 'danger',
    icon: nextActive ? '✓' : '!',
  });
  if (!ok) return;
  const { data, error } = await sb.rpc('vn_admin_set_user_active', {
    p_session_token: sessionToken,
    p_user_id: user.id,
    p_is_active: nextActive,
  });
  if (error || !data || data.ok !== true) {
    showToast(error?.message || userRpcMessage(data?.error), 'error');
    return;
  }
  showToast(`ইউজার ${action} হয়েছে`, 'success');
  await loadAdminUsers();
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
  if (appMustChangePin) guidePinChange();
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
  if (sb && sessionToken) {
    try {
      await sb.rpc('vn_pin_logout', { p_session_token: sessionToken });
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
    sb = window.supabase.createClient(SB_URL, SB_KEY);
    const tok = localStorage.getItem('vn-app-session-token');
    if (tok) {
      sessionToken = tok;
      const { data, error } = await sb.rpc('vn_pin_resolve_session', { p_session_token: tok });
      if (error || !data || data.ok !== true) {
        clearAppSession();
        showAuthGate();
        setAuthSheetMessage('সেশন শেষ — আবার লগইন করুন', false);
        return;
      }
      appUserId = data.user_id;
      appUsername = data.username || '';
      appIsAdmin = !!data.is_admin;
      appMustChangePin = !!data.must_change_pin;
      try {
        localStorage.setItem('vn-app-user-id', appUserId || '');
        localStorage.setItem('vn-app-username', appUsername);
        localStorage.setItem('vn-app-is-admin', appIsAdmin ? '1' : '0');
        localStorage.setItem('vn-app-must-change-pin', appMustChangePin ? '1' : '0');
      } catch { /* */ }
      await enterAppAfterSession();
      return;
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
  if (!sb || !sessionToken || !appUserId) return;
  const { data, error } = await sb.rpc('vn_pull_notes_folders', {
    p_session_token: sessionToken,
  });
  if (error) throw new Error(error.message);
  if (!data || data.ok !== true) throw new Error(data?.error || 'pull_failed');

  const nd = Array.isArray(data.notes) ? data.notes : [];
  const fd = Array.isArray(data.folders) ? data.folders : [];

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

function sbUpsertNote(id) {
  if (!sb || !sessionToken || !appUserId || !notes[id]) return;
  sb.rpc('vn_upsert_note_session', {
    p_session_token: sessionToken,
    p_note: notePayloadForRpc(id),
  }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert note:', error.message);
  });
}

function sbDeleteNote(id) {
  if (!sb || !sessionToken) return;
  sb.rpc('vn_delete_note_session', {
    p_session_token: sessionToken,
    p_note_id: id,
  }).then(({ error }) => {
    if (error) console.warn('[Supabase] delete note:', error.message);
  });
}

function sbUpsertFolder(id) {
  if (!sb || !sessionToken || !appUserId || !folders[id]) return;
  const f = folders[id];
  sb.rpc('vn_upsert_folder_session', {
    p_session_token: sessionToken,
    p_folder: {
      id,
      name: f.name,
      created_at: f.created,
      label_color: f.labelColor || '',
    },
  }).then(({ error }) => {
    if (error) console.warn('[Supabase] upsert folder:', error.message);
  });
}

function sbDeleteFolder(id) {
  if (!sb || !sessionToken) return;
  sb.rpc('vn_delete_folder_session', {
    p_session_token: sessionToken,
    p_folder_id: id,
  }).then(({ error }) => {
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

function showNoteActionSheet(id) {
  if (!notes[id] || !notes[id].content?.trim()) return;
  activeNoteActionId   = id;
  noteActionTitle.textContent = getListTitle(notes[id]);
  if (noteActionPin) {
    noteActionPin.textContent = notes[id].pinned ? '📌 পিন খুলুন' : '📌 পিন করুন';
  }
  noteActionSheet.classList.remove('hidden');
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
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
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
  if (folderActionSheet) folderActionSheet.classList.add('hidden');
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
  const iconFill = NOTE_LABEL_BAR[folder.labelColor] || '#4a90d9';

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
    showFolderActionSheet(id);
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

on(folderActionLabel, 'click', () => {
  const id = activeFolderActionId;
  if (folderActionSheet) folderActionSheet.classList.add('hidden');
  if (id) openLabelModalForFolder(id);
});

on(folderActionDelete, 'click', async () => {
  if (folderActionSheet) folderActionSheet.classList.add('hidden');
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
on(noteActionPin, 'click', () => {
  const id = activeNoteActionId;
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  if (id) toggleNotePinned(id);
});
on(noteActionTags, 'click', () => {
  const id = activeNoteActionId;
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  if (id) openTagsModalForNote(id);
});
on(noteActionLabel, 'click', () => {
  const id = activeNoteActionId;
  if (noteActionSheet) noteActionSheet.classList.add('hidden');
  if (id) openLabelModalForNote(id);
});
on(noteActionCancel, 'click',  () => { if (noteActionSheet) noteActionSheet.classList.add('hidden'); });
on(noteActionSheet, 'click',  e => { if (e.target === noteActionSheet) noteActionSheet.classList.add('hidden'); });

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

    const res = await fetch(TRANSCRIBE_ENDPOINT, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        audio: base64,
        mimeType: effectiveMime,
      }),
    });

    if (!res.ok) {
      const e = await res.json().catch(() => ({}));
      throw new Error(e?.error?.message || `HTTP ${res.status}`);
    }

    const data = await res.json();
    const text = data?.text?.trim();

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
    if (appPrefs.transcribeAutoSave !== false) saveCurrentNote(true);
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

on(authSignOutBtn, 'click', async () => {
  await exitAppSession();
  showToast('সাইন আউট হয়েছে', 'info');
});

on(authSigninBtn, 'click', async () => {
  if (!sb) {
    setAuthSheetMessage('ক্লায়েন্ট লোড হয়নি — পেজ রিফ্রেশ করুন', true);
    return;
  }
  const username = (authUsername && authUsername.value ? authUsername.value : '').trim();
  const pin = authPassword && authPassword.value ? authPassword.value : '';
  if (!username) {
    setAuthSheetMessage('ইউজারনেম লিখুন', true);
    return;
  }
  if (!pin || pin.length < 4) {
    setAuthSheetMessage('পিন কমপক্ষে ৪ অক্ষর', true);
    return;
  }
  if (authSigninBtn) authSigninBtn.disabled = true;
  setAuthSheetMessage('লগইন…', false);
  const { data, error } = await sb.rpc('vn_pin_login', {
    p_username: username,
    p_pin: pin,
  });
  if (authSigninBtn) authSigninBtn.disabled = false;
  if (error) {
    setAuthSheetMessage(error.message, true);
    return;
  }
  if (!data || data.ok !== true) {
    setAuthSheetMessage(
      data?.error === 'invalid_credentials' ? 'ভুল ইউজারনেম বা পিন' : userRpcMessage(data?.error || 'লগইন ব্যর্থ'),
      true,
    );
    return;
  }
  persistAppSessionFromLogin(data);
  setAuthSheetMessage('', false);
  showToast('লগইন হয়েছে', 'success');
  await enterAppAfterSession();
});

on(adminCreateUserBtn, 'click', async () => {
  if (!sb || !sessionToken) {
    setAdminCreateMessage('সেশন নেই', true);
    return;
  }
  const nu = (adminNewUsername && adminNewUsername.value ? adminNewUsername.value : '').trim();
  const np = adminNewPin && adminNewPin.value ? adminNewPin.value : '';
  if (!nu) {
    setAdminCreateMessage('নতুন ইউজারনেম লিখুন', true);
    return;
  }
  if (!np || np.length < 4) {
    setAdminCreateMessage('পিন কমপক্ষে ৪ অক্ষর', true);
    return;
  }
  if (adminCreateUserBtn) adminCreateUserBtn.disabled = true;
  setAdminCreateMessage('তৈরি হচ্ছে…', false);
  const { data, error } = await sb.rpc('vn_admin_create_user', {
    p_session_token: sessionToken,
    p_new_username: nu,
    p_new_pin: np,
  });
  if (adminCreateUserBtn) adminCreateUserBtn.disabled = false;
  if (error) {
    setAdminCreateMessage(error.message, true);
    return;
  }
  if (!data || data.ok !== true) {
    const err = data?.error || 'failed';
    setAdminCreateMessage(
      err === 'duplicate_username' ? 'এই ইউজারনেম আগে থেকেই আছে' : err,
      true,
    );
    return;
  }
  if (adminNewUsername) adminNewUsername.value = '';
  if (adminNewPin) adminNewPin.value = '';
  setAdminCreateMessage('ইউজার তৈরি হয়েছে', false);
  showToast(`ইউজার "${data.username}" যোগ হয়েছে`, 'success');
  await loadAdminUsers();
});

on(accountChangePinBtn, 'click', async () => {
  if (!sb || !sessionToken) {
    setAccountPinMessage('সেশন নেই', true);
    return;
  }
  const currentPin = accountCurrentPin?.value || '';
  const newPin = accountNewPin?.value || '';
  const confirmPin = accountConfirmPin?.value || '';
  if (!currentPin) {
    setAccountPinMessage('বর্তমান পিন লিখুন', true);
    return;
  }
  if (!newPin || newPin.length < 4) {
    setAccountPinMessage('নতুন পিন কমপক্ষে ৪ অক্ষর', true);
    return;
  }
  if (newPin !== confirmPin) {
    setAccountPinMessage('নতুন পিন দুইবার একই নয়', true);
    return;
  }
  if (accountChangePinBtn) accountChangePinBtn.disabled = true;
  setAccountPinMessage('পিন পরিবর্তন হচ্ছে…', false);
  const { data, error } = await sb.rpc('vn_change_own_pin', {
    p_session_token: sessionToken,
    p_current_pin: currentPin,
    p_new_pin: newPin,
  });
  if (accountChangePinBtn) accountChangePinBtn.disabled = false;
  if (error || !data || data.ok !== true) {
    setAccountPinMessage(error?.message || userRpcMessage(data?.error), true);
    return;
  }
  if (accountCurrentPin) accountCurrentPin.value = '';
  if (accountNewPin) accountNewPin.value = '';
  if (accountConfirmPin) accountConfirmPin.value = '';
  appMustChangePin = false;
  try { localStorage.setItem('vn-app-must-change-pin', '0'); } catch { /* */ }
  updateCloudSyncSummary();
  setAccountPinMessage('পিন পরিবর্তন হয়েছে', false);
  showToast('পিন পরিবর্তন হয়েছে', 'success');
  if (appIsAdmin) loadAdminUsers();
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
  autoSaveTimer = setTimeout(() => saveCurrentNote(true), appPrefs.autoSaveDelay || 2000);
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
loadPrefs();
applyPrefs();
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
