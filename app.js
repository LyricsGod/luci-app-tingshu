/* ===========================================================
   听书 · 网页书架
   纯前端渲染 + 少量同源 CGI；音频既可以来自本机文件夹，
   也可以来自路由器书库（library.json / media/*）。

   组织方式：
     常量 → DOM 引用 → 运行状态 → IndexedDB → 工具函数 → 业务模块
     （书库扫描 / 渲染 / 播放 / 合集 / 上传 / 同步）→ 事件绑定 → 启动
   =========================================================== */

(() => {
  'use strict';

  /* ================= 常量 ================= */

  const APP_VERSION = '1.6.1';   // 打包脚本会从这里读取版本号，改这一处即可

  const AUDIO_EXT = new Set(['mp3', 'm4a', 'm4b', 'aac', 'ogg', 'oga', 'opus', 'wav', 'flac', 'weba', 'webm']);
  const IMG_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif', 'avif', 'bmp']);
  const SPEEDS = [0.5, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3];
  const SEEK_STEP = 30;          // 快进 / 快退步长（秒）
  const SAVE_EVERY = 4000;       // 播放中落盘间隔（毫秒）
  const PUSH_EVERY = 15000;      // 播放中向服务器推送进度的最短间隔（毫秒）
  const PROGRESS_FILE_VERSION = 1;

  const SORTS = ['recent', 'title', 'progress'];
  const SORT_LABELS = { recent: '最近收听', title: '书名', progress: '进度' };
  const THEMES = ['auto', 'dark', 'light'];
  const THEME_LABELS = { auto: '跟随系统', dark: '夜间模式', light: '日间模式' };

  const collator = new Intl.Collator('zh-Hans-CN', { numeric: true, sensitivity: 'base' });
  const isFiniteNumber = (n) => typeof n === 'number' && Number.isFinite(n);
  const ignore = () => {};

  const PALETTES = [
    ['#caa27c', '#8a6849'], ['#a8b6c6', '#5e6f81'], ['#c4b1a1', '#7d6a58'],
    ['#b7c1a6', '#5e6b51'], ['#c9afb8', '#7d5f6b'], ['#afbccb', '#596a7b'],
    ['#ccbd9d', '#8a7a55'], ['#c1aaa0', '#7a5a52'], ['#b3b8c9', '#5c6178'],
    ['#cbb49a', '#86694f'],
  ];

  /* ================= DOM ================= */

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  const el = {
    pageScroll: $('#pageScroll'),
    audio: $('#audio'),
    folderInput: $('#folderInput'),
    libraryView: $('#libraryView'),
    bookView: $('#bookView'),
    bookGrid: $('#bookGrid'),
    emptyState: $('#emptyState'),
    emptyTitle: $('#emptyState h2'),
    emptyDesc: $('#emptyState .empty-desc'),
    emptyPickBtn: $('#emptyPickBtn'),
    compatNote: $('#compatNote'),
    resumeHero: $('#resumeHero'),
    shelfCount: $('#shelfCount'),
    searchInput: $('#searchInput'),
    sortBtn: $('#sortBtn'),
    pickBtn: $('#pickBtn'),
    themeBtn: $('#themeBtn'),
    backBtn: $('#backBtn'),
    mini: $('#miniPlayer'),
    miniBar: $('#miniBar'),
    miniCover: $('#miniCover'),
    miniTitle: $('#miniTitle'),
    miniTrack: $('#miniTrack'),
    miniPlay: $('#miniPlay'),
    miniPrev: $('#miniPrev'),
    miniNext: $('#miniNext'),
    miniSpeed: $('#miniSpeed'),
    miniTimer: $('#miniTimer'),
    miniTimerLabel: $('#miniTimer span'),
    expandBtn: $('#expandBtn'),
    sheet: $('#playerSheet'),
    sheetBg: $('#sheetBg'),
    sheetCover: $('#sheetCover'),
    sheetTitle: $('#sheetTitle'),
    sheetTrack: $('#sheetTrack'),
    sheetBadge: $('#sheetTimerBadge'),
    sheetFoot: $('#sheetFoot'),
    collapseBtn: $('#collapseBtn'),
    seekBar: $('#seekBar'),
    curTime: $('#curTime'),
    durTime: $('#durTime'),
    playBtn: $('#playBtn'),
    prevBtn: $('#prevBtn'),
    nextBtn: $('#nextBtn'),
    back30: $('#back30'),
    fwd30: $('#fwd30'),
    speedBtn: $('#speedBtn'),
    speedVal: $('#speedVal'),
    timerBtn: $('#timerBtn'),
    timerVal: $('#timerVal'),
    chapterBtn: $('#chapterBtn'),
    chapterVal: $('#chapterVal'),
    volumeBar: $('#volumeBar'),
    popover: $('#popover'),
    toast: $('#toast'),
    bookCover: $('#bookCover'),
    bookTitle: $('#bookTitle'),
    bookMeta: $('#bookMeta'),
    bookBar: $('#bookBar'),
    bookPct: $('#bookPct'),
    bookPlayBtn: $('#bookPlayBtn'),
    bookPlayLabel: $('#bookPlayLabel'),
    bookRestartBtn: $('#bookRestartBtn'),
    bookDesc: $('#bookDesc'),
    editBookBtn: $('#editBookBtn'),
    trackList: $('#trackList'),
    // 编辑弹窗
    editSheet: $('#editSheet'),
    editCover: $('#editCover'),
    editTitle: $('#editTitle'),
    editDesc: $('#editDesc'),
    descCount: $('#descCount'),
    paletteRow: $('#paletteRow'),
    uploadCoverBtn: $('#uploadCoverBtn'),
    shuffleCoverBtn: $('#shuffleCoverBtn'),
    resetCoverBtn: $('#resetCoverBtn'),
    coverInput: $('#coverInput'),
    cropSheet: $('#cropSheet'),
    cropViewport: $('#cropViewport'),
    cropCanvas: $('#cropCanvas'),
    cropZoom: $('#cropZoom'),
    cropSave: $('#cropSave'),
    cropCancel: $('#cropCancel'),
    cropClose: $('#cropClose'),
    cropReset: $('#cropReset'),
    // 上传
    uploadSheet: $('#uploadSheet'),
    uploadClose: $('#uploadClose'),
    uploadDir: $('#uploadDir'),
    pickFolderBtn: $('#pickFolderBtn'),
    pickArchiveBtn: $('#pickArchiveBtn'),
    uploadDirInput: $('#uploadDirInput'),
    uploadFileInput: $('#uploadFileInput'),
    uploadProgress: $('#uploadProgress'),
    uploadBar: $('#uploadBar'),
    uploadStatus: $('#uploadStatus'),
    // 进度备份
    backupSheet: $('#backupSheet'),
    backupClose: $('#backupClose'),
    backupBtn: $('#backupBtn'),
    brand: $('.brand'),
    backupInfo: $('#backupInfo'),
    backupModes: $('#backupModes'),
    backupUserRow: $('#backupUserRow'),
    backupUser: $('#backupUser'),
    backupSize: $('#backupSize'),
    backupDownloadBtn: $('#backupDownloadBtn'),
    backupRestoreBtn: $('#backupRestoreBtn'),
    backupFile: $('#backupFile'),
    exportBtn: $('#exportBtn'),
    importBtn: $('#importBtn'),
    progressFileInput: $('#progressFileInput'),
    bookMarksBtn: $('#bookMarksBtn'),
    pausemarksSheet: $('#pausemarksSheet'),
    pausemarksClose: $('#pausemarksClose'),
    pausemarksList: $('#pausemarksList'),
    pmCount: $('#pmCount'),
    pmClearBtn: $('#pmClearBtn'),
    bookDeleteBtn: $('#bookDeleteBtn'),
    editSave: $('#editSave'),
    editCancel: $('#editCancel'),
    editClose: $('#editClose'),
    // 合集
    newCollectionBtn: $('#newCollectionBtn'),
    addToCollectionBtn: $('#addToCollectionBtn'),
    collectionSheet: $('#collectionSheet'),
    colName: $('#colName'),
    colMeta: $('#colMeta'),
    colGrid: $('#colGrid'),
    colEmpty: $('#colEmpty'),
    colSortBtn: $('#colSortBtn'),
    colSortFoot: $('#colSortFoot'),
    colSortDone: $('#colSortDone'),
    colManageBtn: $('#colManageBtn'),
    colCloseBtn: $('#colCloseBtn'),
    colEditSheet: $('#colEditSheet'),
    colEditTitle: $('#colEditTitle'),
    colEditName: $('#colEditName'),
    colPickList: $('#colPickList'),
    colPickCount: $('#colPickCount'),
    colEditSave: $('#colEditSave'),
    colEditCancel: $('#colEditCancel'),
    colEditClose: $('#colEditClose'),
    colDelete: $('#colDelete'),
    // 用户
    userBtn: $('#userBtn'),
    userLabel: $('#userLabel'),
    layoutBtn: $('#layoutBtn'),
    authSheet: $('#authSheet'),
    authTitle: $('#authTitle'),
    authHint: $('#authHint'),
    authForm: $('#authForm'),
    authUser: $('#authUser'),
    authPass: $('#authPass'),
    authError: $('#authError'),
    authSubmit: $('#authSubmit'),
    authFoot: $('#authFoot'),
    accountSheet: $('#accountSheet'),
    accountInfo: $('#accountInfo'),
    accountClose: $('#accountClose'),
    accountUsersBtn: $('#accountUsersBtn'),
    accountPasswordBtn: $('#accountPasswordBtn'),
    accountLogoutBtn: $('#accountLogoutBtn'),
    usersSheet: $('#usersSheet'),
    usersClose: $('#usersClose'),
    userList: $('#userList'),
    newUserForm: $('#newUserForm'),
    newUserName: $('#newUserName'),
    newUserPass: $('#newUserPass'),
    newUserRole: $('#newUserRole'),
    usersMsg: $('#usersMsg'),
  };

  /* ================= 状态 ================= */

  const state = {
    books: [],
    collections: [],
    current: null,
    bookView: null,
    progress: new Map(),
    durations: new Map(),
    settings: { rate: 1, volume: 1, theme: 'auto', sort: 'recent', sleepFinish: false },
    user: null,
    role: '',
    columns: 0,
    authShown: false,
    users: [],
    sleep: { mode: 'off', endsAt: 0, minutes: 0, pending: false, finishTrack: false },
    filter: '',
    source: 'local',      // local = 本机文件夹，net = 路由器/服务器书库
    serverRoot: '',
    serverError: '',
    pendingHandle: null,
    scanning: false,
    scrubbing: false,
    playedOnce: false,    // 本次会话是否真的播放过（用于判断是否推送进度）
  };

  // 播放器相关的临时状态（不进 state：这些值不需要被渲染逻辑读取）
  let objectUrl = null;
  let currentBlob = null;      // 保住当前章节的 blob 引用，防止安卓后台 GC 后 blob URL 失效
  let loadToken = 0;
  let prefetchCache = null;    // 预取的下一章 { bookId, index, file }，后台切章时避免 fetch 挂起
  let prefetchToken = 0;
  let switchingTrack = false;  // 正在切换章节（此时触发的 pause 不记录为暂停）
  let switchingTrackTimer = null;
  let timerPausing = false;    // 定时到点正在暂停（此时 pause 事件按 'timer' 记录，避免重复）
  let reloadingPlay = false;
  let lastSaveAt = 0;
  let sleepTimer = null;
  let fadeTimer = null;
  let durScanToken = 0;
  let toastTimer = null;
  let popoverAnchor = null;
  let bookIconDataUrl = null;  // Canvas 画的兜底封面，给 MediaSession 用
  let settingsSaveTimer = null;

  // 服务器能力开关：拉取成功后才允许回推，避免离线时刷一堆失败请求
  let syncEnabled = false;
  let colSyncEnabled = false;

  /* ================= IndexedDB ================= */

  let dbPromise = null;

  function openDB() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open('tingshu-db', 5);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
        if (!db.objectStoreNames.contains('progress')) db.createObjectStore('progress', { keyPath: 'bookId' });
        if (!db.objectStoreNames.contains('durations')) db.createObjectStore('durations', { keyPath: 'key' });
        if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'bookId' });
        if (!db.objectStoreNames.contains('collections')) db.createObjectStore('collections', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('pausemarks')) db.createObjectStore('pausemarks', { keyPath: 'id' });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  async function dbGet(store, key) {
    const db = await openDB();
    return new Promise((res, rej) => {
      const r = db.transaction(store, 'readonly').objectStore(store).get(key);
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  }

  async function dbPut(store, value, key) {
    const db = await openDB();
    return new Promise((res, rej) => {
      const s = db.transaction(store, 'readwrite').objectStore(store);
      const r = key === undefined ? s.put(value) : s.put(value, key);
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  }

  async function dbAll(store) {
    const db = await openDB();
    return new Promise((res, rej) => {
      const r = db.transaction(store, 'readonly').objectStore(store).getAll();
      r.onsuccess = () => res(r.result || []);
      r.onerror = () => rej(r.error);
    });
  }

  async function dbDelete(store, key) {
    const db = await openDB();
    return new Promise((res, rej) => {
      const r = db.transaction(store, 'readwrite').objectStore(store).delete(key);
      r.onsuccess = () => res();
      r.onerror = () => rej(r.error);
    });
  }

  async function dbClear(store) {
    const db = await openDB();
    return new Promise((res, rej) => {
      const r = db.transaction(store, 'readwrite').objectStore(store).clear();
      r.onsuccess = () => res();
      r.onerror = () => rej(r.error);
    });
  }

  /* ================= 用户 / 权限 ================= */

  const AUTH_API = 'cgi-ts/auth';
  const USERS_API = 'cgi-ts/users';
  const LAYOUT_API = 'cgi-ts/layout';
  const BACKUP_API = 'cgi-ts/backup';
  const nativeFetch = window.fetch.bind(window);

  /**
   * 给所有 CGI 请求补上同源 cookie，并在 401 时统一拉起登录层。
   * 音频、封面、library.json 这些静态资源不经过这里（否则会多一次判断）。
   */
  window.fetch = (input, init = {}) => {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    if (!url.includes('cgi-ts/')) return nativeFetch(input, init);
    return nativeFetch(input, { credentials: 'same-origin', ...init }).then(res => {
      if (res.status === 401) onAuthLost();
      return res;
    });
  };

  function isAdmin() {
    return state.role === 'admin';
  }

  /** 每行本数：0 = 自动，否则限制在 2~5 */
  function clampColumns(value) {
    const n = Math.round(Number(value) || 0);
    if (n <= 0) return 0;
    return Math.max(2, Math.min(5, n));
  }

  function applyLayout() {
    if (!el.bookGrid) return;
    state.columns = clampColumns(state.columns);
    if (state.columns > 0) {
      el.bookGrid.style.gridTemplateColumns = `repeat(${state.columns}, minmax(0, 1fr))`;
    } else {
      el.bookGrid.style.gridTemplateColumns = '';
    }
    if (el.layoutBtn) {
      el.layoutBtn.textContent = state.columns > 0 ? `每行 ${state.columns} 本` : '每行自动';
    }
  }

  async function loadUserLayout() {
    try {
      const res = await fetch(LAYOUT_API + '?t=' + Date.now(), { cache: 'no-store' });
      const data = await res.json();
      if (data && data.ok && data.layout) state.columns = clampColumns(data.layout.columns);
    } catch { /* 用自动列数 */ }
    applyLayout();
  }

  async function saveUserLayout() {
    applyLayout();
    try {
      await fetch(LAYOUT_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ columns: state.columns }),
      });
    } catch { /* 下次再试 */ }
  }

  function onAuthLost() {
    if (state.authShown) return;
    state.user = null;
    state.role = '';
    showAuth('login', '登录已过期，请重新登录');
  }

  async function clearUserCaches() {
    try { await dbClear('progress'); } catch { /* 缓存清不掉不影响使用 */ }
    try { await dbClear('collections'); } catch { /* 同上 */ }
    try { await dbClear('pausemarks'); } catch { /* 同上 */ }
    state.progress.clear();
    state.collections = [];
    syncEnabled = false;
    colSyncEnabled = false;
    // 换账号了，墓碑缓存跟着换（localStorage 里按用户名分开存）
    pmRemoved = new Map();
    pmRemovedLoaded = false;
  }

  /* ================= 小工具 ================= */

  const extOf = (name) => (name.split('.').pop() || '').toLowerCase();
  const isAudio = (name) => AUDIO_EXT.has(extOf(name));
  const isImage = (name) => IMG_EXT.has(extOf(name));

  /** 秒 → 0:00 / 0:00:00 */
  function fmtTime(sec) {
    const v = isFiniteNumber(sec) && sec > 0 ? sec : 0;
    const s = Math.floor(v % 60);
    const m = Math.floor((v / 60) % 60);
    const h = Math.floor(v / 3600);
    return (h > 0 ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(s).padStart(2, '0');
  }

  /** 秒 → 「40 分钟」「1.5 小时」 */
  function fmtRuntime(sec) {
    if (!isFiniteNumber(sec) || sec <= 0) return '';
    if (sec < 60) return `${Math.round(sec)} 秒`;
    return sec >= 3600 ? `${(sec / 3600).toFixed(1)} 小时` : `${Math.round(sec / 60)} 分钟`;
  }

  /** 字节 → 可读体积 */
  function fmtSize(n) {
    if (!n) return '0 B';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }

  /** 书名 hash → 稳定的封面渐变色 */
  function coverGradient(title) {
    let h = 7;
    for (const ch of String(title || '')) h = (h * 131 + ch.codePointAt(0)) >>> 0;
    const p = PALETTES[h % PALETTES.length];
    return `linear-gradient(148deg, ${p[0]} 0%, ${p[1]} 100%)`;
  }

  function cleanTrackTitle(name) {
    let s = name.replace(/\.[^.]+$/, '');
    // 去掉「01.」「001 -」「3）」这类章节序号，纯数字（如 1984）不处理
    s = s.replace(/^\s*\d{1,3}\s*[.\-_—、)）]\s*/, '');
    s = s.replace(/^\s*\d{1,3}\s+(?=\S)/, '');
    return s.trim() || name;
  }

  function rateLabel(r) {
    return (Number.isInteger(r) ? r.toFixed(1) : String(r)) + '×';
  }

  function toast(msg, ms = 2200) {
    el.toast.textContent = msg;
    el.toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.toast.hidden = true; }, ms);
  }

  /* 点击首页品牌，弹出构建信息与版本号 */
  function showVersionInfo() {
    const stamp = window.__BUILD_VER__;     // init.d 注入的构建时间（人可读）
    const bootId = window.__BOOT_STAMP__;   // 每次启动变化的时间戳，用于刷缓存
    let info = '听书 v' + APP_VERSION;
    if (stamp && stamp !== '@BUILD@') info += ' · 构建 ' + stamp;
    else if (bootId && bootId !== '@BOOT@') info += ' · 构建戳 ' + bootId;
    info += ' · 来源 ' + (state.source === 'net' ? '路由器' : '本机');
    toast(info, 4200);
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, m => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]
    ));
  }

  /* ---- 全屏层（弹窗 / 播放器面板）统一开关 ---- */

  const OVERLAYS = [
    'sheet', 'editSheet', 'cropSheet', 'collectionSheet', 'colEditSheet',
    'uploadSheet', 'backupSheet', 'pausemarksSheet', 'authSheet',
    'accountSheet', 'usersSheet',
  ];

  /** 任何一个全屏层打开时锁住页面滚动 */
  function syncBodyScroll() {
    const locked = OVERLAYS.some(key => el[key] && !el[key].hidden);
    // 滚动发生在 #pageScroll 上，锁它才有效；body 已经不再是滚动容器
    el.pageScroll.style.overflowY = locked ? 'hidden' : '';
  }

  function openOverlay(node) {
    node.hidden = false;
    syncBodyScroll();
  }

  function closeOverlay(node) {
    node.hidden = true;
    syncBodyScroll();
  }

  const durKey = (book, i) => book.id + '::' + book.tracks[i].path;

  async function* eachEntry(dir) {
    if (typeof dir.values === 'function') { for await (const v of dir.values()) yield v; }
    else if (typeof dir.entries === 'function') { for await (const [, v] of dir.entries()) yield v; }
  }

  /* ================= 扫描书库 ================= */

  async function walkFolder(dirHandle, prefix, acc) {
    for await (const entry of eachEntry(dirHandle)) {
      const path = prefix ? prefix + '/' + entry.name : entry.name;
      if (entry.kind === 'directory') {
        await walkFolder(entry, path, acc);
      } else if (isAudio(entry.name)) {
        acc.audio.push({ handle: entry, name: entry.name, path });
      } else if (isImage(entry.name)) {
        if (/^(cover|folder|front|poster|封面|专辑)/i.test(entry.name)) acc.coverPreferred = acc.coverPreferred || entry;
        else acc.coverAlt = acc.coverAlt || entry;
      }
    }
    return acc;
  }

  async function scanBookDir(dirHandle, title) {
    const acc = await walkFolder(dirHandle, '', { audio: [], coverPreferred: null, coverAlt: null });
    if (!acc.audio.length) return null;
    acc.audio.sort((a, b) => collator.compare(a.path, b.path));
    return makeBook(title, title, acc.audio, acc.coverPreferred || acc.coverAlt);
  }

  function makeBook(id, title, files, coverHandle) {
    return {
      id,
      title,              // 显示用书名（可被自定义书名覆盖）
      folderTitle: title, // 文件夹原名
      desc: '',
      palette: null,      // 自定义封面配色下标
      userCover: null,    // 用户上传的封面 Blob
      userCoverUrl: null,
      folderCoverUrl: null,
      coverHandle: coverHandle || null,
      tracks: files
        .slice()
        .sort((a, b) => collator.compare(a.path, b.path))
        .map(f => ({
          name: f.name,
          title: cleanTrackTitle(f.name),
          path: f.path,
          handle: f.handle,
        })),
    };
  }

  /* 自定义封面 > 指定配色 > 文件夹封面 */
  function coverUrlOf(book) {
    if (book.userCoverUrl) return book.userCoverUrl;
    if (book.palette != null) return null;
    return book.folderCoverUrl || null;
  }

  function coverStyleOf(book) {
    if (book.palette != null && PALETTES[book.palette]) {
      const p = PALETTES[book.palette];
      return `linear-gradient(148deg, ${p[0]} 0%, ${p[1]} 100%)`;
    }
    return coverGradient(book.folderTitle || book.title);
  }

  /** 扫描本机文件夹：每个子目录一本书，根目录散落的音频单独成一本 */
  async function scanLibrary(root) {
    const books = [];
    const loose = [];
    for await (const entry of eachEntry(root)) {
      if (entry.kind === 'directory') {
        const b = await scanBookDir(entry, entry.name);
        if (b) books.push(b);
      } else if (isAudio(entry.name)) {
        loose.push({ handle: entry, name: entry.name, path: entry.name });
      }
    }
    if (loose.length) {
      books.push(makeBook('__loose__', (root.name || '本机') + ' · 单章音频', loose, null));
    }
    books.sort((a, b) => collator.compare(a.title, b.title));
    return books;
  }

  async function loadCovers(books) {
    for (const book of books) {
      if (!book.coverHandle) continue;
      try {
        const file = await book.coverHandle.getFile();
        book.folderCoverUrl = URL.createObjectURL(file);
      } catch { /* 封面读取失败就画出占位封面 */ }
    }
  }

  /* 合并用户自定义的书名 / 简介 / 封面 */
  async function applyMeta(books) {
    let metas = [];
    try { metas = await dbAll('meta'); } catch { metas = []; }
    const map = new Map((metas || []).filter(m => m && m.bookId).map(m => [m.bookId, m]));

    for (const book of books) {
      const m = map.get(book.id);
      if (!m) continue;
      if (m.title) book.title = m.title;
      if (m.desc) book.desc = m.desc;
      if (m.palette != null) book.palette = m.palette;
      if (m.cover instanceof Blob) {
        if (book.userCoverUrl) URL.revokeObjectURL(book.userCoverUrl);
        book.userCover = m.cover;
        book.userCoverUrl = URL.createObjectURL(m.cover);
      }
    }
  }

  async function setLibrary(books) {
    state.books = books;
    await applyMeta(books);
    await loadCovers(books);
    renderLibrary();
  }

  /* ================= 服务器书库（OpenWrt / 局域网） ================= */

  const NET_INDEX = 'library.json';

  function mediaUrl(path) {
    return 'media/' + String(path || '')
      .split('/')
      .filter(seg => seg !== '')
      .map(encodeURIComponent)
      .join('/');
  }

  function bookFromNet(raw, idx) {
    const dirId = raw.id || raw.title || ('book' + idx);
    const tracks = (raw.tracks || []).map((t, i) => ({
      name: t.name || t.title || `第 ${i + 1} 章`,
      title: cleanTrackTitle(t.title || t.name || `第 ${i + 1} 章`),
      path: t.path,
      src: mediaUrl(t.path),
      handle: null,
    })).filter(t => t.path);

    // 服务端按目录顺序给的，这里按文件名做自然排序（01 < 02 < 10）
    tracks.sort((a, b) => collator.compare(a.path, b.path));
    if (!tracks.length) return null;

    return {
      id: 'net:' + dirId,
      title: raw.title || dirId,
      folderTitle: raw.title || dirId,
      desc: raw.desc || '',
      palette: null,
      userCover: null,
      userCoverUrl: null,
      folderCoverUrl: raw.cover ? mediaUrl(raw.cover) : null,
      coverHandle: null,
      tracks,
    };
  }

  /** 拉取路由器书库索引；连不上或格式不对返回 null，调用方据此保留本机模式 */
  async function loadServerLibrary() {
    let data;
    try {
      const res = await fetch(`${NET_INDEX}?t=${Date.now()}`, { cache: 'no-store' });
      if (!res.ok) return null;
      data = await res.json();
    } catch {
      return null;
    }
    if (!data || typeof data !== 'object') return null;

    state.source = 'net';
    state.serverRoot = data.root || '';
    state.serverError = data.error || '';
    return (data.books || []).map(bookFromNet).filter(Boolean);
  }

  /* ================= 打开文件夹 ================= */

  async function pickFolder() {
    if (state.scanning) return;
    if (typeof window.showDirectoryPicker !== 'function') {
      el.folderInput.click();
      return;
    }
    let handle;
    try {
      handle = await window.showDirectoryPicker({ id: 'tingshu-library', mode: 'read' });
    } catch (err) {
      if (err && err.name === 'AbortError') return;
      toast('无法打开文件夹：' + (err.message || err.name));
      return;
    }
    await loadFromHandle(handle);
  }

  async function loadFromHandle(handle) {
    if (state.scanning) return;
    try {
      let perm = await handle.queryPermission({ mode: 'read' });
      if (perm !== 'granted') perm = await handle.requestPermission({ mode: 'read' });
      if (perm !== 'granted') { toast('需要读写授权才能打开书库'); return; }

      state.scanning = true;
      const label = el.pickBtn.querySelector('span');
      const old = label.textContent;
      label.textContent = '读取中…';
      el.pickBtn.disabled = true;

      try {
        const books = await scanLibrary(handle);
        await dbPut('kv', handle, 'rootHandle');
        state.pendingHandle = null;
        state.source = 'local';
        await setLibrary(books);
        if (books.length) toast(`书架已更新：${books.length} 本`);
        else toast('这个文件夹里没找到音频，试试选择「上一级」文件夹');
      } finally {
        state.scanning = false;
        label.textContent = old === '读取中…' ? '打开书库' : old;
        el.pickBtn.disabled = false;
      }
    } catch (err) {
      console.error(err);
      toast('读取失败：' + (err.message || err.name));
    }
  }

  async function importFromInput(fileList) {
    const files = Array.from(fileList || []).filter(f => isAudio(f.name));
    if (!files.length) { toast('没有找到音频文件'); return; }

    const groups = new Map();
    for (const f of files) {
      const rel = f.webkitRelativePath || f.name;
      const parts = rel.split('/');
      const folder = parts.length > 1 ? parts.slice(0, -1).join('/') : '__loose__';
      if (!groups.has(folder)) groups.set(folder, []);
      groups.get(folder).push({ handle: { getFile: async () => f }, name: f.name, path: rel });
    }

    const books = [];
    for (const [folder, list] of groups) {
      const title = folder === '__loose__' ? '单章音频' : folder.split('/').pop();
      books.push(makeBook(folder, title, list, null));
    }
    books.sort((a, b) => collator.compare(a.title, b.title));
    state.source = 'local';
    await setLibrary(books);
    toast(`已载入 ${books.length} 本（刷新页面后需重新选择）`, 3800);
  }

  /* ================= 进度计算 ================= */

  /** 取某本书的进度记录（没有则返回 null） */
  function progressOf(bookId) {
    return state.progress.get(bookId) || null;
  }

  /**
   * 计算一本书的整体进度。
   * 已知所有章节时长时按「秒」算，否则退化成按「章节数」估算。
   */
  function bookStats(book) {
    const p = progressOf(book.id);
    const n = book.tracks.length;
    // 只认当前章节范围内的记录，防止换文件后旧进度串味
    const done = new Set(((p && p.done) || []).filter(i => i >= 0 && i < n));
    const idx = p ? Math.min(p.index || 0, n - 1) : 0;
    const time = p ? p.time || 0 : 0;

    let total = 0, listened = 0, known = n > 0;
    for (let i = 0; i < n; i++) {
      const d = state.durations.get(durKey(book, i));
      if (!d) { known = false; break; }
      total += d;
      if (done.has(i)) listened += d;
      else if (p && i === idx) listened += Math.min(time, d);
    }

    let frac;
    if (known && total > 0) frac = Math.min(1, listened / total);
    else {
      const partial = p && time > 1 && !done.has(idx) ? 0.35 : 0;
      frac = n ? Math.min(1, (done.size + partial) / n) : 0;
    }

    return {
      frac,
      finished: n > 0 && done.size >= n,
      doneCount: done.size,
      total, listened, known,
      remaining: known ? Math.max(0, total - listened) : 0,
      index: idx,
    };
  }

  /* ================= 渲染：书架 ================= */

  function paintCover(node, book, chars) {
    node.replaceChildren();
    node.style.background = coverStyleOf(book);
    const url = coverUrlOf(book);
    if (url) {
      const img = new Image();
      img.src = url;
      img.alt = '';
      node.appendChild(img);
      return;
    }
    const span = document.createElement('span');
    span.className = 'cv-fallback';
    span.textContent = (book.title || '书').slice(0, chars);
    node.appendChild(span);
  }

  function sortedBooks() {
    const list = state.books.slice();
    const mode = state.settings.sort;
    if (mode === 'recent') {
      list.sort((a, b) => {
        const pa = progressOf(a.id), pb = progressOf(b.id);
        if (!pa && !pb) return collator.compare(a.title, b.title);
        if (!pa) return 1;
        if (!pb) return -1;
        return (pb.updatedAt || 0) - (pa.updatedAt || 0);
      });
    } else if (mode === 'progress') {
      // 先算一次进度再排序，避免比较函数里反复遍历章节
      const frac = new Map(list.map(b => [b.id, bookStats(b).frac]));
      list.sort((a, b) => frac.get(b.id) - frac.get(a.id));
    } else {
      list.sort((a, b) => collator.compare(a.title, b.title));
    }
    return list;
  }

  function renderLibrary() {
    const q = state.filter.trim().toLowerCase();
    let books = sortedBooks();
    if (q) books = books.filter(b => b.title.toLowerCase().includes(q));
    const cols = state.collections.filter(c => matchesCollection(c, q));

    // 已经收进合集的书，书架上不再重复出现
    const collected = new Set();
    state.collections.forEach(c => (c.bookIds || []).forEach(id => collected.add(id)));
    const shelfBooks = books.filter(b => !collected.has(b.id));

    el.bookGrid.replaceChildren();
    const trulyEmpty = !state.books.length && !state.collections.length;
    el.libraryView.classList.toggle('is-empty', trulyEmpty);

    if (trulyEmpty) {
      el.shelfCount.textContent = '';
      el.emptyState.hidden = false;
      el.bookGrid.hidden = true;
      el.resumeHero.hidden = true;
      renderEmptyState();
      return;
    }

    el.shelfCount.textContent = state.collections.length
      ? `${cols.length} 合集${shelfBooks.length ? ` · ${shelfBooks.length} 本` : ''}`
      : `${shelfBooks.length} 本`;

    el.emptyState.hidden = true;
    el.bookGrid.hidden = false;
    renderHero();

    if (!shelfBooks.length && !cols.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.style.gridColumn = '1 / -1';
      p.style.padding = '20px 4px';
      p.textContent = `没有找到「${state.filter}」`;
      el.bookGrid.appendChild(p);
      return;
    }

    const frag = document.createDocumentFragment();
    cols.forEach(col => frag.appendChild(makeCollectionCard(col)));
    for (const book of shelfBooks) {
      const st = bookStats(book);
      const p = progressOf(book.id);

      const card = document.createElement('div');
      card.className = 'book-card';
      card.dataset.id = book.id;
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      card.setAttribute('aria-label', `打开《${book.title}》`);
      if (book.desc) card.title = book.desc;

      const cover = document.createElement('div');
      cover.className = 'book-cover';
      paintCover(cover, book, 6);

      const editBtn = document.createElement('button');
      editBtn.type = 'button';
      editBtn.className = 'card-edit';
      editBtn.title = '编辑封面和简介';
      editBtn.setAttribute('aria-label', `编辑《${book.title}》`);
      editBtn.dataset.edit = book.id;
      editBtn.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.4 4.6a1.9 1.9 0 0 1 2.7 2.7L8.6 17.8l-3.6.9.9-3.6Z"/></svg>';

      const title = document.createElement('div');
      title.className = 'card-title';
      title.textContent = book.title;

      const meta = document.createElement('div');
      meta.className = 'card-meta';
      if (st.finished) {
        meta.innerHTML = '<span class="card-done">已听完</span>';
      } else if (p && (st.frac > 0.004 || st.doneCount)) {
        meta.textContent = `第 ${st.index + 1} / ${book.tracks.length} 章`;
      } else {
        meta.textContent = `${book.tracks.length} 章`;
      }
      if (book.desc) {
        const dot = document.createElement('span');
        dot.className = 'card-dot';
        dot.textContent = '·';
        const has = document.createElement('span');
        has.className = 'card-hasdesc';
        has.textContent = '简介';
        meta.append(dot, has);
      }

      const bar = document.createElement('div');
      bar.className = 'card-bar';
      const fill = document.createElement('i');
      fill.style.width = (st.frac * 100).toFixed(1) + '%';
      bar.appendChild(fill);

      card.append(cover);
      if (isAdmin()) card.append(editBtn);
      card.append(title, meta, bar);
      frag.appendChild(card);
    }
    el.bookGrid.appendChild(frag);
  }

  function renderEmptyState() {
    // 已连上服务端书库，只是里面没内容 / 路径不对
    if (state.source === 'net') {
      if (state.serverError) {
        el.emptyTitle.textContent = '书库目录读不到';
        el.emptyDesc.textContent = state.serverError + '。到路由器的 LuCI 里确认「书架目录」填对了、U 盘也挂载好了，再点「重新扫描书库」。';
      } else {
        el.emptyTitle.textContent = '书库还是空的';
        el.emptyDesc.textContent = `服务已经连上${state.serverRoot ? `，但「${state.serverRoot}」里还没有有声书` : ''}。把 MP3 按书名分文件夹放进去，再到 LuCI 里点一下「重新扫描书库」。`;
      }
      el.emptyPickBtn.textContent = '上传书籍';
      el.emptyPickBtn.dataset.mode = 'upload';
      el.compatNote.hidden = true;
      const tree = $('.tree');
      if (tree) tree.hidden = true;
      return;
    }

    const tree = $('.tree');
    if (tree) tree.hidden = false;

    if (state.pendingHandle) {
      el.emptyTitle.textContent = `上次的书库「${state.pendingHandle.name}」`;
      el.emptyDesc.textContent = '浏览器重启后需要你重新确认一次读取权限。点下面按钮即可恢复，书和进度都还在。';
      el.emptyPickBtn.textContent = '恢复上次书库';
      el.emptyPickBtn.dataset.mode = 'restore';
    } else {
      el.emptyTitle.textContent = '还没有书，先选一个文件夹';
      el.emptyDesc.textContent = '把 MP3 放进以书名命名的文件夹，然后点这里选择总目录。文件不会上传，全部在本机播放。';
      el.emptyPickBtn.textContent = '选择书库文件夹';
      el.emptyPickBtn.dataset.mode = 'pick';
    }
    const unsupported = typeof window.showDirectoryPicker !== 'function';
    el.compatNote.hidden = !unsupported;
    if (unsupported) {
      el.compatNote.textContent = '当前浏览器不支持直接读取文件夹，已改用文件选择。若想「选一次就永久记住」，请用 Chrome / Edge 打开本页面。';
    }
  }

  function renderHero() {
    let latest = null;
    for (const book of state.books) {
      const p = progressOf(book.id);
      if (!p || (!p.time && !(p.done || []).length)) continue;
      if (!latest || (p.updatedAt || 0) > (latest.p.updatedAt || 0)) latest = { book, p };
    }
    if (!latest) { el.resumeHero.hidden = true; return; }

    const { book, p } = latest;
    const st = bookStats(book);
    const idx = Math.min(p.index || 0, book.tracks.length - 1);
    const track = book.tracks[idx];

    el.resumeHero.hidden = false;
    el.resumeHero.dataset.id = book.id;
    el.resumeHero.replaceChildren();

    const cover = document.createElement('div');
    cover.className = 'book-cover hero-cover';
    paintCover(cover, book, 2);

    const body = document.createElement('div');
    body.className = 'hero-body';
    const label = document.createElement('div');
    label.className = 'hero-label';
    label.textContent = st.finished ? '已听完' : '继续收听';
    const t = document.createElement('div');
    t.className = 'hero-title';
    t.textContent = book.title;
    const sub = document.createElement('div');
    sub.className = 'hero-sub';
    const dur = state.durations.get(durKey(book, idx));
    sub.textContent = st.finished
      ? `共 ${book.tracks.length} 章 · 已全部听完`
      : `第 ${idx + 1} 章 · ${track.title}` + (dur ? ` · ${fmtTime(p.time || 0)} / ${fmtTime(dur)}` : '');
    body.append(label, t, sub);

    const cta = document.createElement('div');
    cta.className = 'hero-cta';
    cta.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13l11-6.5z"/></svg>';
    cta.append(document.createTextNode(st.finished ? '重听' : '继续'));

    el.resumeHero.append(cover, body, cta);
  }

  /* ================= 渲染：书籍详情 ================= */

  function goBook(book) {
    state.bookView = book;
    el.libraryView.hidden = true;
    el.bookView.hidden = false;
    el.pageScroll.scrollTop = 0;
    renderBookView(book);
    scanDurations(book);
  }

  function goLibrary() {
    state.bookView = null;
    el.bookView.hidden = true;
    el.libraryView.hidden = false;
    el.pageScroll.scrollTop = 0;
    renderLibrary();
  }

  function renderBookView(book) {
    paintCover(el.bookCover, book, 6);
    el.bookTitle.textContent = book.title;
    el.bookTitle.title = book.title !== book.folderTitle ? `原文件夹：${book.folderTitle}` : '';

    const desc = (book.desc || '').trim();
    el.bookDesc.hidden = !desc;
    el.bookDesc.textContent = desc;
    el.bookDesc.classList.remove('open');
    el.bookDesc.title = desc ? '点击展开 / 收起' : '';

    const p = progressOf(book.id);
    const st = bookStats(book);
    const hasProgress = !!p && ((p.time || 0) > 1 || (p.done || []).length > 0);
    el.bookPlayLabel.textContent = st.finished ? '重新收听' : hasProgress ? '继续收听' : '开始收听';
    el.bookRestartBtn.hidden = !hasProgress;

    updateBookStats(book);
    renderTrackList(book);
    updateBookMeta(book);
  }

  function refreshBookView() {
    if (state.bookView) renderBookView(state.bookView);
  }

  function updateBookMeta(book) {
    const playing = state.current && state.current.book.id === book.id;
    const st = bookStats(book);
    el.bookMeta.textContent = playing
      ? `正在播放 · 第 ${state.current.index + 1} / ${book.tracks.length} 章`
      : st.known && st.total > 0
        ? `${book.tracks.length} 章 · 全长 ${fmtRuntime(st.total)}`
        : `共 ${book.tracks.length} 章`;
  }

  function updateBookStats(book) {
    const st = bookStats(book);
    el.bookBar.style.width = (st.frac * 100).toFixed(1) + '%';
    if (st.finished) {
      el.bookPct.textContent = `已听完 · 共 ${book.tracks.length} 章`;
    } else if (st.known && st.total > 0) {
      el.bookPct.textContent = `${Math.round(st.frac * 100)}% · 还剩 ${fmtRuntime(st.remaining)}`;
    } else if (st.doneCount || st.frac > 0) {
      el.bookPct.textContent = `${Math.round(st.frac * 100)}% · 第 ${st.index + 1} / ${book.tracks.length} 章`;
    } else {
      el.bookPct.textContent = `0% · ${book.tracks.length} 章`;
    }
  }

  function renderTrackList(book) {
    const p = progressOf(book.id);
    const done = new Set((p && p.done) || []);
    const freq = document.createDocumentFragment();
    const isCurrentBook = state.current && state.current.book.id === book.id;

    book.tracks.forEach((track, i) => {
      const li = document.createElement('li');
      li.className = 'track-item';
      li.dataset.i = String(i);
      li.tabIndex = 0;
      const playing = isCurrentBook && state.current.index === i;
      if (playing) li.classList.add('playing');
      else if (done.has(i)) li.classList.add('done');

      const num = document.createElement('span');
      num.className = 't-idx';
      num.textContent = playing ? '♪' : String(i + 1);

      const body = document.createElement('span');
      body.className = 't-body';
      const nm = document.createElement('span');
      nm.className = 't-name';
      nm.textContent = track.title;
      body.appendChild(nm);

      const time = document.createElement('span');
      time.className = 't-time';
      const d = state.durations.get(durKey(book, i));
      time.textContent = d ? fmtTime(d) : '';

      const mark = document.createElement('span');
      mark.className = 't-state';
      if (playing) mark.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 8.5v7M12 6v12M15 9.5v5"/></svg>';
      else if (done.has(i)) mark.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7"/></svg>';

      li.append(num, body, time, mark);
      freq.appendChild(li);
    });

    el.trackList.replaceChildren();
    el.trackList.appendChild(freq);
  }

  /* ================= 编辑书籍（封面 / 简介） ================= */

  const editState = { book: null, palette: null, coverBlob: null, tempUrl: null, title: '', desc: '' };

  function openEditor(book) {
    if (!isAdmin()) return;
    editState.book = book;
    editState.palette = book.palette != null ? book.palette : null;
    editState.coverBlob = book.userCover || null;
    if (editState.tempUrl) { URL.revokeObjectURL(editState.tempUrl); editState.tempUrl = null; }

    el.editTitle.value = book.title && book.title !== book.folderTitle ? book.title : '';
    el.editTitle.placeholder = `留空则用文件夹名「${book.folderTitle}」`;
    el.editDesc.value = book.desc || '';
    editState.title = el.editTitle.value;
    editState.desc = el.editDesc.value;
    updateDescCount();
    renderPaletteRow();
    renderEditCover();

    // 只有服务器书库的书才能删（本机文件不归我们管）
    el.bookDeleteBtn.hidden = !(state.source === 'net' && String(book.id).indexOf('net:') === 0);

    openOverlay(el.editSheet);
    setTimeout(() => { try { el.editTitle.focus({ preventScroll: true }); } catch { el.editTitle.focus(); } }, 80);
  }

  function closeEditor() {
    closeOverlay(el.editSheet);
    if (editState.tempUrl) { URL.revokeObjectURL(editState.tempUrl); editState.tempUrl = null; }
    editState.book = null;
  }

  function renderEditCover() {
    const b = editState.book;
    if (!b) return;
    paintCover(el.editCover, {
      title: el.editTitle.value.trim() || b.folderTitle,
      folderTitle: b.folderTitle,
      palette: editState.palette,
      userCoverUrl: editState.tempUrl || (editState.coverBlob ? b.userCoverUrl : null),
      folderCoverUrl: b.folderCoverUrl,
    }, 4);
  }

  function renderPaletteRow() {
    el.paletteRow.replaceChildren();

    const auto = document.createElement('button');
    auto.type = 'button';
    auto.className = 'swatch auto' + (editState.palette == null ? ' on' : '');
    auto.dataset.palette = '';
    auto.textContent = '自动';
    el.paletteRow.appendChild(auto);

    PALETTES.forEach((p, i) => {
      const s = document.createElement('button');
      s.type = 'button';
      s.className = 'swatch' + (editState.palette === i ? ' on' : '');
      s.style.background = `linear-gradient(148deg, ${p[0]}, ${p[1]})`;
      s.dataset.palette = String(i);
      s.title = `配色 ${i + 1}`;
      el.paletteRow.appendChild(s);
    });
  }

  /* 放弃图片封面，改用纯色 / 渐变 */
  function dropEditCover() {
    if (editState.tempUrl) { URL.revokeObjectURL(editState.tempUrl); editState.tempUrl = null; }
    editState.coverBlob = null;
  }

  /* ---- 封面裁切：可拖动、可缩放，方框内画面输出 720×720 ---- */

  const cropState = {
    img: null, url: null, zoom: 1, ox: 0, oy: 0,
    w: 0, h: 0, dpr: 1, pointers: new Map(), pinch: null,
  };

  function openCropper(file) {
    closeCropper();
    const url = URL.createObjectURL(file);
    const img = new Image();
    cropState.url = url;
    img.onload = () => {
      cropState.img = img;
      cropState.zoom = 1;
      cropState.ox = 0;
      cropState.oy = 0;
      openOverlay(el.cropSheet);
      requestAnimationFrame(() => {
        initCropCanvas();
        resetCrop();
      });
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      cropState.url = null;
      toast('图片读取失败，请换一张试试', 2600);
    };
    img.src = url;
  }

  function initCropCanvas() {
    const vp = el.cropViewport;
    const c = el.cropCanvas;
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const w = Math.max(1, vp.clientWidth);
    const h = Math.max(1, vp.clientHeight);
    cropState.w = w;
    cropState.h = h;
    cropState.dpr = dpr;
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
    clampCrop();
  }

  function cropScale() {
    const st = cropState;
    if (!st.img) return 1;
    return Math.max(st.w / st.img.naturalWidth, st.h / st.img.naturalHeight) * st.zoom;
  }

  function clampCrop() {
    const st = cropState;
    if (!st.img) return;
    const s = cropScale();
    const maxX = Math.max(0, (st.img.naturalWidth * s - st.w) / 2);
    const maxY = Math.max(0, (st.img.naturalHeight * s - st.h) / 2);
    st.ox = Math.max(-maxX, Math.min(maxX, st.ox));
    st.oy = Math.max(-maxY, Math.min(maxY, st.oy));
  }

  function drawCrop() {
    const st = cropState;
    const c = el.cropCanvas;
    if (!st.img || !st.w) return;
    const ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    const s = cropScale();
    ctx.save();
    ctx.scale(st.dpr, st.dpr);
    ctx.translate(st.w / 2 + st.ox, st.h / 2 + st.oy);
    ctx.scale(s, s);
    ctx.drawImage(st.img, -st.img.naturalWidth / 2, -st.img.naturalHeight / 2);
    ctx.restore();
  }

  function setCropZoom(z) {
    cropState.zoom = Math.max(1, Math.min(4, Number(z) || 1));
    clampCrop();
    el.cropZoom.value = String(cropState.zoom);
    drawCrop();
  }

  function resetCrop() {
    cropState.zoom = 1;
    cropState.ox = 0;
    cropState.oy = 0;
    if (el.cropZoom) el.cropZoom.value = '1';
    drawCrop();
  }

  function closeCropper() {
    closeOverlay(el.cropSheet);
    cropState.img = null;
    cropState.pointers.clear();
    cropState.pinch = null;
    if (cropState.url) {
      URL.revokeObjectURL(cropState.url);
      cropState.url = null;
    }
  }

  function applyCrop() {
    const st = cropState;
    if (!st.img || !st.w) return;
    const out = document.createElement('canvas');
    out.width = 720;
    out.height = 720;
    const ctx = out.getContext('2d');
    const scale = 720 / st.w;
    const s = cropScale();
    ctx.scale(scale, scale);
    ctx.translate(st.w / 2 + st.ox, st.h / 2 + st.oy);
    ctx.scale(s, s);
    ctx.drawImage(st.img, -st.img.naturalWidth / 2, -st.img.naturalHeight / 2);

    out.toBlob((blob) => {
      if (!blob) { toast('裁切失败，请重试', 2600); return; }
      if (editState.tempUrl) URL.revokeObjectURL(editState.tempUrl);
      editState.coverBlob = blob;
      editState.tempUrl = URL.createObjectURL(blob);
      renderEditCover();
      closeCropper();
      toast('封面已裁好，点保存生效', 1800);
    }, 'image/jpeg', 0.86);
  }

  function cropPoint(e) {
    const r = el.cropViewport.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  function updateDescCount() {
    el.descCount.textContent = `${el.editDesc.value.length} / 600`;
  }

  async function saveEditor() {
    const b = editState.book;
    if (!b) return;

    // 保存时直接从 DOM 读最新值（手机输入法即使 input 事件延迟，value 也已同步更新）
    const typedTitle = el.editTitle.value.trim();
    const desc = el.editDesc.value.trim();
    editState.title = el.editTitle.value;
    editState.desc = el.editDesc.value;
    const rec = {
      bookId: b.id,
      title: typedTitle && typedTitle !== b.folderTitle ? typedTitle : '',
      desc,
      palette: editState.palette,
      cover: editState.coverBlob || null,
      updatedAt: Date.now(),
    };

    b.title = rec.title || b.folderTitle;
    b.desc = desc;
    b.palette = editState.palette;

    if (editState.tempUrl) {
      // 新上传的图：把 blob 与 objectURL 交给书对象持有
      if (b.userCoverUrl) URL.revokeObjectURL(b.userCoverUrl);
      b.userCover = editState.coverBlob;
      b.userCoverUrl = editState.tempUrl;
      editState.tempUrl = null;
    } else if (!editState.coverBlob) {
      if (b.userCoverUrl) URL.revokeObjectURL(b.userCoverUrl);
      b.userCover = null;
      b.userCoverUrl = null;
    }

    try {
      const empty = !rec.title && !rec.desc && rec.palette == null && !rec.cover;
      if (empty) await dbDelete('meta', b.id);
      else await dbPut('meta', rec);
    } catch (err) {
      console.error(err);
      toast('保存失败：' + (err.message || err.name));
      return;
    }

    // 同步到服务器（书名/简介/配色/封面，多端共享）
    const pushed = await pushServerMeta(b);
    closeEditor();
    if (state.bookView && state.bookView.id === b.id) renderBookView(b);
    renderLibrary();
    if (state.current && state.current.book.id === b.id) syncNowPlaying();
    toast('已保存：简介「' + (desc || '(空)') + '」' + (pushed ? ' ✓已同步' : ' ✗未同步'), 2600);
  }

  /* ================= 合集 ================= */

  const colState = { id: null, sorting: false, dragEl: null };
  const colEditState = { id: null };

  function booksOfCollection(col) {
    return (col.bookIds || [])
      .map(id => state.books.find(b => b.id === id))
      .filter(Boolean);
  }

  function collectionOf(bookId) {
    return state.collections.filter(c => (c.bookIds || []).includes(bookId));
  }

  function matchesCollection(col, q) {
    if (!q) return true;
    if ((col.name || '').toLowerCase().includes(q)) return true;
    return booksOfCollection(col).some(b => b.title.toLowerCase().includes(q));
  }

  function paintMosaic(node, col, list) {
    node.replaceChildren();
    node.classList.add('mosaic');

    // 九宫格：最多显示 9 本；不足 9 本用空位补齐，超过 9 本在最后一格叠 +N
    const ids = (col.bookIds || []).slice(0, 9);
    for (let i = 0; i < 9; i++) {
      const cell = document.createElement('span');
      cell.className = 'mosaic-cell';

      const book = state.books.find(b => b.id === ids[i]);
      if (book) {
        const url = coverUrlOf(book);
        if (url) {
          const img = new Image();
          img.src = url;
          img.alt = '';
          cell.appendChild(img);
        } else {
          cell.style.background = coverStyleOf(book);
          const t = document.createElement('i');
          t.textContent = (book.title || '书').slice(0, 1);
          cell.appendChild(t);
        }
      } else {
        cell.classList.add('blank');
      }

      if (i === 8 && list.length > 9) {
        const more = document.createElement('span');
        more.className = 'mosaic-more';
        more.textContent = '+' + (list.length - 8);
        cell.appendChild(more);
      }
      node.appendChild(cell);
    }
  }

  function makeCollectionCard(col) {
    const list = booksOfCollection(col);

    const card = document.createElement('div');
    card.className = 'book-card collection-card';
    card.dataset.collection = col.id;
    card.tabIndex = 0;
    card.setAttribute('role', 'button');
    card.setAttribute('aria-label', `打开合集《${col.name}》`);

    const cover = document.createElement('div');
    cover.className = 'book-cover mosaic';
    paintMosaic(cover, col, list);

    const title = document.createElement('div');
    title.className = 'card-title';
    title.textContent = col.name;

    const meta = document.createElement('div');
    meta.className = 'card-meta';
    const doneCount = list.filter(b => bookStats(b).finished).length;
    meta.textContent = list.length ? `${list.length} 本` : '空合集';
    if (doneCount) {
      const dot = document.createElement('span');
      dot.className = 'card-dot';
      dot.textContent = '·';
      const d = document.createElement('span');
      d.className = 'card-done';
      d.textContent = `听完了 ${doneCount}`;
      meta.append(dot, d);
    }

    const bar = document.createElement('div');
    bar.className = 'card-bar';
    const fill = document.createElement('i');
    const avg = list.length ? list.reduce((s, b) => s + bookStats(b).frac, 0) / list.length : 0;
    fill.style.width = (avg * 100).toFixed(1) + '%';
    bar.appendChild(fill);

    card.append(cover, title, meta, bar);
    return card;
  }

  /* ---- 打开合集 ---- */

  function openCollection(col) {
    colState.id = col.id;
    colState.sorting = false;
    colState.dragEl = null;
    renderCollectionView();
    openOverlay(el.collectionSheet);
  }

  function closeCollection() {
    colState.sorting = false;
    colState.dragEl = null;
    closeOverlay(el.collectionSheet);
    // 合集面板盖在书架上：关掉后书架要跟着刷新（进度、合集数量都可能变了）
    if (!el.libraryView.hidden) renderLibrary();
  }

  function currentCollection() {
    return state.collections.find(c => c.id === colState.id) || null;
  }

  function renderCollectionView() {
    const col = currentCollection();
    if (!col) { closeCollection(); return; }
    const list = booksOfCollection(col);

    const doneCount = list.filter(b => bookStats(b).finished).length;
    el.colName.textContent = col.name;
    el.colMeta.textContent = list.length
      ? `${list.length} 本${doneCount ? ` · 已听完 ${doneCount}` : ''}${colState.sorting ? ' · 拖动卡片排序' : ''}`
      : '还没有书';
    el.colEmpty.hidden = list.length > 0;
    el.colSortFoot.hidden = !colState.sorting || list.length < 2;
    el.colSortBtn.textContent = colState.sorting ? '完成' : '调整顺序';
    el.colSortBtn.classList.toggle('on', colState.sorting);

    el.colGrid.replaceChildren();
    list.forEach((book, i) => el.colGrid.appendChild(makeColTile(book, i, list.length)));
  }

  function makeColTile(book, i, total) {
    const tile = document.createElement('div');
    tile.className = 'col-tile' + (colState.sorting ? ' sorting' : '');
    tile.dataset.book = book.id;
    tile.tabIndex = 0;
    tile.setAttribute('role', 'button');
    tile.setAttribute('aria-label', book.title);

    const st = bookStats(book);
    const p = progressOf(book.id);
    const playing = state.current && state.current.book.id === book.id;

    const cover = document.createElement('div');
    cover.className = 'book-cover col-cover';
    paintCover(cover, book, 2);

    if (playing) {
      const badge = document.createElement('span');
      badge.className = 'col-playing';
      badge.title = '正在播放';
      badge.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 8.5v7M12 6v12M15 9.5v5"/></svg>';
      cover.appendChild(badge);
    }

    const title = document.createElement('div');
    title.className = 'col-title';
    title.textContent = book.title;

    const prog = document.createElement('div');
    prog.className = 'col-prog';
    const fill = document.createElement('i');
    fill.style.width = (st.frac * 100).toFixed(1) + '%';
    prog.appendChild(fill);

    const meta = document.createElement('div');
    meta.className = 'col-meta';
    if (st.finished) {
      meta.textContent = '已听完';
      meta.classList.add('done');
    } else if (p && (st.frac > 0.004 || st.doneCount)) {
      meta.textContent = `第 ${st.index + 1}/${book.tracks.length} 章`;
    } else {
      meta.textContent = '未开始';
    }
    if (playing) {
      meta.textContent = '正在播放';
      meta.classList.add('playing');
    }

    tile.append(cover, title, prog, meta);

    if (colState.sorting) {
      tile.draggable = true;
      const arrows = document.createElement('div');
      arrows.className = 'col-arrows';
      arrows.innerHTML =
        `<button type="button" data-move="-1" ${i === 0 ? 'disabled' : ''} title="前移"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg></button>` +
        `<button type="button" data-move="1" ${i === total - 1 ? 'disabled' : ''} title="后移"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg></button>`;
      tile.appendChild(arrows);
    }
    return tile;
  }

  /* 调整顺序：可见书按新顺序排，已不在书库里的 id 留到末尾 */
  function applyOrder(col, orderedIds) {
    const set = new Set(orderedIds);
    const stale = (col.bookIds || []).filter(id => !set.has(id));
    col.bookIds = [...orderedIds, ...stale];
    return dbPut('collections', col).catch(() => {});
  }

  function moveColTile(index, dir) {
    const col = currentCollection();
    if (!col) return;
    const ids = booksOfCollection(col).map(b => b.id);
    const j = index + dir;
    if (j < 0 || j >= ids.length) return;
    [ids[index], ids[j]] = [ids[j], ids[index]];
    applyOrder(col, ids).then(renderCollectionView);
  }

  function persistDomOrder() {
    const col = currentCollection();
    if (!col) return;
    const ids = [...el.colGrid.children].map(t => t.dataset.book).filter(Boolean);
    applyOrder(col, ids).then(renderCollectionView);
  }

  /* ---- 新建 / 管理合集 ---- */

  function openCollectionEditor(col, presetBookIds) {
    colEditState.id = col ? col.id : null;
    const checked = new Set(col ? (col.bookIds || []) : (presetBookIds || []));

    el.colEditTitle.textContent = col ? '管理合集' : '新建合集';
    el.colEditName.value = col ? col.name : '';
    el.colDelete.hidden = !col;
    renderBookPicker(checked);

    openOverlay(el.colEditSheet);
    setTimeout(() => {
      try { el.colEditName.focus({ preventScroll: true }); } catch { el.colEditName.focus(); }
    }, 80);
  }

  function closeCollectionEditor() {
    closeOverlay(el.colEditSheet);
  }

  function renderBookPicker(checked) {
    el.colPickList.replaceChildren();

    // 新建合集时：已经在其它合集里的书不再显示（一本书只能属于一个合集）
    // 编辑合集时：显示全部书，但已在其它合集的也置灰不可勾，避免重复加入
    const isNew = !colEditState.id;
    const books = sortedBooks().filter(book => {
      if (checked.has(book.id)) return true;            // 本合集已选的书保留
      if (isNew) {
        // 新建：已在任何合集里的书直接排除
        return !state.collections.some(c => (c.bookIds || []).includes(book.id));
      }
      return true;
    });

    if (!books.length) {
      const p = document.createElement('p');
      p.className = 'muted pick-none';
      p.textContent = isNew ? '书架上的书都已经收进合集了' : '书架还是空的，先打开书库吧';
      el.colPickList.appendChild(p);
      updatePickCount();
      return;
    }

    books.forEach(book => {
      const row = document.createElement('label');
      row.className = 'pick-item';

      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.value = book.id;
      cb.checked = checked.has(book.id);

      // 编辑合集时：已在其它合集的书显示但置灰，防止重复加入
      const inOther = !checked.has(book.id) &&
        state.collections.some(c => c.id !== colEditState.id && (c.bookIds || []).includes(book.id));
      if (inOther) {
        cb.disabled = true;
        row.classList.add('is-disabled');
        row.title = '已在另一个合集里';
      }

      const cover = document.createElement('span');
      cover.className = 'book-cover pick-cover';
      paintCover(cover, book, 1);

      const name = document.createElement('span');
      name.className = 'pick-title';
      name.textContent = book.title;

      row.append(cb, cover, name);
      el.colPickList.appendChild(row);
    });
    updatePickCount();
  }

  function pickedBookIds() {
    return [...el.colPickList.querySelectorAll('input[type="checkbox"]:checked')].map(i => i.value);
  }

  function updatePickCount() {
    const n = pickedBookIds().length;
    el.colPickCount.textContent = n ? `已选 ${n} 本` : '';
  }

  async function saveCollectionEditor() {
    const name = el.colEditName.value.trim() || '未命名合集';
    const picked = pickedBookIds();
    if (!picked.length) { toast('至少挑一本书'); return; }

    const existing = colEditState.id ? state.collections.find(c => c.id === colEditState.id) : null;
    const prevOrder = existing ? (existing.bookIds || []).filter(id => picked.includes(id)) : [];
    const added = picked.filter(id => !prevOrder.includes(id));
    const bookIds = [...prevOrder, ...added];

    const col = existing || {
      id: 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5),
      name,
      bookIds: [],
      createdAt: Date.now(),
    };
    col.name = name;
    col.bookIds = bookIds;
    touchCollection(col);

    try {
      await dbPut('collections', col);
    } catch (err) {
      console.error(err);
      toast('保存失败：' + (err.message || err.name));
      return;
    }

    if (!existing) state.collections.push(col);
    pushServerCollections();
    closeCollectionEditor();
    renderLibrary();
    if (!el.collectionSheet.hidden && colState.id === col.id) renderCollectionView();
    toast(existing ? '合集已更新' : `合集「${col.name}」已创建`, 1600);
  }

  async function deleteCurrentCollection() {
    const col = colEditState.id ? state.collections.find(c => c.id === colEditState.id) : null;
    if (!col) return;
    if (!window.confirm(`删除合集「${col.name}」？\n里面的书不会被删掉。`)) return;

    try { await dbDelete('collections', col.id); } catch (err) { console.error(err); }
    state.collections = state.collections.filter(c => c.id !== col.id);
    pushServerCollections();
    closeCollectionEditor();
    if (colState.id === col.id) {
      closeOverlay(el.collectionSheet);
    }
    renderLibrary();
    toast('合集已删除', 1400);
  }

  /* ---- 从书详情加入合集（浮层） ---- */

  function openAddToCollection(book, anchor) {
    const rows = state.collections.map(c => {
      const has = (c.bookIds || []).includes(book.id);
      return `<button type="button" class="pop-row ${has ? 'on' : ''}" data-col="${esc(c.id)}">
        <span>${esc(c.name)}</span>
        <span class="pr-right">${has ? '✓ 已加入' : booksOfCollection(c).length + ' 本'}</span>
      </button>`;
    }).join('');

    const html = `<h4>加入合集</h4>${rows}
      <button type="button" class="pop-row" data-col="__new__">
        <span>＋ 新建合集</span><span class="pr-right">含这本</span>
      </button>`;

    showPopover(anchor, html, 200);

    $$('[data-col]', el.popover).forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = btn.dataset.col;
        if (id === '__new__') {
          hidePopover();
          openCollectionEditor(null, [book.id]);
          return;
        }
        const added = await toggleBookInCollection(id, book.id);
        const col = state.collections.find(c => c.id === id);
        if (!col) return;   // 合集可能在别处被删掉了
        btn.classList.toggle('on', added);
        const right = btn.querySelector('.pr-right');
        if (right) right.textContent = added ? '✓ 已加入' : booksOfCollection(col).length + ' 本';
        toast(added ? `已移入「${col.name}」，书架不再重复显示` : `已移出「${col.name}」，回到书架`, 1800);
      });
    });
  }

  async function toggleBookInCollection(colId, bookId) {
    const col = state.collections.find(c => c.id === colId);
    if (!col) return false;
    if (!Array.isArray(col.bookIds)) col.bookIds = [];
    const i = col.bookIds.indexOf(bookId);
    if (i >= 0) col.bookIds.splice(i, 1);
    else col.bookIds.push(bookId);
    touchCollection(col);
    await dbPut('collections', col).catch(() => {});
    pushServerCollections();
    renderLibrary();
    return i < 0;
  }

  /* ================= 播放 ================= */

  function setMiniVisible(v) {
    el.mini.hidden = !v;
    document.body.classList.toggle('mini-hidden', !v);
  }

  /**
   * 播放指定章节。
   * @param {object} book
   * @param {number} index 章节下标
   * @param {{autoplay?: boolean, time?: number}} [opts] 起播位置（秒）
   */
  async function playFrom(book, index, opts = {}) {
    const { autoplay = true, time = 0 } = opts;
    if (!book || !book.tracks[index]) return;

    const token = ++loadToken;
    const track = book.tracks[index];

    // 本地文件夹用 File 句柄；服务器书库的音频会被 fetch 成 Blob。
    // 为什么 fetch 成 Blob：uhttpd 不支持 HTTP Range，直接给 URL 的话
    // 拖动进度条会跳回开头。blob URL 在本机内存里 seek，天然正常。
    let file = null;

    // 命中预取缓存（后台切章时 fetch 可能被挂起，优先用已预取的 blob）
    if (prefetchCache && prefetchCache.bookId === book.id && prefetchCache.index === index && prefetchCache.file) {
      file = prefetchCache.file;
      prefetchCache = null;
    } else if (track.handle) {
      try {
        file = await track.handle.getFile();
      } catch (err) {
        console.error(err);
        toast('读取音频失败，请重新选择书库文件夹');
        return;
      }
      if (token !== loadToken) return;
    } else if (track.src) {
      // 网络书库：先整章拉下来转成 Blob
      try {
        const res = await fetch(track.src);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        file = await res.blob();
      } catch (err) {
        console.error(err);
        toast('这一章加载失败');
        return;
      }
      if (token !== loadToken) return;
    } else {
      toast('这一章没有可用的音频地址');
      return;
    }

    state.current = { book, index };
    setMiniVisible(true);

    // 切章标志：接下来因 load 触发的 pause 不应记录为「暂停」
    switchingTrack = true;
    clearTimeout(switchingTrackTimer);
    switchingTrackTimer = setTimeout(() => { switchingTrack = false; }, 600);

    // 立即把这本书标记为「最近收听」，书架排序和继续收听卡片随之更新
    const prog = ensureProgress(book.id);
    prog.index = index;
    prog.updatedAt = Date.now();
    dbPut('progress', prog).catch(() => {});

    if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; }
    if (file) {
      try {
        objectUrl = URL.createObjectURL(file);
        currentBlob = file;                     // 保住引用，防止后台 GC
      } catch (err) {
        // 极少见（内存不足 / blob 被回收）：别把整个页面拖进未捕获异常
        console.error(err);
        objectUrl = null;
        currentBlob = null;
        toast('这一章暂时无法播放，请稍后再试');
        return;
      }
    } else {
      currentBlob = null;
    }

    const a = el.audio;
    a.src = objectUrl;
    a.load();
    a.playbackRate = state.settings.rate;
    a.volume = state.settings.volume;

    const startAt = () => {
      if (token !== loadToken) return;
      const dur = isFiniteNumber(a.duration) ? a.duration : 0;
      const at = time > 1 && dur && time < dur - 1 ? time : 0;
      if (at) a.currentTime = at;
      rememberDuration(book, index, dur);
      if (autoplay) doPlay();
      updateTimeline();
    };

    a.addEventListener('loadedmetadata', startAt, { once: true });

    syncNowPlaying();
    updatePlayState();
    if (state.bookView && state.bookView.id === book.id) {
      renderTrackList(book);
      updateBookMeta(book);
      updateBookStats(book);
    }

    // 预取下一章，保证手机后台/锁屏时 ended 能立刻切章（不被 fetch 挂起）
    prefetchNextTrack(book, index);
  }

  // 后台预取下一章的音频 blob，缓存起来
  function prefetchNextTrack(book, index) {
    const nextIndex = index + 1;
    const next = book.tracks[nextIndex];
    if (!next) { prefetchCache = null; return; }
    if (prefetchCache && prefetchCache.bookId === book.id && prefetchCache.index === nextIndex) return;

    const token = ++prefetchToken;
    const finish = (file) => {
      if (token !== prefetchToken) return;
      prefetchCache = { bookId: book.id, index: nextIndex, file };
    };

    if (next.handle) {
      next.handle.getFile()
        .then(finish)
        .catch(() => { if (token === prefetchToken) prefetchCache = null; });
    } else if (next.src) {
      fetch(next.src)
        .then(res => { if (!res.ok) throw new Error('HTTP ' + res.status); return res.blob(); })
        .then(finish)
        .catch(() => { if (token === prefetchToken) prefetchCache = null; });
    }
  }

  function doPlay() {
    const pr = el.audio.play();
    if (pr && pr.catch) {
      pr.catch(err => {
        if (err.name === 'NotAllowedError') {
          document.body.classList.add('is-paused');
          toast('再点一次播放按钮即可开始（浏览器要求先有一次点击）');
        } else if (err.name === 'NotSupportedError' || err.name === 'InvalidStateError') {
          // 安卓后台/锁屏后，blob 可能失效或音频元素状态异常。
          // 重载当前章节再试一次，能救回来就不打扰用户。
          reloadAndPlay();
        } else if (err.name !== 'AbortError') {
          console.error(err);
        }
      });
    }
  }

  // 音频源失效时重新加载当前章节并恢复播放
  function reloadAndPlay() {
    if (reloadingPlay || !state.current) return;
    reloadingPlay = true;

    const cur = state.current;
    const idx = cur.index;
    // 优先用已保存的进度；音频 src 被清掉后 currentTime 归 0，别把用户进度弄丢
    const prog = state.progress.get(cur.book.id);
    const time = (el.audio.currentTime > 1 ? el.audio.currentTime : (prog ? (prog.time || 0) : 0));

    playFrom(cur.book, idx, { autoplay: true, time })
      .then(() => { reloadingPlay = false; })
      .catch(() => { reloadingPlay = false; toast('播放恢复失败，请重新点一下播放'); });
  }

  function togglePlay() {
    if (!state.current) {
      const first = sortedBooks()[0];
      if (first) openBookAndPlay(first);
      return;
    }
    const a = el.audio;
    if (a.paused) {
      // 已经播到结尾时，重新从本章开头开始
      if (a.ended || (isFiniteNumber(a.duration) && a.duration > 0 && a.currentTime >= a.duration - 0.1)) {
        a.currentTime = 0;
      }
      doPlay();
    } else {
      a.pause();
    }
  }

  function playOrExpand() {
    openSheet();
    if (el.audio.paused) togglePlay();
  }

  async function restartBook(book) {
    const p = ensureProgress(book.id);
    p.done = [];
    p.time = 0;
    p.index = 0;
    p.updatedAt = Date.now();
    dbPut('progress', p).catch(() => {});
    pushServerProgress(book.id, p, true);
    await playFrom(book, 0, { time: 0 });
    if (state.bookView && state.bookView.id === book.id) renderBookView(book);
    openSheet();
    toast('已从头开始');
  }

  function nextTrack(user = true) {
    const c = state.current;
    if (!c) return;
    if (c.index + 1 < c.book.tracks.length) {
      playFrom(c.book, c.index + 1, { time: 0 });
    } else {
      el.audio.pause();
      if (user) toast('已经是最后一章了');
    }
  }

  function prevTrack() {
    const c = state.current;
    if (!c) return;
    if (el.audio.currentTime > 4) { el.audio.currentTime = 0; return; }
    if (c.index > 0) playFrom(c.book, c.index - 1, { time: 0 });
    else el.audio.currentTime = 0;
  }

  async function openBookAndPlay(book, index) {
    const p = progressOf(book.id);
    const start = index !== undefined ? index : Math.min(p ? p.index || 0 : 0, book.tracks.length - 1);
    let time = 0;
    if (index === undefined && p) {
      const done = new Set(p.done || []);
      time = done.has(start) ? 0 : (p.time || 0);
    }
    await playFrom(book, start, { time });
  }

  /**
   * 记住一章的时长。
   * @param {boolean} [silent] 批量探测时置 true，避免每章都整页重绘
   */
  function rememberDuration(book, i, d, silent = false) {
    if (!isFiniteNumber(d) || d <= 0) return;
    const k = durKey(book, i);
    if (state.durations.has(k)) return;
    state.durations.set(k, d);
    dbPut('durations', { key: k, seconds: d }).catch(ignore);

    if (silent) return;
    if (state.bookView && state.bookView.id === book.id) {
      updateBookStats(book);
      updateBookMeta(book);
      const li = el.trackList.children[i];
      const t = li && li.querySelector('.t-time');
      if (t) t.textContent = fmtTime(d);
    }
    if (!el.libraryView.hidden) renderLibrary();
  }

  /**
   * 后台补齐整本书的时长：用一个隐藏的 audio 逐个探 metadata。
   * 扫描期间只写缓存不重绘，扫完统一刷新一次界面。
   */
  async function scanDurations(book) {
    if (!book || book.tracks.length > 800) return;
    const token = ++durScanToken;
    const probe = document.createElement('audio');
    probe.preload = 'metadata';
    let changed = false;

    for (let i = 0; i < book.tracks.length; i++) {
      if (token !== durScanToken) break;
      if (state.durations.has(durKey(book, i))) continue;
      let url = null;
      let revoke = false;
      try {
        if (book.tracks[i].handle) {
          const f = await book.tracks[i].handle.getFile();
          url = URL.createObjectURL(f);
          revoke = true;
        } else {
          url = book.tracks[i].src;
        }
        if (!url) continue;
        probe.src = url;
        const d = await new Promise(resolve => {
          let settled = false;
          const finish = (v) => { if (!settled) { settled = true; resolve(v); } };
          probe.addEventListener('loadedmetadata', () => finish(probe.duration), { once: true });
          probe.addEventListener('error', () => finish(0), { once: true });
          setTimeout(() => finish(0), 8000);
        });
        if (d) { rememberDuration(book, i, d, true); changed = true; }
      } catch { /* 读不到的章节直接跳过 */ }
      finally { if (revoke && url) URL.revokeObjectURL(url); }
    }
    probe.src = '';

    if (!changed) return;
    if (state.bookView && state.bookView.id === book.id) {
      // 原地更新时间，不整页重绘（避免把展开的简介收回去）
      updateBookStats(book);
      updateBookMeta(book);
      book.tracks.forEach((_, i) => {
        const d = state.durations.get(durKey(book, i));
        const time = el.trackList.children[i] && el.trackList.children[i].querySelector('.t-time');
        if (time && d) time.textContent = fmtTime(d);
      });
    } else if (!el.libraryView.hidden) {
      renderLibrary();
    }
  }

  /* ================= 服务器备份 / 恢复（仅管理员） ================= */

  const BACKUP_LABELS = {
    user: '指定用户数据', users: '所有用户数据', meta: '数据库',
    media: '媒体库', full: '全量备份',
  };

  let backupMode = 'user';
  let backupMeta = null;

  function fmtSize(kb) {
    if (!Number.isFinite(kb) || kb <= 0) return '0 B';
    if (kb >= 1024 * 1024) return (kb / 1024 / 1024).toFixed(2) + ' GB';
    if (kb >= 1024) return (kb / 1024).toFixed(1) + ' MB';
    return kb + ' KB';
  }

  function backupQuery(action) {
    let q = BACKUP_API + '?action=' + action + '&mode=' + encodeURIComponent(backupMode);
    if (backupMode === 'user' && el.backupUser.value) {
      q += '&u=' + encodeURIComponent(el.backupUser.value);
    }
    return q;
  }

  function updateBackupSize() {
    if (!backupMeta || !el.backupSize) return;
    const kb = (backupMeta.sizes || {})[backupMode] || 0;
    let text = '「' + (BACKUP_LABELS[backupMode] || backupMode) + '」预计 ' + fmtSize(kb);
    if (backupMeta.freeKb) text += ' · 路由器可用空间 ' + fmtSize(backupMeta.freeKb);
    if (backupMode === 'media' || backupMode === 'full') {
      text += '。包含音频，下载时间会很长，建议用电脑操作。';
    }
    el.backupSize.textContent = text;
  }

  function syncBackupModeUI() {
    el.backupUserRow.hidden = backupMode !== 'user';
    el.backupModes.querySelectorAll('.backup-mode').forEach(node => {
      const input = node.querySelector('input');
      node.classList.toggle('on', !!(input && input.checked));
    });
    updateBackupSize();
  }

  async function loadBackupInfo() {
    if (state.source !== 'net') {
      el.backupSize.textContent = '没连上书库服务，服务器备份不可用。';
      el.backupDownloadBtn.disabled = true;
      el.backupRestoreBtn.disabled = true;
      return;
    }
    try {
      const res = await fetch(backupQuery('info'));
      const data = await res.json();
      if (!data.ok) throw new Error(data.error || '读取失败');
      backupMeta = data;

      const prev = el.backupUser.value;
      el.backupUser.replaceChildren();
      (data.users || []).forEach(u => {
        const opt = document.createElement('option');
        opt.value = u;
        opt.textContent = u;
        el.backupUser.appendChild(opt);
      });
      if (prev && (data.users || []).includes(prev)) el.backupUser.value = prev;

      updateBackupSize();
      el.backupDownloadBtn.disabled = false;
      el.backupRestoreBtn.disabled = false;
    } catch (e) {
      backupMeta = null;
      el.backupSize.textContent = '读取备份信息失败：' + (e.message || e);
      el.backupDownloadBtn.disabled = true;
      el.backupRestoreBtn.disabled = true;
    }
  }

  function downloadServerBackup() {
    if (!isAdmin()) { toast('只有管理员可以备份'); return; }
    if (backupMode === 'user' && !el.backupUser.value) { toast('请先选择用户'); return; }
    const a = document.createElement('a');
    a.href = backupQuery('export');
    a.download = '';   // 文件名由服务端的 Content-Disposition 决定
    document.body.appendChild(a);
    a.click();
    a.remove();
    toast('开始下载备份：' + BACKUP_LABELS[backupMode], 2600);
  }

  async function restoreServerBackup(file) {
    if (!file) return;
    if (!/\.(tar\.gz|tgz)$/i.test(file.name)) {
      toast('请选择 tar.gz 备份包', 3000);
      el.backupFile.value = '';
      return;
    }
    const label = BACKUP_LABELS[backupMode] || backupMode;
    const withMedia = backupMode === 'media' || backupMode === 'full';
    if (!window.confirm(
      '确定从「' + file.name + '」恢复吗？\n\n' +
      '恢复会直接覆盖路由器上同名的数据' + (withMedia ? '与媒体文件' : '') +
      '，不会删除备份包里没有的文件。\n当前模式：' + label + '。'
    )) {
      el.backupFile.value = '';
      return;
    }

    toast('正在上传并恢复，请勿关闭页面…', 4000);
    try {
      const res = await fetch(backupQuery('import'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/gzip' },
        body: file,
      });
      const data = await res.json();
      if (!res.ok || !data.ok) throw new Error(data.error || ('HTTP ' + res.status));
      toast('恢复完成，共 ' + (data.entries || 0) + ' 个条目', 3200);
      closeBackupSheet();
    } catch (e) {
      toast('恢复失败：' + (e.message || e), 4200);
    } finally {
      el.backupFile.value = '';
    }
  }

  /* ================= 进度导出 / 导入 ================= */

  function downloadBlob(blob, name) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  function progressCount() {
    let n = 0;
    state.progress.forEach(p => {
      if (p && ((p.time || 0) > 1 || (p.done || []).length)) n++;
    });
    return n;
  }

  function updateBackupInfo() {
    if (!el.backupInfo) return;
    const n = progressCount();
    el.backupInfo.textContent = n
      ? '当前有 ' + n + ' 本书留下了听书记录，可以导出保存。'
      : '还没有任何听书记录，先听一段再回来导出。';
    el.exportBtn.disabled = !n;
  }

  function openBackupSheet() {
    if (!isAdmin()) { toast('只有管理员可以备份'); return; }
    updateBackupInfo();
    syncBackupModeUI();
    openOverlay(el.backupSheet);
    loadBackupInfo();
  }

  function closeBackupSheet() {
    closeOverlay(el.backupSheet);
  }

  function exportProgress() {
    const items = {};
    let n = 0;

    state.progress.forEach((p, id) => {
      if (!p) return;
      if (!((p.time || 0) > 1 || (p.done || []).length)) return;
      items[id] = {
        index: Math.max(0, p.index | 0),
        time: Number(p.time) || 0,
        done: Array.isArray(p.done) ? p.done.slice() : [],
        updatedAt: p.updatedAt || 0,
      };
      n++;
    });

    if (!n) { toast('还没有可导出的进度'); return; }

    const payload = {
      app: 'tingshu',
      version: PROGRESS_FILE_VERSION,
      exportedAt: Date.now(),
      items,
    };

    const d = new Date();
    const p2 = (x) => String(x).padStart(2, '0');
    const stamp = '' + d.getFullYear() + p2(d.getMonth() + 1) + p2(d.getDate())
      + '-' + p2(d.getHours()) + p2(d.getMinutes());

    downloadBlob(
      new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }),
      'tingshu-progress-' + stamp + '.json'
    );
    toast('已导出 ' + n + ' 本书的进度', 2400);
  }

  async function importProgressFile(file) {
    let data;
    try {
      data = JSON.parse(await file.text());
    } catch {
      toast('这个文件不是有效的 JSON', 3000);
      return;
    }

    const items = data && data.items;
    if (!items || typeof items !== 'object' || Array.isArray(items)) {
      toast('不是听书进度备份文件', 3000);
      return;
    }

    let added = 0;
    let updated = 0;
    let skipped = 0;

    for (const id in items) {
      const rec = items[id];
      if (!rec || typeof rec !== 'object') continue;

      const ts = Number(rec.updatedAt) || 0;
      const local = state.progress.get(id);
      if (local && (local.updatedAt || 0) >= ts) { skipped++; continue; }

      const merged = {
        bookId: id,
        index: Math.max(0, rec.index | 0),
        time: Math.max(0, Number(rec.time) || 0),
        done: Array.isArray(rec.done) ? rec.done.filter(v => Number.isInteger(v) && v >= 0) : [],
        updatedAt: ts,
      };

      state.progress.set(id, merged);
      dbPut('progress', merged).catch(() => {});
      pushServerProgress(id, merged, true);

      if (local) updated++; else added++;
    }

    renderLibrary();
    if (state.bookView) renderBookView(state.bookView);
    updateBackupInfo();
    closeBackupSheet();

    if (!added && !updated) toast('没有更新的记录，已跳过 ' + skipped + ' 本', 3000);
    else toast('导入完成：新增 ' + added + ' 本，更新 ' + updated + ' 本', 3200);
  }

  /* ================= 上传 / 删除（服务器端） ================= */

  const MANAGE_API = 'cgi-ts/manage';
  const UPLOAD_RE = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm|ape|wma|jpg|jpeg|png|webp|gif|bmp|txt|nfo|m3u|m3u8|json|srt|lrc)$/i;

  function openUploadSheet() {
    if (!isAdmin()) {
      toast('只有管理员可以上传书籍');
      return;
    }
    if (state.source !== 'net') {
      // 没有连上路由器（比如直接双击 index.html），退回本机文件夹
      toast('没连上书库服务，改为选择本机文件夹', 2600);
      pickFolder();
      return;
    }
    el.uploadDir.textContent = state.serverRoot || '书架目录';
    el.uploadProgress.hidden = true;
    el.uploadBar.style.width = '0%';
    el.uploadStatus.textContent = '';
    openOverlay(el.uploadSheet);
  }

  function closeUploadSheet() {
    closeOverlay(el.uploadSheet);
  }

  function setUploadBusy(busy) {
    el.pickFolderBtn.disabled = busy;
    el.pickArchiveBtn.disabled = busy;
  }

  /* 上传完重新拉索引，界面直接刷新 */
  async function refreshServerLibrary() {
    const books = await loadServerLibrary();
    if (books) await setLibrary(books);
  }

  async function rescanServer() {
    if (!isAdmin()) { toast('只有管理员可以重新扫描书库'); return; }
    try {
      const res = await fetch(MANAGE_API + '?action=rescan', { method: 'POST' });
      return await res.json();
    } catch { return null; }
  }

  /* 整包上传：如果选中的是一个「装着多本书的大文件夹」，把外层剥掉，
     让里面的子文件夹各自成为一本书。
     两个反例不能剥：
       1) 音频直接躺在选中目录里（深度 2）——这个目录本身就是一本书
       2) 子目录是「光盘1/光盘2」「CD1/CD2」这类分盘目录——是一本书拆成了几盘
     对不上就从原样保留。 */
  const AUDIO_ONLY_RE = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm|ape|wma)$/i;

  function looksLikeDisc(name) {
    const s = String(name).trim();
    if (/^(cd|disc|disk|dvd|vol|volume|part)[\s._-]*\d*$/i.test(s)) return true;
    if (/^(光盘|碟|碟片|上部|中部|下部|上集|下集|上卷|下卷|上册|下册)\s*\d*$/.test(s)) return true;
    if (/^第?\s*[0-9一二三四五六七八九十]{1,3}\s*[卷部集册章节]?$/.test(s)) return true;
    return false;
  }

  function stripWrapperRoot(names) {
    if (!names.length) return names;

    const audioNames = names.filter(n => AUDIO_ONLY_RE.test(n));
    const probe = audioNames.length ? audioNames : names;

    let minDepth = Infinity;
    let sameRoot = true;
    let root = null;
    const secondLevel = new Set();

    for (const n of probe) {
      const parts = n.split('/');
      if (parts.length < minDepth) minDepth = parts.length;
      if (root === null) root = parts[0];
      else if (parts[0] !== root) sameRoot = false;
      if (parts.length >= 3) secondLevel.add(parts[1]);
    }

    if (!sameRoot || !root) return names;      // 不是同一个根目录
    if (minDepth < 3) return names;            // 根目录里直接有音频 → 它本身就是一本书
    if (secondLevel.size < 2) return names;    // 只有一个子目录 → 可能是一本书分盘

    // 有一个子目录像「光盘/CD/第N卷」，就当成一本书的分盘，不剥
    for (const d of secondLevel) {
      if (looksLikeDisc(d)) return names;
    }

    const cut = root.length + 1;
    return names.map(n => (n.length > cut ? n.slice(cut) : ''));
  }

  async function uploadFiles(files) {
    const list = files.filter(f => UPLOAD_RE.test(f.name));
    if (!list.length) { toast('没有可上传的音频文件'); return; }

    // 可能是「选择了一个装着多本书的大文件夹」，先把外层剥掉
    const rawPaths = list.map(f => (f.webkitRelativePath || f.name));
    const paths = stripWrapperRoot(rawPaths);

    // 剥壳后还落在根目录上的文件（比如容器里的封面）不属于任何一本书，跳过
    const jobs = [];
    for (let i = 0; i < list.length; i++) {
      const p = paths[i];
      if (p && p.indexOf('/') >= 0) jobs.push({ file: list[i], path: p });
    }
    if (!jobs.length) { toast('没有可上传的音频文件'); return; }

    setUploadBusy(true);
    el.uploadProgress.hidden = false;

    const total = jobs.length;
    let done = 0;
    let bytes = 0;
    let failed = 0;

    for (const job of jobs) {
      const f = job.file;
      done++;

      // 逐段编码，保留目录分隔符
      const rel = job.path.split('/').map(encodeURIComponent).join('/');
      try {
        const res = await fetch(MANAGE_API + '?action=upload&p=' + rel, { method: 'POST', body: f });
        const data = await res.json().catch(() => null);
        if (!res.ok || !data || !data.ok) failed++;
        else bytes += (data.size || f.size);
      } catch { failed++; }

      el.uploadBar.style.width = Math.round((done / total) * 100) + '%';
      el.uploadStatus.textContent = '正在上传 ' + done + '/' + total + ' · ' + fmtSize(bytes);
    }

    el.uploadStatus.textContent = '正在整理书库…';
    await rescanServer();
    await refreshServerLibrary();

    setUploadBusy(false);
    closeUploadSheet();

    if (failed) toast('完成，但有 ' + failed + ' 个文件失败', 3200);
    else toast('已上传 ' + total + ' 个文件', 2400);
  }

  /* ---------- 浏览器内解 zip：服务端的 unzip 会把中文名转成 GBK ---------- */

  function decodeZipName(bytes, flags) {
    if (flags & 0x800) return new TextDecoder('utf-8').decode(bytes);
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      try { return new TextDecoder('gbk').decode(bytes); } catch { /* 兜底 */ }
      return new TextDecoder('utf-8').decode(bytes);
    }
  }

  async function inflateRaw(bytes) {
    if (typeof DecompressionStream !== 'function') throw new Error('浏览器不支持解压');
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Response(stream).blob();
  }

  /* 只按需读取需要的字节，不会把整个压缩包读进内存 */
  async function readZipEntries(file) {
    if (file.size < 22) throw new Error('文件太小');

    const tailLen = Math.min(file.size, 0x10000);
    const tail = new Uint8Array(await file.slice(file.size - tailLen).arrayBuffer());
    let p = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 0x05 && tail[i + 3] === 0x06) { p = i; break; }
    }
    if (p < 0) throw new Error('不是有效的 zip 文件');

    const tdv = new DataView(tail.buffer, tail.byteOffset);
    const count = tdv.getUint16(p + 10, true);
    const cdSize = tdv.getUint32(p + 12, true);
    const cdOff = tdv.getUint32(p + 16, true);
    if (cdOff === 0xffffffff || cdSize === 0xffffffff) throw new Error('不支持 ZIP64');

    const cd = new Uint8Array(await file.slice(cdOff, cdOff + cdSize).arrayBuffer());
    const dv = new DataView(cd.buffer, cd.byteOffset);

    const out = [];
    let off = 0;
    for (let i = 0; i < count && off + 46 <= cd.length; i++) {
      if (dv.getUint32(off, true) !== 0x02014b50) break;

      const flags = dv.getUint16(off + 8, true);
      const method = dv.getUint16(off + 10, true);
      const csize = dv.getUint32(off + 20, true);
      const nameLen = dv.getUint16(off + 28, true);
      const extraLen = dv.getUint16(off + 30, true);
      const commentLen = dv.getUint16(off + 32, true);
      const localOff = dv.getUint32(off + 42, true);
      const name = decodeZipName(cd.subarray(off + 46, off + 46 + nameLen), flags);

      off += 46 + nameLen + extraLen + commentLen;

      if (!name || name.endsWith('/')) continue;
      if (name.startsWith('__MACOSX/') || name.indexOf('/.') === 0 || name.indexOf('.DS_Store') >= 0) continue;
      if (name.indexOf('..') >= 0) continue;

      out.push({
        name,
        method,
        csize,
        localOff,
        read: async () => {
          const lh = new Uint8Array(await file.slice(localOff, localOff + 30).arrayBuffer());
          const lv = new DataView(lh.buffer, lh.byteOffset);
          if (lv.getUint32(0, true) !== 0x04034b50) throw new Error('zip 结构损坏');
          const start = localOff + 30 + lv.getUint16(26, true) + lv.getUint16(28, true);
          const raw = new Uint8Array(await file.slice(start, start + csize).arrayBuffer());
          if (method === 0) return new Blob([raw]);
          if (method === 8) return inflateRaw(raw);
          throw new Error('不支持的压缩方式');
        },
      });
    }
    return out;
  }

  async function uploadZip(file) {
    setUploadBusy(true);
    el.uploadProgress.hidden = false;
    el.uploadBar.style.width = '4%';
    el.uploadStatus.textContent = '正在解析压缩包…';

    let entries;
    try {
      entries = await readZipEntries(file);
    } catch (err) {
      setUploadBusy(false);
      el.uploadStatus.textContent = '解析失败';
      toast('压缩包读取失败：' + (err.message || err), 3600);
      return;
    }

    const list = entries.filter(e => UPLOAD_RE.test(e.name));
    if (!list.length) {
      setUploadBusy(false);
      el.uploadStatus.textContent = '压缩包里没有音频';
      toast('压缩包里没有可用的音频文件', 3000);
      return;
    }

    // 压缩包常见结构是「一个总目录/书/?」，同样把总目录剥掉
    const paths = stripWrapperRoot(list.map(e => e.name));

    const jobs = [];
    for (let i = 0; i < list.length; i++) {
      const p = paths[i];
      if (p && p.indexOf('/') >= 0) jobs.push({ entry: list[i], path: p });
    }
    if (!jobs.length) {
      setUploadBusy(false);
      el.uploadStatus.textContent = '压缩包里没有成书的目录';
      toast('压缩包里没有按书分目录的音频', 3200);
      return;
    }

    let done = 0;
    let failed = 0;
    for (const job of jobs) {
      const e = job.entry;
      done++;
      try {
        const blob = await e.read();
        const rel = job.path.split('/').map(encodeURIComponent).join('/');
        const res = await fetch(MANAGE_API + '?action=upload&p=' + rel, { method: 'POST', body: blob });
        const data = await res.json().catch(() => null);
        if (!res.ok || !data || !data.ok) failed++;
      } catch { failed++; }

      el.uploadBar.style.width = Math.round(4 + (done / jobs.length) * 90) + '%';
      el.uploadStatus.textContent = '正在导入 ' + done + '/' + jobs.length + ' · ' + e.name.split('/').pop();
    }

    el.uploadStatus.textContent = '正在整理书库…';
    await rescanServer();
    await refreshServerLibrary();

    setUploadBusy(false);
    closeUploadSheet();

    if (failed) toast('导入完成，' + failed + ' 个文件失败', 3200);
    else toast('已导入 ' + done + ' 个文件', 2400);
  }

  async function uploadArchive(file) {
    setUploadBusy(true);
    el.uploadProgress.hidden = false;
    el.uploadBar.style.width = '12%';
    el.uploadStatus.textContent = '正在上传 ' + file.name + '（' + fmtSize(file.size) + '）…';

    try {
      const res = await fetch(MANAGE_API + '?action=archive&n=' + encodeURIComponent(file.name), {
        method: 'POST',
        body: file,
      });
      const data = await res.json().catch(() => null);
      el.uploadBar.style.width = '72%';

      if (!res.ok || !data || !data.ok) {
        setUploadBusy(false);
        el.uploadStatus.textContent = '解压失败';
        toast('导入失败：' + ((data && data.error) || ('HTTP ' + res.status)), 3600);
        return;
      }

      el.uploadStatus.textContent = '正在整理书库…';
      await rescanServer();
      await refreshServerLibrary();

      setUploadBusy(false);
      closeUploadSheet();
      toast('已导入 ' + data.items + ' 项', 2400);
    } catch (err) {
      setUploadBusy(false);
      el.uploadStatus.textContent = '上传失败';
      toast('上传失败：' + (err.message || err), 3600);
    }
  }

  /* 删掉书架上的书：连本地进度、合集引用一起清理 */
  async function deleteBook(book) {
    if (!isAdmin()) {
      toast('只有管理员可以删除书籍');
      return;
    }
    const folder = String(book.id).indexOf('net:') === 0 ? book.id.slice(4) : book.folderTitle;
    const ask = '删除「' + book.title + '」？\n\n' +
      '会把路由器上「' + folder + '」这个文件夹整个删掉，里面的音频也一起没了，不能恢复。';
    if (!window.confirm(ask)) return;

    try {
      const res = await fetch(MANAGE_API + '?action=delete&b=' + encodeURIComponent(folder), { method: 'POST' });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data || !data.ok) {
        toast('删除失败：' + ((data && data.error) || ('HTTP ' + res.status)), 3600);
        return;
      }
    } catch (err) {
      toast('删除失败：' + (err.message || err), 3600);
      return;
    }

    state.progress.delete(book.id);
    dbDelete('progress', book.id).catch(() => {});
    state.collections.forEach(c => {
      if (Array.isArray(c.bookIds) && c.bookIds.includes(book.id)) {
        c.bookIds = c.bookIds.filter(id => id !== book.id);
        touchCollection(c);
        dbPut('collections', c).catch(() => {});
      }
    });
    pushServerCollections();

    closeEditor();

    // 正在看这本书的详情页，删完退回书架
    if (state.bookView && state.bookView.id === book.id) {
      state.bookView = null;
      goLibrary();
    }

    if (state.current && state.current.book.id === book.id) {
      el.audio.pause();
      el.audio.removeAttribute('src');
      state.current = null;
      setMiniVisible(false);
    }

    await rescanServer();
    await refreshServerLibrary();
    toast('已删除「' + book.title + '」', 2400);
  }

  /* ================= 进度云同步（服务器端） ================= */

  const SYNC_API = 'cgi-ts/progress';
  const syncPushedAt = new Map();
  const syncLastSent = new Map();

  function hexOf(str) {
    const bytes = new TextEncoder().encode(String(str));
    let out = '';
    for (let i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, '0');
    return out;
  }

  function unhex(hex) {
    let s = '';
    for (let i = 0; i + 1 < hex.length; i += 2) s += '%' + hex.slice(i, i + 2);
    try { return decodeURIComponent(s); } catch { return ''; }
  }

  /* 启动时从服务器拉一次，按 updatedAt 取新的那份 */
  async function pullServerProgress() {
    let data;
    try {
      const res = await fetch(SYNC_API + '?t=' + Date.now(), { cache: 'no-store' });
      if (!res.ok) return 0;
      data = await res.json();
    } catch { return 0; }
    if (!data || data.ok !== true || !data.items) return 0;

    syncEnabled = true;
    let changed = 0;

    for (const hex in data.items) {
      const rec = data.items[hex];
      if (!rec || typeof rec !== 'object') continue;
      const bookId = unhex(hex);
      if (!bookId) continue;

      const local = state.progress.get(bookId);
      const localReal = !!local && ((local.time || 0) > 1 || (local.done || []).length > 0);
      const remoteReal = (Number(rec.time) || 0) > 1 || (Array.isArray(rec.done) && rec.done.length > 0);

      if (local && !localReal && !remoteReal) continue;                 // 两边都是空记录
      if (localReal && (local.updatedAt || 0) >= (rec.updatedAt || 0)) continue;  // 本地更新
      if (local && !localReal && remoteReal) { /* 本地那条是空的，让服务端覆盖 */ }

      const merged = {
        bookId,
        index: rec.index | 0,
        time: Number(rec.time) || 0,
        done: Array.isArray(rec.done) ? rec.done.filter(n => typeof n === 'number') : [],
        updatedAt: rec.updatedAt || 0,
      };
      state.progress.set(bookId, merged);
      dbPut('progress', merged).catch(() => {});
      changed++;
    }

    updateSyncHint();
    return changed;
  }

  function syncCore(bookId, rec) {
    return {
      b: hexOf(bookId),
      i: Math.max(0, rec.index | 0),
      t: Math.max(0, Number(rec.time) || 0).toFixed(2),
      d: (rec.done || []).filter(n => Number.isInteger(n) && n >= 0).join(','),
    };
  }

  function syncKey(core) {
    return core.i + '|' + core.t + '|' + core.d;
  }

  function syncBody(core, u) {
    return 'b=' + core.b + '&i=' + core.i + '&t=' + core.t + '&d=' + core.d + '&u=' + u;
  }

  /**
   * 推送单本进度。播放中最多 15 秒一次，暂停/切后台时强制推；
   * 内容没变（同一章同一秒）就不推，免得多端之间用旧位置互相覆盖。
   */
  function pushServerProgress(bookId, rec, force) {
    if (!syncEnabled) return;
    const core = syncCore(bookId, rec);
    const key = syncKey(core);
    if (syncLastSent.get(bookId) === key) return;

    const now = Date.now();
    if (!force && now - (syncPushedAt.get(bookId) || 0) < PUSH_EVERY) return;

    syncPushedAt.set(bookId, now);
    syncLastSent.set(bookId, key);

    fetch(SYNC_API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: syncBody(core, rec.updatedAt || now),
      keepalive: true,
    }).catch(() => {});
  }

  /* 关页面时用 beacon 兜底，普通 fetch 可能被取消 */
  function beaconProgress() {
    if (!syncEnabled || !state.current || !state.playedOnce) return;
    const p = state.progress.get(state.current.book.id);
    if (!p) return;
    const core = syncCore(p.bookId, p);
    if (syncLastSent.get(p.bookId) === syncKey(core)) return;
    try {
      const blob = new Blob([syncBody(core, Date.now())], { type: 'application/x-www-form-urlencoded' });
      navigator.sendBeacon(SYNC_API, blob);
    } catch { /* 忽略 */ }
  }

  function updateSyncHint() {
    if (!el.sheetFoot) return;
    el.sheetFoot.textContent = syncEnabled
      ? '进度已同步到路由器 · 换台设备也能接着听'
      : '进度已自动保存 · 关闭网页也不会丢';
  }

  /* ================= 合集云同步（服务器端） ================= */

  const COL_API = 'cgi-ts/collections';
  let colPushTimer = null;

  function touchCollection(col) {
    col.updatedAt = Date.now();
    return col;
  }

  /* 把整个合集列表推到服务器 */
  function pushServerCollections() {
    if (!colSyncEnabled) return;
    // 合并小步操作，避免连续操作时疯狂发请求
    clearTimeout(colPushTimer);
    colPushTimer = setTimeout(() => {
      const payload = state.collections.map(c => ({
        id: c.id,
        name: c.name,
        bookIds: (c.bookIds || []).slice(),
        createdAt: c.createdAt || 0,
        updatedAt: c.updatedAt || c.createdAt || 0,
      }));
      const body = 'c=' + encodeURIComponent(JSON.stringify(payload));
      fetch(COL_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
        keepalive: true,
      }).catch(() => {});
    }, 400);
  }

  /* 启动时拉一次，按每个合集 updatedAt 取新的 */
  async function pullServerCollections() {
    let data;
    try {
      const res = await fetch(COL_API + '?t=' + Date.now(), { cache: 'no-store' });
      if (!res.ok) return 0;
      data = await res.json();
    } catch { return 0; }
    if (!data || data.ok !== true) return 0;

    colSyncEnabled = true;
    const remote = Array.isArray(data.data) ? data.data : [];
    if (!remote.length) {
      // 服务端明确说没有合集：清掉本地缓存，避免 App 删除后网页还留着
      if (state.collections.length) {
        state.collections = [];
        dbClear('collections').catch(() => {});
        renderLibrary();
        return 1;
      }
      return 0;
    }

    const localMap = new Map(state.collections.map(c => [c.id, c]));
    let changed = 0;

    for (const rec of remote) {
      if (!rec || typeof rec !== 'object' || !rec.id) continue;
      const local = localMap.get(rec.id);
      const remoteTs = Number(rec.updatedAt) || Number(rec.createdAt) || 0;
      const localTs = local ? (local.updatedAt || local.createdAt || 0) : 0;

      if (local && localTs >= remoteTs) continue;   // 本地更新，保留

      const merged = {
        id: rec.id,
        name: rec.name || '未命名合集',
        bookIds: Array.isArray(rec.bookIds) ? rec.bookIds.filter(x => typeof x === 'string') : [],
        createdAt: Number(rec.createdAt) || remoteTs,
        updatedAt: remoteTs,
      };

      if (local) {
        const i = state.collections.indexOf(local);
        if (i >= 0) state.collections[i] = merged;
      } else {
        state.collections.push(merged);
      }
      dbPut('collections', merged).catch(() => {});
      changed++;
    }

    if (changed) renderLibrary();
    return changed;
  }

  /* ================= 书籍元数据云同步（书名/简介/配色/封面） ================= */

  const META_API = 'cgi-ts/meta';
  const metaPushedAt = new Map();

  function metaOf(book) {
    return {
      title: book.title && book.title !== book.folderTitle ? book.title : '',
      desc: book.desc || '',
      palette: book.palette != null ? book.palette : null,
      coverBlob: book.userCover || null,
    };
  }

  /* 把一本书的元数据推到服务器 */
  async function pushServerMeta(book) {
    if (!book) return false;
    const now = Date.now();
    if (now - (metaPushedAt.get(book.id) || 0) < 1000) return true;
    metaPushedAt.set(book.id, now);

    try {
      const m = metaOf(book);
      let coverB64 = '';
      let coverType = '';
      // 封面编码失败不应阻塞简介/书名推送：单独 try，失败就跳过封面
      try {
        let coverBlob = m.coverBlob;
        if (!(coverBlob instanceof Blob) && book.userCoverUrl && book.userCoverUrl.startsWith('blob:')) {
          try { coverBlob = await (await fetch(book.userCoverUrl)).blob(); } catch { coverBlob = null; }
        }
        if (coverBlob instanceof Blob) {
          coverType = coverBlob.type || 'image/png';
          coverB64 = await blobToBase64(coverBlob);
        }
      } catch { coverB64 = ''; coverType = ''; }

      const payload = {
        id: hexOf(book.id),
        title: m.title,
        desc: m.desc,
        palette: m.palette,
        cover: coverB64,
        coverType,
        updatedAt: now,
      };

      const res = await fetch(META_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const rdata = await res.json().catch(() => null);
      if (!res.ok || !rdata || rdata.ok !== true) {
        console.warn('[meta] 推送失败', res.status, rdata);
        return false;
      }
      return true;

    } catch (err) {
      console.warn('[meta] 同步异常', err && err.message);
      return false;
    }
  }

  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).split(',')[1] || '');
      r.onerror = reject;
      r.readAsDataURL(blob);
    });
  }

  /* 启动时拉取所有书籍元数据，合并到本地 meta 表 */
  async function pullServerMeta() {
    let data;
    try {
      const res = await fetch(META_API + '?t=' + Date.now(), { cache: 'no-store' });
      if (!res.ok) return 0;
      data = await res.json();
    } catch { return 0; }
    if (!data || data.ok !== true || !data.items) return 0;

    let changed = 0;

    for (const hex in data.items) {
      const rec = data.items[hex];
      if (!rec || typeof rec !== 'object') continue;
      const bookId = unhex(hex);
      if (!bookId) continue;
      if (!(rec.title || rec.desc || rec.palette != null || rec.cover)) continue;

      // 本地 meta
      let local = null;
      try { local = await dbGet('meta', bookId); } catch { local = null; }
      const remoteTs = Number(rec.updatedAt) || 0;
      const localTs = local ? (Number(local.updatedAt) || 0) : 0;

      // 合并判断：本地更「新」才跳过。
      // 但设备时钟可能不一致，稳妥起见：只要内容有差异，就以服务器为准
      // （服务器是多端汇聚点，通常是最权威的）。
      const localDesc = local ? (local.desc || '') : '';
      const remoteDesc = rec.desc || '';
      const contentDiffers = localDesc !== remoteDesc || (local && (local.title || '') !== (rec.title || ''));
      if (local && !contentDiffers && localTs >= remoteTs) continue;

      const merged = {
        bookId,
        title: rec.title || '',
        desc: rec.desc || '',
        palette: rec.palette != null ? rec.palette : null,
        cover: null,
        updatedAt: remoteTs,
      };
      if (rec.cover) {
        try {
          const bin = atob(rec.cover);
          const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          merged.cover = new Blob([bytes], { type: rec.coverType || 'image/png' });
        } catch { /* 封面解码失败就跳过 */ }
      }

      const empty = !merged.title && !merged.desc && merged.palette == null && !merged.cover;
      if (empty) await dbDelete('meta', bookId).catch(() => {});
      else await dbPut('meta', merged).catch(() => {});

      // 立即应用到内存里的书
      const book = state.books.find(b => b.id === bookId);
      if (book) {
        if (merged.title) book.title = merged.title;
        if (merged.desc) book.desc = merged.desc;
        if (merged.palette != null) book.palette = merged.palette;
        if (merged.cover instanceof Blob) {
          if (book.userCoverUrl) URL.revokeObjectURL(book.userCoverUrl);
          book.userCover = merged.cover;
          book.userCoverUrl = URL.createObjectURL(merged.cover);
        }
      }
      changed++;
    }

    if (changed) {
      renderLibrary();
      if (state.bookView) renderBookView(state.bookView);
    }
    return changed;
  }

  /* ================= 暂停记录云同步 ================= */

  const PAUSEMARKS_API = 'cgi-ts/pausemarks';
  let pmPushTimer = null;

  /* 删除墓碑：id → 删除时间。推送时带上，另一台设备才不会把删掉的记录又推回来。 */
  let pmRemoved = new Map();
  let pmRemovedLoaded = false;

  function pmRemovedKey() {
    return 'tingshu.pmRemoved.' + (state.user || 'anon');
  }

  function loadPmRemoved() {
    if (pmRemovedLoaded) return;
    pmRemovedLoaded = true;
    try {
      const raw = localStorage.getItem(pmRemovedKey());
      const obj = raw ? (JSON.parse(raw) || {}) : {};
      Object.keys(obj).forEach((id) => pmRemoved.set(id, Number(obj[id]) || 0));
    } catch { /* 忽略 */ }
  }

  function savePmRemoved() {
    loadPmRemoved();
    // 90 天前的墓碑没必要一直留着
    const cutoff = Date.now() - 90 * 24 * 3600 * 1000;
    for (const [id, ts] of [...pmRemoved]) if (ts && ts < cutoff) pmRemoved.delete(id);
    try {
      localStorage.setItem(pmRemovedKey(), JSON.stringify(Object.fromEntries(pmRemoved)));
    } catch { /* 忽略 */ }
  }

  function rememberRemoved(ids) {
    loadPmRemoved();
    const now = Date.now();
    (ids || []).forEach((id) => { if (id) pmRemoved.set(id, now); });
    savePmRemoved();
  }

  function pauseMarkPayload(list) {
    return (list || []).map((m) => ({
      id: m.id,
      bookId: m.bookId,
      bookTitle: m.bookTitle || '',
      index: m.index,
      trackTitle: m.trackTitle || '',
      time: m.time,
      reason: m.reason || 'pause',
      createdAt: m.createdAt || 0,
      source: m.source || '',
    }));
  }

  async function fetchPauseMarksRemote() {
    const res = await fetch(PAUSEMARKS_API + '?t=' + Date.now(), { cache: 'no-store' });
    if (!res.ok) return null;
    const data = await res.json();
    if (!data || data.ok !== true) return null;
    return {
      items: Array.isArray(data.items) ? data.items : [],
      removed: (data.removed && typeof data.removed === 'object') ? data.removed : {},
    };
  }

  /** 把服务器上的记录与墓碑并进本地（按 id 取较新的那份），返回变动的条数。 */
  async function mergePauseMarks(remote) {
    const items = remote.items || [];
    loadPmRemoved();
    for (const [id, ts] of Object.entries(remote.removed || {})) {
      const t = Number(ts) || 0;
      if (!pmRemoved.has(id) || pmRemoved.get(id) < t) pmRemoved.set(id, t);
    }
    savePmRemoved();

    let local = [];
    try { local = await dbAll('pausemarks'); } catch { local = []; }
    const localMap = new Map((local || []).map((m) => [m.id, m]));

    let changed = 0;
    // 墓碑生效：服务器说删过的，本地这份如果没更新过也删掉
    for (const [id, ts] of pmRemoved) {
      const existing = localMap.get(id);
      if (!existing) continue;
      if ((Number(existing.createdAt) || 0) <= ts) {
        await dbDelete('pausemarks', id).catch(() => {});
        localMap.delete(id);
        changed++;
      }
    }
    // 记录合并：同 id 取 createdAt 较新的
    for (const rec of items) {
      if (!rec || typeof rec !== 'object' || !rec.id) continue;
      const existing = localMap.get(rec.id);
      const remoteTs = Number(rec.createdAt) || 0;
      const localTs = existing ? (Number(existing.createdAt) || 0) : 0;
      if (existing && localTs >= remoteTs) continue;

      const merged = {
        id: rec.id,
        bookId: rec.bookId,
        bookTitle: rec.bookTitle || '',
        index: Number(rec.index) || 0,
        trackTitle: rec.trackTitle || '',
        time: Number(rec.time) || 0,
        reason: rec.reason || 'pause',
        createdAt: remoteTs,
        source: normalizeMarkSource(rec.source),
      };
      await dbPut('pausemarks', merged).catch(() => {});
      localMap.set(rec.id, merged);
      changed++;
    }

    return changed;
  }

  // 启动时从服务器拉取暂停记录，合并到本地
  async function pullServerPauseMarks() {
    const remote = await fetchPauseMarksRemote().catch(() => null);
    if (!remote) return 0;
    return mergePauseMarks(remote);
  }

  /**
   * 推送暂停记录：先拉一遍合并，再把完整状态（记录 + 墓碑）写回。
   * 只推本地列表会覆盖掉另一台设备刚记的内容，所以这里必须先读后写。
   */
  function pushServerPauseMarks() {
    if (state.source !== 'net') return;
    clearTimeout(pmPushTimer);
    pmPushTimer = setTimeout(async () => {
      try {
        const remote = await fetchPauseMarksRemote().catch(() => null);
        if (remote) await mergePauseMarks(remote);
        let marks = [];
        try { marks = await dbAll('pausemarks'); } catch { marks = []; }
        await fetch(PAUSEMARKS_API, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            items: pauseMarkPayload(marks),
            removed: Object.fromEntries(pmRemoved),
          }),
        });
      } catch { /* 忽略 */ }
    }, 500);
  }

  function ensureProgress(bookId) {
    let p = state.progress.get(bookId);
    if (!p) { p = { bookId, index: 0, time: 0, done: [], updatedAt: 0 }; state.progress.set(bookId, p); }
    if (!Array.isArray(p.done)) p.done = [];
    return p;
  }

  /**
   * 把当前播放位置落盘（IndexedDB）并按需推送到服务器。
   * @param {boolean} rerender 是否顺手刷新书架
   * @param {boolean} forcePush 忽略推送节流
   */
  function saveProgress(rerender = false, forcePush = false) {
    const c = state.current;
    if (!c) return;
    // 只打开页面（恢复位置会触发 pause）时不能落盘，否则会把已有进度冲成 0
    if (!state.playedOnce) return;
    const p = ensureProgress(c.book.id);
    p.index = c.index;
    p.time = el.audio.currentTime || 0;
    p.updatedAt = Date.now();
    dbPut('progress', p).catch(() => {});
    if (state.playedOnce) pushServerProgress(c.book.id, p, forcePush);
    if (rerender && !el.libraryView.hidden) renderLibrary();
    lastSaveAt = Date.now();
  }

  /** 标记某章已听完 */
  function markDone(book, i) {
    const p = ensureProgress(book.id);
    if (!p.done.includes(i)) p.done.push(i);
    p.updatedAt = Date.now();
    state.progress.set(book.id, p);
    dbPut('progress', p).catch(() => {});
    pushServerProgress(book.id, p, true);
  }

  /* ================= UI 同步 ================= */

  function updatePlayState() {
    const paused = el.audio.paused;
    document.body.classList.toggle('is-paused', paused);
  }

  function syncNowPlaying() {
    const c = state.current;
    if (!c) return;
    const { book, index } = c;
    const track = book.tracks[index];

    paintCover(el.miniCover, book, 1);
    paintCover(el.sheetCover, book, 6);
    el.miniTitle.textContent = book.title;
    el.miniTrack.textContent = `第 ${index + 1} 章 · ${track.title}`;
    el.sheetTitle.textContent = book.title;
    el.sheetTrack.textContent = `第 ${index + 1} / ${book.tracks.length} 章 · ${track.title}`;
    el.chapterVal.textContent = `${index + 1} / ${book.tracks.length}`;

    const cv = coverUrlOf(book);
    el.sheetBg.style.setProperty('--cover-img', cv ? `url("${cv}")` : 'none');

    updateMediaSession(book, track);
    updateTimeline();
  }

  function updateTimeline() {
    const a = el.audio;
    const cur = a.currentTime || 0;
    const dur = isFiniteNumber(a.duration) ? a.duration : 0;
    const pct = dur ? Math.min(100, (cur / dur) * 100) : 0;

    el.miniBar.style.width = pct.toFixed(2) + '%';
    if (!state.scrubbing) {
      el.seekBar.value = String(Math.round(pct * 10));
      el.seekBar.style.setProperty('--fill', pct.toFixed(2) + '%');
    }
    el.curTime.textContent = fmtTime(cur);
    el.durTime.textContent = dur ? fmtTime(dur) : '--:--';
  }

  function updateMediaSession(book, track) {
    if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return;
    try {
      let cover = coverUrlOf(book);
      // MediaMetadata 的 artwork 需要绝对 URL（安卓通知栏直接访问）
      if (cover && !/^(https?:|blob:|data:)/.test(cover)) {
        cover = new URL(cover, location.href).href;
      }
      const art = cover
        ? [{ src: cover, sizes: '512x512', type: 'image/*' }]
        : (bookIconDataUrl ? [{ src: bookIconDataUrl, sizes: '512x512', type: 'image/png' }] : []);
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title,
        artist: book.title,
        album: `第 ${state.current.index + 1} 章`,
        artwork: art,
      });
    } catch { /* 忽略 */ }
  }

  // 用 Canvas 画一个书籍图标，兜底给 MediaSession 用（安卓通知栏没有封面时显示默认音乐符号）
  function ensureBookIcon() {
    if (bookIconDataUrl) return;
    try {
      const c = document.createElement('canvas');
      c.width = c.height = 512;
      const ctx = c.getContext('2d');
      // 圆角底
      const r = 100;
      ctx.beginPath();
      ctx.moveTo(r, 0);
      ctx.arcTo(512, 0, 512, 512, r);
      ctx.arcTo(512, 512, 0, 512, r);
      ctx.arcTo(0, 512, 0, 0, r);
      ctx.arcTo(0, 0, 512, 0, r);
      ctx.closePath();
      const g = ctx.createLinearGradient(0, 0, 512, 512);
      g.addColorStop(0, '#d18a5a');
      g.addColorStop(1, '#9c4f2c');
      ctx.fillStyle = g;
      ctx.fill();
      // 摊开的书
      ctx.fillStyle = 'rgba(253,246,238,0.94)';
      ctx.beginPath();
      ctx.moveTo(150, 140);
      ctx.lineTo(290, 140);
      ctx.quadraticCurveTo(290, 200, 270, 200);
      ctx.lineTo(270, 330);
      ctx.lineTo(150, 330);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = 'rgba(255,248,240,0.8)';
      ctx.beginPath();
      ctx.moveTo(362, 140);
      ctx.lineTo(300, 140);
      ctx.quadraticCurveTo(300, 200, 320, 200);
      ctx.lineTo(320, 330);
      ctx.lineTo(362, 330);
      ctx.closePath();
      ctx.fill();
      // 播放三角
      ctx.fillStyle = '#9c4f2c';
      ctx.beginPath();
      ctx.moveTo(236, 210);
      ctx.lineTo(296, 248);
      ctx.lineTo(236, 286);
      ctx.closePath();
      ctx.fill();
      bookIconDataUrl = c.toDataURL('image/png');
    } catch { /* 画不出来就算了，用默认 */ }
  }

  function setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    const map = {
      play: () => doPlay(),
      pause: () => el.audio.pause(),
      stop: () => el.audio.pause(),
      previoustrack: () => prevTrack(),
      nexttrack: () => nextTrack(),
      seekbackward: (d) => { el.audio.currentTime = Math.max(0, el.audio.currentTime - (d.seekOffset || SEEK_STEP)); },
      seekforward: (d) => { el.audio.currentTime = Math.min(el.audio.duration || 1e9, el.audio.currentTime + (d.seekOffset || SEEK_STEP)); },
      seekto: (d) => { if (typeof d.seekTime === 'number') el.audio.currentTime = d.seekTime; },
    };
    Object.keys(map).forEach(k => {
      try { navigator.mediaSession.setActionHandler(k, map[k]); } catch { /* 不支持就算了 */ }
    });
  }

  /* ================= 倍速 ================= */

  function setRate(rate) {
    state.settings.rate = rate;
    el.audio.playbackRate = rate;
    el.audio.defaultPlaybackRate = rate;
    el.miniSpeed.textContent = rateLabel(rate);
    el.speedVal.textContent = rateLabel(rate);
    el.miniSpeed.classList.toggle('active', Math.abs(rate - 1) > 0.001);
    saveSettings();
  }

  function openSpeedPopover(anchor) {
    const rows = SPEEDS.map(r => `
      <button type="button" class="pop-row ${Math.abs(r - state.settings.rate) < 0.001 ? 'on' : ''}" data-rate="${r}">
        <span>${rateLabel(r)}</span><span class="pr-right">${r === 1 ? '正常' : r < 1 ? '慢' : ''}</span>
      </button>`).join('');
    showPopover(anchor, `<h4>播放倍速</h4>${rows}`, 176);
    $$('[data-rate]', el.popover).forEach(b => {
      b.addEventListener('click', () => {
        const r = parseFloat(b.dataset.rate);
        setRate(r);
        hidePopover();
        toast('倍速 ' + rateLabel(r), 1200);
      });
    });
  }

  /* ================= 定时关闭 ================= */

  const TIMER_OPTIONS = [15, 30, 45, 60, 90];

  function openTimerPopover(anchor) {
    const active = state.sleep.mode;
    const chapterRow = `
      <button type="button" class="pop-row ${active === 'chapter' ? 'on' : ''}" data-mode="chapter">
        <span>播完本章后停</span><span class="pr-right">${active === 'chapter' ? '已开启' : ''}</span>
      </button>`;
    const rows = TIMER_OPTIONS.map(m => {
      const on = active === 'time' && state.sleep.minutes === m;
      return `<button type="button" class="pop-row ${on ? 'on' : ''}" data-min="${m}">
        <span>${m} 分钟</span><span class="pr-right">${on ? '进行中' : ''}</span></button>`;
    }).join('');
    const finish = !!state.settings.sleepFinish;
    const finishRow = `<button type="button" class="pop-row ${finish ? 'on' : ''}" data-toggle="finish">
        <span>到点后听完本节</span><span class="pr-right">${finish ? '开' : '关'}</span></button>`;
    const offRow = `<button type="button" class="pop-row" data-mode="off">
        <span>关闭定时</span><span class="pr-right">${active === 'off' ? '未启用' : ''}</span></button>`;

    showPopover(anchor, `<h4>定时关闭</h4>${chapterRow}${rows}${finishRow}${offRow}`, 200);

    $$('[data-min]', el.popover).forEach(b => b.addEventListener('click', () => {
      setSleep('time', parseFloat(b.dataset.min));
      hidePopover();
    }));
    $$('[data-mode]', el.popover).forEach(b => b.addEventListener('click', () => {
      setSleep(b.dataset.mode);
      hidePopover();
    }));

    $$('[data-toggle]', el.popover).forEach(b => b.addEventListener('click', (e) => {
      e.stopPropagation();
      state.settings.sleepFinish = !state.settings.sleepFinish;
      saveSettings();
      if (state.sleep.mode === 'time') state.sleep.finishTrack = state.settings.sleepFinish;

      b.classList.toggle('on', state.settings.sleepFinish);
      const right = b.querySelector('.pr-right');
      if (right) right.textContent = state.settings.sleepFinish ? '开' : '关';

      toast(state.settings.sleepFinish ? '定时到点后，本节播完再停' : '定时到点立即暂停', 2200);
    }));
  }

  /** 定时状态。所有字段一次性给全，避免重置时丢字段。 */
  function sleepState(patch) {
    return { mode: 'off', endsAt: 0, minutes: 0, pending: false, finishTrack: false, ...patch };
  }

  function setSleep(mode, minutes = 0) {
    clearInterval(sleepTimer);
    clearInterval(fadeTimer);
    fadeTimer = null;

    if (mode === 'time') {
      const finishTrack = !!state.settings.sleepFinish;
      state.sleep = { mode: 'time', endsAt: Date.now() + minutes * 60000, minutes, finishTrack, pending: false };
      sleepTimer = setInterval(tickSleep, 500);
      if (state.current) recordPauseMark('timer-start');
      toast(finishTrack
        ? `${minutes} 分钟到点后，本节播完再暂停`
        : `将在 ${minutes} 分钟后暂停`);
    } else if (mode === 'chapter') {
      state.sleep = sleepState({ mode: 'chapter' });
      if (state.current) recordPauseMark('timer-start');
      toast('本章播完后自动暂停');
    } else {
      state.sleep = sleepState();
      toast('定时已关闭');
    }
    updateTimerUI();
  }

  function sleepRemaining() {
    return state.sleep.mode === 'time' ? Math.max(0, state.sleep.endsAt - Date.now()) : 0;
  }

  function tickSleep() {
    if (state.sleep.mode !== 'time') return;
    if (state.sleep.pending) return;            // 已经在等本节播完
    if (sleepRemaining() > 0) { updateTimerUI(); return; }

    // 到点了：勾了「听完本节」就不打断，等音频自己走完
    if (state.sleep.finishTrack) { armPendingPause(); return; }

    if (!fadeTimer) startFadeOut();
  }

  /* 定时到点，但先把当前音频播完再停 */
  function armPendingPause() {
    clearInterval(sleepTimer);
    clearInterval(fadeTimer);
    fadeTimer = null;

    if (el.audio.paused) {
      state.sleep = sleepState();
      updateTimerUI();
      toast('定时到点，当时没有在播放，已关闭');
      return;
    }

    state.sleep.pending = true;
    updateTimerUI();
    toast('定时已到，本节播完就暂停', 3200);
  }

  function startFadeOut() {
    const base = state.settings.volume;
    let step = 0;
    const total = 24;
    toast('时间到，准备暂停…', 1500);
    fadeTimer = setInterval(() => {
      step += 1;
      el.audio.volume = Math.max(0, base * (1 - step / total));
      if (step >= total) {
        clearInterval(fadeTimer);
        clearInterval(sleepTimer);
        fadeTimer = null;
        timerPausing = true;
        el.audio.pause();
        el.audio.volume = base;
        state.sleep = sleepState();
        updateTimerUI();
        toast('已按定时暂停');
      }
    }, 320);
  }

  function updateTimerUI() {
    const mode = state.sleep.mode;
    let label = '关闭';
    let badge = '';

    if (mode === 'time' && state.sleep.pending) {
      label = '本节后停';
      badge = '⏱ 本节播完后暂停';
      el.miniTimerLabel.textContent = '本节';
    } else if (mode === 'time') {
      const left = sleepRemaining();
      const m = Math.floor(left / 60000);
      const s = Math.floor((left % 60000) / 1000);
      label = `${m}:${String(s).padStart(2, '0')}`;
      badge = `⏱ ${label} 后暂停`;
      el.miniTimerLabel.textContent = label;
    } else if (mode === 'chapter') {
      label = '本章结束';
      badge = '⏱ 播完本章暂停';
      el.miniTimerLabel.textContent = '本章';
    } else {
      el.miniTimerLabel.textContent = '定时';
    }

    el.sheetBadge.hidden = !badge;
    if (badge) el.sheetBadge.textContent = badge;
    el.timerVal.textContent = label;
    el.timerBtn.classList.toggle('active', mode !== 'off');
    el.miniTimer.classList.toggle('active', mode !== 'off');
  }

  /* ================= 定时暂停记录 ================= */

  // 记录一次暂停位置。reason: 'pause' 手动/自动暂停、'timer' 定时到点、'timer-start' 开启定时
  function recordPauseMark(reason = 'pause') {
    const c = state.current;
    if (!c) return;
    const t = c.book.tracks[c.index];
    const time = Math.max(0, el.audio.currentTime || 0);
    if (time < 1 && reason !== 'timer-start') return;  // 刚开始就停了，不值得记（开启定时例外）

    const mark = {
      id: 'pm-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      bookId: c.book.id,
      bookTitle: c.book.title || c.book.folderTitle || '',
      index: c.index,
      trackTitle: t.title || t.name || `第 ${c.index + 1} 章`,
      time,
      reason,
      createdAt: Date.now(),
      // 来源标记：网页端记 'web'，安卓端记 'app'，暂停记录列表按它显示小图标
      source: 'web',
    };
    dbPut('pausemarks', mark).catch(() => {});
    pushServerPauseMarks();
  }

  async function loadPauseMarks(bookId) {
    let marks = [];
    try { marks = await dbAll('pausemarks'); } catch { marks = []; }
    marks = (marks || []).filter(m => !bookId || m.bookId === bookId);
    return marks.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  }

  function fmtStamp(ts) {
    const d = new Date(ts);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  const REASON_LABEL = {
    'pause': '暂停',
    'timer': '定时暂停',
    'timer-start': '开启定时',
  };

  /* 暂停记录来源：'app' 安卓端、'web' 网页端；1.6.0 之前的旧记录没有来源，按空处理 */
  const MARK_SOURCE_LABEL = { app: '移动端', web: '网页端' };
  const MARK_SOURCE_ICON = {
    app: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>',
    web: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4.5" width="18" height="12.5" rx="2"/><path d="M9 21h6M12 17v4"/></svg>',
  };

  function normalizeMarkSource(v) {
    return v === 'app' || v === 'web' ? v : '';
  }

  function markSourceBadge(source) {
    const key = normalizeMarkSource(source);
    if (!key) return null;
    const badge = document.createElement('span');
    badge.className = 'pm-src ' + key;
    badge.title = '来自' + (key === 'app' ? '安卓客户端' : '网页端');
    badge.setAttribute('aria-label', MARK_SOURCE_LABEL[key]);
    badge.innerHTML = MARK_SOURCE_ICON[key];
    return badge;
  }

  async function openPauseMarks(bookId) {
    openOverlay(el.pausemarksSheet);
    await renderPauseMarks(bookId);
  }

  function closePauseMarks() {
    closeOverlay(el.pausemarksSheet);
  }

  async function renderPauseMarks(bookId) {
    const marks = await loadPauseMarks(bookId);
    el.pmCount.textContent = marks.length ? `${marks.length} 条记录` : '';
    el.pmClearBtn.hidden = marks.length === 0;

    if (!marks.length) {
      el.pausemarksList.innerHTML = '<li class="pm-empty">还没有暂停记录。<br>播放中暂停、或开启定时，都会自动记一笔。</li>';
      return;
    }

    el.pausemarksList.replaceChildren();
    for (const m of marks) {
      const li = document.createElement('li');
      const item = document.createElement('button');
      item.className = 'pm-item';
      item.type = 'button';

      const main = document.createElement('span');
      main.className = 'pm-main';
      const title = document.createElement('div');
      title.className = 'pm-title';
      title.textContent = m.bookTitle;
      const sub = document.createElement('div');
      sub.className = 'pm-sub';
      sub.textContent = `${m.trackTitle} · ${fmtTime(m.time)} · ${REASON_LABEL[m.reason] || '暂停'} · ${fmtStamp(m.createdAt)}`;
      main.append(title, sub);

      const del = document.createElement('span');
      del.className = 'pm-del';
      del.title = '删除这条';
      del.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M10 7V5h4v2m-7 0 1 12h8l1-12M10 11v4M14 11v4"/></svg>';

      item.append(main, del);
      const badge = markSourceBadge(m.source);
      if (badge) item.insertBefore(badge, main);
      li.append(item);

      item.addEventListener('click', async () => {
        // 跳到对应位置继续播放
        const book = state.books.find(b => b.id === m.bookId);
        if (!book) { toast('这本书已不在书架'); return; }
        closePauseMarks();
        const idx = Math.min(m.index, book.tracks.length - 1);
        await playFrom(book, idx, { autoplay: true, time: m.time });
        openSheet();
      });

      del.addEventListener('click', (e) => {
        e.stopPropagation();
        deletePauseMark(m.id, bookId);
      });

      el.pausemarksList.append(li);
    }
  }

  async function deletePauseMark(id, bookId) {
    try { await dbDelete('pausemarks', id); } catch {}
    rememberRemoved([id]);
    pushServerPauseMarks();
    toast('已删除');
    await renderPauseMarks(bookId);
  }

  async function clearPauseMarks(bookId) {
    const marks = await loadPauseMarks(bookId);
    if (!marks.length) return;
    if (!window.confirm(`确定清空这${bookId ? '本书' : '全部'}的 ${marks.length} 条暂停记录？`)) return;
    for (const m of marks) {
      try { await dbDelete('pausemarks', m.id); } catch {}
    }
    rememberRemoved(marks.map((m) => m.id));
    pushServerPauseMarks();
    toast('已清空');
    await renderPauseMarks(bookId);
  }

  /* ================= 浮层 ================= */

  function showPopover(anchor, html, width) {
    el.popover.innerHTML = html;
    el.popover.style.width = (width || 180) + 'px';
    el.popover.hidden = false;

    const rect = anchor.getBoundingClientRect();
    const pr = el.popover.getBoundingClientRect();
    let left = rect.left + rect.width / 2 - pr.width / 2;
    left = Math.max(12, Math.min(window.innerWidth - pr.width - 12, left));
    let top = rect.top - pr.height - 10;
    if (top < 12) top = Math.min(window.innerHeight - pr.height - 12, rect.bottom + 10);
    el.popover.style.left = left + 'px';
    el.popover.style.top = top + 'px';
    popoverAnchor = anchor;
  }

  function hidePopover() {
    el.popover.hidden = true;
    popoverAnchor = null;
  }

  /* ================= 设置 ================= */

  /** 设置写盘做 300ms 防抖：拖音量条时不要每帧写一次 IndexedDB */
  function saveSettings() {
    clearTimeout(settingsSaveTimer);
    settingsSaveTimer = setTimeout(() => {
      dbPut('kv', state.settings, 'settings').catch(ignore);
    }, 300);
  }

  function applyTheme() {
    const t = state.settings.theme;
    const dark = t === 'dark' || (t === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#14120f' : '#f5f1ea');
  }

  function setVolume(v, quiet = false) {
    state.settings.volume = Math.max(0, Math.min(1, v));
    el.audio.volume = state.settings.volume;
    el.volumeBar.value = String(Math.round(state.settings.volume * 100));
    el.volumeBar.style.setProperty('--fill', (state.settings.volume * 100) + '%');
    saveSettings();
    if (!quiet) toast(`音量 ${Math.round(state.settings.volume * 100)}%`, 900);
  }

  /* ================= 播放器面板 ================= */

  function openSheet() {
    if (!state.current) return;
    openOverlay(el.sheet);
  }

  function closeSheet() {
    closeOverlay(el.sheet);
  }

  /* ================= 事件 ================= */

  async function fetchAuthStatus() {
    try {
      const res = await nativeFetch(AUTH_API + '?action=status&t=' + Date.now(), {
        cache: 'no-store', credentials: 'same-origin',
      });
      if (!res.ok) return null;
      return await res.json();
    } catch { return null; }
  }

  /** 登录 / 初始化管理员弹层。mode: 'login' | 'setup' */
  function showAuth(mode, message) {
    const setup = mode === 'setup';
    state.authShown = true;
    el.authTitle.textContent = setup ? '创建管理员' : '登录听书';
    el.authHint.textContent = setup ? '第一次使用，先创建一个管理员账号。' : '用管理员或普通用户账号登录。';
    el.authSubmit.textContent = setup ? '创建并进入' : '登录';
    el.authFoot.textContent = setup
      ? '管理员可以创建用户、上传/编辑/删除书籍；普通用户只能听书。'
      : '没有账号？请让管理员在「用户管理」里创建。';
    el.authForm.dataset.mode = setup ? 'setup' : 'login';
    el.authPass.autocomplete = setup ? 'new-password' : 'current-password';
    el.authError.hidden = !message;
    el.authError.textContent = message || '';
    openOverlay(el.authSheet);
    setTimeout(() => { try { el.authUser.focus({ preventScroll: true }); } catch { /* 忽略聚焦失败 */ } }, 100);
  }

  async function submitAuth(event) {
    event.preventDefault();
    const mode = el.authForm.dataset.mode || 'login';
    const u = el.authUser.value.trim();
    const p = el.authPass.value;
    if (!u || !p) {
      el.authError.hidden = false;
      el.authError.textContent = '请输入用户名和密码';
      return;
    }
    el.authSubmit.disabled = true;
    try {
      const res = await nativeFetch(AUTH_API + '?action=' + mode, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ u, p }).toString(),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok && data.ok) {
        location.reload();
        return;
      }
      if (mode === 'setup' && res.status === 403) {
        showAuth('login', '服务器已经初始化过了，请直接登录');
        return;
      }
      el.authError.hidden = false;
      el.authError.textContent = data.error || '登录失败，请重试';
    } catch {
      el.authError.hidden = false;
      el.authError.textContent = '连不上服务器，检查网络后重试';
    } finally {
      el.authSubmit.disabled = false;
    }
  }

  function applyPermissions() {
    const admin = isAdmin();
    if (el.pickBtn) el.pickBtn.hidden = !admin;
    if (el.emptyPickBtn && !admin) el.emptyPickBtn.hidden = true;
    if (el.editBookBtn) el.editBookBtn.hidden = !admin;
    if (el.bookDeleteBtn) el.bookDeleteBtn.hidden = !admin;
    if (el.accountUsersBtn) el.accountUsersBtn.hidden = !admin;
    if (el.backupBtn) el.backupBtn.hidden = !admin;
    if (el.userLabel) el.userLabel.textContent = state.user || '登录';
    document.body.classList.toggle('is-admin', admin);
    document.body.classList.toggle('is-user', !admin);
  }

  function openAccount() {
    if (!state.user) { showAuth('login'); return; }
    el.accountInfo.textContent =
      `当前账号：${state.user}\n角色：${isAdmin() ? '管理员' : '普通用户'}`;
    el.accountUsersBtn.hidden = !isAdmin();
    openOverlay(el.accountSheet);
  }

  async function openUsers() {
    if (!isAdmin()) return;
    closeOverlay(el.accountSheet);
    openOverlay(el.usersSheet);
    await loadUsers();
  }

  async function loadUsers() {
    el.userList.innerHTML = '<p class="muted tiny">加载中…</p>';
    try {
      const res = await fetch(USERS_API + '?t=' + Date.now(), { cache: 'no-store' });
      const data = await res.json();
      if (!data.ok) { el.userList.innerHTML = '<p class="muted tiny">加载失败</p>'; return; }
      state.users = data.users || [];
      renderUsers();
    } catch {
      el.userList.innerHTML = '<p class="muted tiny">连不上服务器</p>';
    }
  }

  function renderUsers() {
    el.userList.replaceChildren();
    if (!state.users.length) {
      const p = document.createElement('p');
      p.className = 'muted tiny';
      p.textContent = '还没有用户';
      el.userList.appendChild(p);
      return;
    }
    for (const u of state.users) {
      const row = document.createElement('div');
      row.className = 'user-row';

      const name = document.createElement('span');
      name.className = 'u-name';
      name.textContent = u.user;
      const role = document.createElement('span');
      role.className = 'u-role' + (u.role === 'admin' ? ' admin' : '');
      role.textContent = u.role === 'admin' ? '管理员' : '普通用户';
      const spacer = document.createElement('span');
      spacer.className = 'u-spacer';

      const reset = document.createElement('button');
      reset.className = 'btn ghost';
      reset.type = 'button';
      reset.textContent = '重置密码';
      reset.addEventListener('click', () => resetPassword(u.user));

      const del = document.createElement('button');
      del.className = 'btn ghost danger';
      del.type = 'button';
      del.textContent = '删除';
      del.disabled = u.user === state.user;
      del.title = u.user === state.user ? '不能删除当前账号' : '';
      del.addEventListener('click', () => deleteUser(u.user));

      row.append(name, role, spacer, reset, del);
      el.userList.appendChild(row);
    }
  }

  async function resetPassword(user) {
    const p = window.prompt(`给「${user}」设置新密码（至少 6 位）`);
    if (!p) return;
    try {
      const res = await fetch(USERS_API + '?action=reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ u: user, p }).toString(),
      });
      const data = await res.json();
      toast(data.ok ? '密码已重置' : (data.error || '重置失败'), 1800);
    } catch { toast('重置失败'); }
  }

  async function deleteUser(user) {
    if (!window.confirm(`确定删除用户「${user}」？他的听书记录和布局也会一起删除。`)) return;
    try {
      const res = await fetch(USERS_API + '?action=delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ u: user }).toString(),
      });
      const data = await res.json();
      if (data.ok) { toast('已删除'); await loadUsers(); }
      else toast(data.error || '删除失败', 2200);
    } catch { toast('删除失败'); }
  }

  async function createUser(event) {
    event.preventDefault();
    const u = el.newUserName.value.trim();
    const p = el.newUserPass.value;
    const role = el.newUserRole.value;
    if (!u || !p) { el.usersMsg.textContent = '用户名和密码都要填'; return; }
    try {
      const res = await fetch(USERS_API + '?action=create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ u, p, role }).toString(),
      });
      const data = await res.json();
      if (data.ok) {
        el.newUserName.value = '';
        el.newUserPass.value = '';
        el.usersMsg.textContent = '创建成功';
        await loadUsers();
      } else {
        el.usersMsg.textContent = data.error || '创建失败';
      }
    } catch { el.usersMsg.textContent = '创建失败'; }
  }

  async function changePassword() {
    const old = window.prompt('当前密码');
    if (!old) return;
    const p = window.prompt('新密码（至少 6 位）');
    if (!p) return;
    try {
      const res = await fetch(AUTH_API + '?action=password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ old, p }).toString(),
      });
      const data = await res.json();
      toast(data.ok ? '密码已修改' : (data.error || '修改失败'), 2000);
    } catch { toast('修改失败'); }
  }

  async function logout() {
    try {
      await nativeFetch(AUTH_API + '?action=logout', {
        method: 'POST', credentials: 'same-origin',
      });
    } catch { /* ignore */ }
    localStorage.removeItem('tingshu.user');
    location.reload();
  }

  /* ================= 事件绑定 =================
     按界面区域拆成若干 bindXxx()，便于定位；每个函数只负责自己那一块的事件。 */

  function bind() {
    bindAccountEvents();
    bindLibraryEvents();
    bindBookEvents();
    bindBookEditorEvents();
    bindBackupEvents();
    bindUploadEvents();
    bindCollectionEvents();
    bindPlaybackEvents();
    bindAudioEvents();
    bindGlobalEvents();
  }

  /* 账号 / 用户管理 / 每行本数 */
  function bindAccountEvents() {
    el.authForm.addEventListener('submit', submitAuth);
    el.userBtn.addEventListener('click', () => {
      if (state.user) openAccount();
      else showAuth('login');
    });
    el.accountClose.addEventListener('click', () => closeOverlay(el.accountSheet));
    el.accountSheet.addEventListener('click', (e) => {
      if (e.target === el.accountSheet) closeOverlay(el.accountSheet);
    });
    el.accountUsersBtn.addEventListener('click', () => { void openUsers(); });
    el.accountPasswordBtn.addEventListener('click', () => { void changePassword(); });
    el.accountLogoutBtn.addEventListener('click', () => { void logout(); });
    el.usersClose.addEventListener('click', () => closeOverlay(el.usersSheet));
    el.usersSheet.addEventListener('click', (e) => {
      if (e.target === el.usersSheet) closeOverlay(el.usersSheet);
    });
    el.newUserForm.addEventListener('submit', createUser);
    el.layoutBtn.addEventListener('click', () => {
      const steps = [0, 2, 3, 4, 5];
      const i = steps.indexOf(state.columns);
      state.columns = steps[(i + 1) % steps.length];
      applyLayout();
      void saveUserLayout();
    });
  }

  /* 书架：打开书库、搜索、排序、主题、卡片点击 */
  function bindLibraryEvents() {
    el.pickBtn.addEventListener('click', () => {
      if (state.source === 'net') openUploadSheet();
      else pickFolder();
    });
    el.emptyPickBtn.addEventListener('click', (e) => {
      const mode = e.currentTarget.dataset.mode;
      if (mode === 'restore' && state.pendingHandle) loadFromHandle(state.pendingHandle);
      else if (mode === 'reload') window.location.reload();
      else if (mode === 'upload') openUploadSheet();
      else pickFolder();
    });

    el.folderInput.addEventListener('change', (e) => {
      importFromInput(e.target.files);
      e.target.value = '';
    });

    el.themeBtn.addEventListener('click', () => {
      const i = THEMES.indexOf(state.settings.theme);
      state.settings.theme = THEMES[(i + 1) % THEMES.length];
      applyTheme();
      saveSettings();
      toast(THEME_LABELS[state.settings.theme] || THEME_LABELS.auto, 1100);
    });

    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (state.settings.theme === 'auto') applyTheme();
    });

    el.searchInput.addEventListener('input', (e) => {
      state.filter = e.target.value;
      renderLibrary();
    });

    el.sortBtn.addEventListener('click', () => {
      const i = SORTS.indexOf(state.settings.sort);
      state.settings.sort = SORTS[(i + 1) % SORTS.length];
      el.sortBtn.textContent = SORT_LABELS[state.settings.sort];
      saveSettings();
      renderLibrary();
    });

    el.bookGrid.addEventListener('click', (e) => {
      const editBtn = e.target.closest('.card-edit');
      if (editBtn) {
        const book = state.books.find(b => b.id === editBtn.dataset.edit);
        if (book) openEditor(book);
        return;
      }
      const card = e.target.closest('.book-card');
      if (!card) return;
      if (card.dataset.collection) {
        const col = state.collections.find(c => c.id === card.dataset.collection);
        if (col) openCollection(col);
        return;
      }
      const book = state.books.find(b => b.id === card.dataset.id);
      if (book) goBook(book);
    });

    el.bookGrid.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      const card = e.target.closest('.book-card');
      if (!card) return;
      e.preventDefault();
      card.click();
    });

    el.resumeHero.addEventListener('click', () => {
      const book = state.books.find(b => b.id === el.resumeHero.dataset.id);
      if (!book) return;
      if (state.current && state.current.book.id === book.id) {
        playOrExpand();
      } else {
        openBookAndPlay(book).then(openSheet);
      }
    });
  }

  /* 书籍详情：返回、播放、从头开始、简介展开 */
  function bindBookEvents() {
    el.backBtn.addEventListener('click', goLibrary);

    el.bookPlayBtn.addEventListener('click', () => {
      const book = state.bookView;
      if (!book) return;
      if (state.current && state.current.book.id === book.id) {
        if (bookStats(book).finished && el.audio.ended) { restartBook(book); return; }
        playOrExpand();
      } else {
        openBookAndPlay(book).then(openSheet);
      }
    });

    el.bookRestartBtn.addEventListener('click', () => {
      const book = state.bookView;
      if (!book) return;
      restartBook(book);
    });

    el.bookDesc.addEventListener('click', () => {
      if (!el.bookDesc.hidden) el.bookDesc.classList.toggle('open');
    });
  }

  /* 编辑书籍：书名、简介、配色、封面、裁切 */
  function bindBookEditorEvents() {
    el.editBookBtn.addEventListener('click', () => {
      if (state.bookView) openEditor(state.bookView);
    });
    el.editClose.addEventListener('click', closeEditor);
    el.editCancel.addEventListener('click', closeEditor);
    el.editSave.addEventListener('click', saveEditor);
    el.editSheet.addEventListener('click', (e) => {
      if (e.target === el.editSheet) closeEditor();
    });
    // 实时同步输入到 editState，避免手机输入法未提交导致保存时读到旧值
    el.editDesc.addEventListener('input', () => {
      editState.desc = el.editDesc.value;
      updateDescCount();
    });
    el.editTitle.addEventListener('input', () => {
      editState.title = el.editTitle.value;
      renderEditCover();
    });
    el.editTitle.addEventListener('change', renderEditCover);

    el.paletteRow.addEventListener('click', (e) => {
      const s = e.target.closest('.swatch');
      if (!s) return;
      const v = s.dataset.palette;
      editState.palette = v === '' ? null : parseInt(v, 10);
      if (v !== '') dropEditCover();
      renderPaletteRow();
      renderEditCover();
    });

    el.shuffleCoverBtn.addEventListener('click', () => {
      let i = Math.floor(Math.random() * PALETTES.length);
      if (PALETTES.length > 1 && i === editState.palette) i = (i + 1) % PALETTES.length;
      editState.palette = i;
      dropEditCover();
      renderPaletteRow();
      renderEditCover();
    });

    el.resetCoverBtn.addEventListener('click', () => {
      editState.palette = null;
      dropEditCover();
      renderPaletteRow();
      renderEditCover();
      toast('已恢复默认封面', 1400);
    });
  }

  /* 备份 / 暂停记录 / 版本信息 */
  function bindBackupEvents() {
    el.backupBtn.addEventListener('click', openBackupSheet);
    el.backupClose.addEventListener('click', closeBackupSheet);
    el.backupSheet.addEventListener('click', (e) => {
      if (e.target === el.backupSheet) closeBackupSheet();
    });

    /* ---- 服务器备份：模式 / 用户 / 下载 / 恢复 ---- */
    el.backupModes.addEventListener('change', (e) => {
      if (!e.target || e.target.name !== 'backupMode') return;
      backupMode = e.target.value;
      syncBackupModeUI();
      if (backupMode === 'user') loadBackupInfo();
    });
    el.backupUser.addEventListener('change', () => loadBackupInfo());
    el.backupDownloadBtn.addEventListener('click', downloadServerBackup);
    el.backupRestoreBtn.addEventListener('click', () => el.backupFile.click());
    el.backupFile.addEventListener('change', (e) => {
      const f = e.target.files && e.target.files[0];
      if (f) restoreServerBackup(f);
    });

    /* ---- 定时暂停记录 ---- */
    el.bookMarksBtn.addEventListener('click', () => {
      if (state.bookView) openPauseMarks(state.bookView.id);
    });
    el.pausemarksClose.addEventListener('click', closePauseMarks);
    el.pausemarksSheet.addEventListener('click', (e) => {
      if (e.target === el.pausemarksSheet) closePauseMarks();
    });
    el.pmClearBtn.addEventListener('click', () => {
      clearPauseMarks(state.bookView ? state.bookView.id : null);
    });

    /* ---- 点击首页品牌显示版本 ---- */
    el.brand.addEventListener('click', showVersionInfo);
    el.exportBtn.addEventListener('click', exportProgress);
    el.importBtn.addEventListener('click', () => el.progressFileInput.click());
    el.progressFileInput.addEventListener('change', async (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (f) await importProgressFile(f);
    });
  }

  /* 上传书籍：文件夹 / 压缩包 / 删除，以及封面选择与裁切 */
  function bindUploadEvents() {
    el.uploadClose.addEventListener('click', closeUploadSheet);
    el.uploadSheet.addEventListener('click', (e) => {
      if (e.target === el.uploadSheet) closeUploadSheet();
    });
    el.pickFolderBtn.addEventListener('click', () => el.uploadDirInput.click());
    el.pickArchiveBtn.addEventListener('click', () => el.uploadFileInput.click());
    el.uploadDirInput.addEventListener('change', async (e) => {
      const files = Array.from(e.target.files || []);
      e.target.value = '';
      if (files.length) await uploadFiles(files);
    });
    el.uploadFileInput.addEventListener('change', async (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (!f) return;
      if (/\.zip$/i.test(f.name)) await uploadZip(f);
      else await uploadArchive(f);
    });
    el.bookDeleteBtn.addEventListener('click', () => {
      if (editState.book) deleteBook(editState.book);
    });

    el.uploadCoverBtn.addEventListener('click', () => el.coverInput.click());
    el.coverInput.addEventListener('change', (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (!f) return;
      if (!/^image\//.test(f.type)) { toast('请选择图片文件'); return; }
      openCropper(f);
    });

    /* ---- 封面裁切 ---- */
    el.cropSave.addEventListener('click', applyCrop);
    el.cropCancel.addEventListener('click', closeCropper);
    el.cropClose.addEventListener('click', closeCropper);
    el.cropReset.addEventListener('click', resetCrop);
    el.cropZoom.addEventListener('input', () => setCropZoom(el.cropZoom.value));
    el.cropSheet.addEventListener('click', (e) => { if (e.target === el.cropSheet) closeCropper(); });

    el.cropViewport.addEventListener('pointerdown', (e) => {
      if (!cropState.img) return;
      el.cropViewport.setPointerCapture(e.pointerId);
      cropState.pointers.set(e.pointerId, cropPoint(e));
      if (cropState.pointers.size === 2) {
        const [a, b] = [...cropState.pointers.values()];
        cropState.pinch = {
          dist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
          zoom: cropState.zoom,
          cx: (a.x + b.x) / 2,
          cy: (a.y + b.y) / 2,
        };
      }
    });
    el.cropViewport.addEventListener('pointermove', (e) => {
      if (!cropState.img || !cropState.pointers.has(e.pointerId)) return;
      const prev = cropState.pointers.get(e.pointerId);
      const now = cropPoint(e);
      cropState.pointers.set(e.pointerId, now);
      if (cropState.pointers.size >= 2 && cropState.pinch) {
        const [a, b] = [...cropState.pointers.values()];
        const dist = Math.hypot(a.x - b.x, a.y - b.y);
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        cropState.zoom = Math.max(1, Math.min(4, cropState.pinch.zoom * (dist / cropState.pinch.dist)));
        cropState.ox += mid.x - cropState.pinch.cx;
        cropState.oy += mid.y - cropState.pinch.cy;
        cropState.pinch.cx = mid.x;
        cropState.pinch.cy = mid.y;
        el.cropZoom.value = String(cropState.zoom);
      } else {
        cropState.ox += now.x - prev.x;
        cropState.oy += now.y - prev.y;
      }
      clampCrop();
      drawCrop();
      e.preventDefault();
    });
    function endCropPointer(e) {
      cropState.pointers.delete(e.pointerId);
      if (cropState.pointers.size < 2) cropState.pinch = null;
    }
    el.cropViewport.addEventListener('pointerup', endCropPointer);
    el.cropViewport.addEventListener('pointercancel', endCropPointer);
    el.cropViewport.addEventListener('wheel', (e) => {
      if (!cropState.img) return;
      e.preventDefault();
      setCropZoom(cropState.zoom * Math.exp(-e.deltaY * 0.0015));
    }, { passive: false });
    window.addEventListener('resize', () => {
      if (!el.cropSheet.hidden) {
        initCropCanvas();
        clampCrop();
        drawCrop();
      }
    });
  }

  /* 合集：打开、排序、拖动、增删 */
  function bindCollectionEvents() {
    el.newCollectionBtn.addEventListener('click', () => openCollectionEditor(null, []));

    el.addToCollectionBtn.addEventListener('click', () => {
      if (state.bookView) openAddToCollection(state.bookView, el.addToCollectionBtn);
    });

    el.colCloseBtn.addEventListener('click', closeCollection);
    el.collectionSheet.addEventListener('click', (e) => {
      if (e.target === el.collectionSheet) closeCollection();
    });
    el.colSortBtn.addEventListener('click', () => {
      colState.sorting = !colState.sorting;
      renderCollectionView();
    });
    el.colSortDone.addEventListener('click', () => {
      colState.sorting = false;
      renderCollectionView();
    });
    el.colManageBtn.addEventListener('click', () => {
      const col = currentCollection();
      if (col) openCollectionEditor(col);
    });

    el.colGrid.addEventListener('click', (e) => {
      const tile = e.target.closest('.col-tile');
      if (!tile) return;
      const mv = e.target.closest('[data-move]');
      if (mv) {
        const idx = [...el.colGrid.children].indexOf(tile);
        moveColTile(idx, parseInt(mv.dataset.move, 10));
        return;
      }
      if (colState.sorting) return;
      const book = state.books.find(b => b.id === tile.dataset.book);
      if (book) { closeCollection(); goBook(book); }
    });

    el.colGrid.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      if (e.target.closest('[data-move]')) return;
      const tile = e.target.closest('.col-tile');
      if (!tile) return;
      e.preventDefault();
      tile.click();
    });

    el.colGrid.addEventListener('dragstart', (e) => {
      const tile = e.target.closest('.col-tile');
      if (!tile || !colState.sorting) { e.preventDefault(); return; }
      colState.dragEl = tile;
      tile.classList.add('dragging');
      if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move';
        try { e.dataTransfer.setData('text/plain', tile.dataset.book || ''); } catch { /* 忽略 */ }
      }
    });

    el.colGrid.addEventListener('dragover', (e) => {
      if (!colState.dragEl) return;
      e.preventDefault();
      if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
      const over = e.target.closest('.col-tile');
      if (!over || over === colState.dragEl) return;
      const rect = over.getBoundingClientRect();
      const after = (e.clientX - rect.left) > rect.width / 2;
      el.colGrid.insertBefore(colState.dragEl, after ? over.nextSibling : over);
    });

    el.colGrid.addEventListener('drop', (e) => e.preventDefault());
    el.colGrid.addEventListener('dragend', () => {
      if (colState.dragEl) colState.dragEl.classList.remove('dragging');
      colState.dragEl = null;
      persistDomOrder();
    });

    el.colEditClose.addEventListener('click', closeCollectionEditor);
    el.colEditCancel.addEventListener('click', closeCollectionEditor);
    el.colEditSave.addEventListener('click', saveCollectionEditor);
    el.colDelete.addEventListener('click', deleteCurrentCollection);
    el.colEditSheet.addEventListener('click', (e) => {
      if (e.target === el.colEditSheet) closeCollectionEditor();
    });
    el.colPickList.addEventListener('change', updatePickCount);
  }

  /* 章节列表与播放器控制 */
  function bindPlaybackEvents() {
    el.trackList.addEventListener('click', (e) => {
      const li = e.target.closest('.track-item');
      const book = state.bookView;
      if (!li || !book) return;
      const i = parseInt(li.dataset.i, 10);
      if (state.current && state.current.book.id === book.id && state.current.index === i) {
        togglePlay();
        renderTrackList(book);
      } else {
        playFrom(book, i, { time: 0 });
      }
    });

    el.trackList.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        const li = e.target.closest('.track-item');
        if (li) li.click();
      }
    });

    el.miniPlay.addEventListener('click', () => togglePlay());
    el.miniPrev.addEventListener('click', () => prevTrack());
    el.miniNext.addEventListener('click', () => nextTrack());
    el.expandBtn.addEventListener('click', openSheet);
    el.miniSpeed.addEventListener('click', () => {
      const i = SPEEDS.indexOf(state.settings.rate);
      const next = SPEEDS[(i + 1) % SPEEDS.length];
      setRate(next);
      toast('倍速 ' + rateLabel(next), 1100);
    });
    el.miniTimer.addEventListener('click', () => openTimerPopover(el.miniTimer));

    el.playBtn.addEventListener('click', togglePlay);
    el.prevBtn.addEventListener('click', prevTrack);
    el.nextBtn.addEventListener('click', () => nextTrack());
    el.back30.addEventListener('click', () => {
      el.audio.currentTime = Math.max(0, el.audio.currentTime - SEEK_STEP);
      toast(`后退 ${SEEK_STEP} 秒`, 900);
    });
    el.fwd30.addEventListener('click', () => {
      el.audio.currentTime = Math.min(el.audio.duration || 1e9, el.audio.currentTime + SEEK_STEP);
      toast(`前进 ${SEEK_STEP} 秒`, 900);
    });
    el.collapseBtn.addEventListener('click', closeSheet);
    el.sheet.addEventListener('click', (e) => {
      if (e.target === el.sheet || e.target === el.sheetBg) closeSheet();
    });
    el.speedBtn.addEventListener('click', () => openSpeedPopover(el.speedBtn));
    el.timerBtn.addEventListener('click', () => openTimerPopover(el.timerBtn));
    el.chapterBtn.addEventListener('click', () => {
      if (!state.current) return;
      closeSheet();
      goBook(state.current.book);
    });

    el.seekBar.addEventListener('pointerdown', () => { state.scrubbing = true; });
    el.seekBar.addEventListener('input', () => {
      const dur = el.audio.duration || 0;
      const pct = parseInt(el.seekBar.value, 10) / 10;
      el.seekBar.style.setProperty('--fill', pct + '%');
      if (dur) {
        el.audio.currentTime = (dur * pct) / 100;
        el.curTime.textContent = fmtTime(el.audio.currentTime);
      }
    });
    const endScrub = () => { state.scrubbing = false; saveProgress(); };
    el.seekBar.addEventListener('change', endScrub);
    el.seekBar.addEventListener('pointerup', endScrub);
    el.seekBar.addEventListener('pointercancel', endScrub);

    el.volumeBar.addEventListener('input', () => {
      setVolume(parseInt(el.volumeBar.value, 10) / 100, true);
    });
  }

  /* 音频元素本身的事件 */
  function bindAudioEvents() {
    el.audio.addEventListener('timeupdate', () => {
      updateTimeline();
      if (!el.audio.paused && Date.now() - lastSaveAt > SAVE_EVERY) saveProgress();
    });

    el.audio.addEventListener('play', () => {
      state.playedOnce = true;
      updatePlayState();
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
    });

    el.audio.addEventListener('pause', () => {
      updatePlayState();
      saveProgress(false, true);
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';

      // 正在等本节播完，但用户自己按了暂停 —— 目的已达到，收掉定时。
      // 注意：音频自然播完时浏览器会「先 pause 再 ended」，这里必须放过结尾那一刻，
      // 否则 ended 里就看不到 pending 了（会误切下一章）。
      const atEnd = el.audio.duration > 0 && el.audio.currentTime >= el.audio.duration - 0.3;
      if (state.sleep.mode === 'time' && state.sleep.pending && !el.audio.ended && !atEnd) {
        state.sleep = sleepState();
        updateTimerUI();
      }

      // 记录一次暂停（排除切章、以及音频自然播完前的 pause）
      if (!switchingTrack && !el.audio.ended && !atEnd) {
        recordPauseMark(timerPausing ? 'timer' : 'pause');
        timerPausing = false;
      }
    });

    el.audio.addEventListener('ended', () => {
      const c = state.current;
      if (!c) return;
      markDone(c.book, c.index);

      // 定时到点后一直在等这一刻：本节播完，不续下一节
      if (state.sleep.mode === 'time' && state.sleep.pending) {
        clearInterval(sleepTimer);
        clearInterval(fadeTimer);
        fadeTimer = null;
        state.sleep = sleepState();
        el.audio.pause();
        saveProgress(true, true);
        updateTimerUI();
        recordPauseMark('timer');
        toast('本节播完，已按定时暂停', 3200);
        refreshBookView();
        return;
      }

      if (state.sleep.mode === 'chapter') {
        setSleep('off');
        el.audio.pause();
        saveProgress(true, true);
        recordPauseMark('timer');
        toast('本章播完，已按定时暂停');
        refreshBookView();
        return;
      }

      if (c.index + 1 < c.book.tracks.length) {
        playFrom(c.book, c.index + 1, { time: 0 });
      } else {
        el.audio.pause();
        saveProgress(true, true);
        toast('全书听完，辛苦啦');
        renderLibrary();
      }
      refreshBookView();
    });

    el.audio.addEventListener('error', () => {
      if (!el.audio.src) return;
      toast('这一章无法播放，正在跳到下一章');
      setTimeout(() => nextTrack(false), 800);
    });

    el.audio.addEventListener('ratechange', () => {
      if (Math.abs(el.audio.playbackRate - state.settings.rate) > 0.001) {
        el.audio.playbackRate = state.settings.rate;
      }
    });
  }

  /* 全局：浮层收起、键盘快捷键、页面可见性、滚动 */
  function bindGlobalEvents() {
    document.addEventListener('click', (e) => {
      if (el.popover.hidden) return;
      if (el.popover.contains(e.target)) return;
      if (popoverAnchor && popoverAnchor.contains(e.target)) return;
      hidePopover();
    });

    document.addEventListener('keydown', (e) => {
      // 弹层打开时只处理 Esc，且按「最上层优先」的顺序关闭
      const stack = [
        ['cropSheet', closeCropper],
        ['backupSheet', closeBackupSheet],
        ['uploadSheet', closeUploadSheet],
        ['pausemarksSheet', closePauseMarks],
        ['usersSheet', () => closeOverlay(el.usersSheet)],
        ['accountSheet', () => closeOverlay(el.accountSheet)],
        ['colEditSheet', closeCollectionEditor],
        ['collectionSheet', closeCollection],
        ['editSheet', closeEditor],
        ['authSheet', null],   // 登录层不允许 Esc 关掉
      ];
      const opened = stack.filter(([key]) => el[key] && !el[key].hidden);
      if (opened.length) {
        if (e.key === 'Escape') {
          e.preventDefault();
          const handler = opened[0][1];
          if (handler) handler();
        }
        return;
      }

      const tag = e.target instanceof Element ? e.target.tagName.toLowerCase() : '';
      if (tag === 'input' || tag === 'textarea' || tag === 'select') {
        if (e.key === 'Escape') hidePopover();
        return;
      }
      if (!state.current) return;
      switch (e.key) {
        case ' ': e.preventDefault(); togglePlay(); break;
        case 'ArrowRight':
          e.preventDefault();
          el.audio.currentTime = Math.min(el.audio.duration || 1e9, el.audio.currentTime + (e.shiftKey ? 60 : 15));
          break;
        case 'ArrowLeft':
          e.preventDefault();
          el.audio.currentTime = Math.max(0, el.audio.currentTime - (e.shiftKey ? 60 : 15));
          break;
        case 'ArrowUp': e.preventDefault(); setVolume(state.settings.volume + 0.1); break;
        case 'ArrowDown': e.preventDefault(); setVolume(state.settings.volume - 0.1); break;
        case 'n': case 'N': nextTrack(); break;
        case 'p': case 'P': prevTrack(); break;
        case 'Escape':
          hidePopover();
          if (!el.sheet.hidden) closeSheet();
          break;
        default: break;
      }
    });

    window.addEventListener('beforeunload', () => {
      if (state.current) { saveProgress(); beaconProgress(); }
    });

    // 切后台存一次进度；回前台时补救被系统回收的音频源，顺便刷一次合集
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        if (state.current) saveProgress(true, true);
        return;
      }
      if (state.user) void pullServerCollections();
      // 回到前台也拉一次暂停记录：另一端刚记的能马上出现
      if (state.user) {
        void pullServerPauseMarks().then((n) => {
          if (n > 0 && !el.pausemarksSheet.hidden && state.bookView) void renderPauseMarks(state.bookView.id);
        });
      }

      // 安卓后台/锁屏可能把 blob 源回收掉：没源了就用存的 blob 重建，blob 也没了就重新拉章节
      const a = el.audio;
      if (!state.current) return;
      if (!a.src && currentBlob) {
        objectUrl = URL.createObjectURL(currentBlob);
        a.src = objectUrl;
        a.load();
      } else if (!a.src && !currentBlob) {
        reloadAndPlay();
      }
    });
    el.pageScroll.addEventListener('scroll', () => {
      document.body.classList.toggle('scrolled', el.pageScroll.scrollTop > 6);
    }, { passive: true });
    window.addEventListener('resize', () => { if (!el.popover.hidden) hidePopover(); });
  }

  /* ================= 启动 ================= */

  async function restoreSession() {
    try {
      const [progs, durs, settings, handle, cols] = await Promise.all([
        dbAll('progress'),
        dbAll('durations'),
        dbGet('kv', 'settings'),
        dbGet('kv', 'rootHandle'),
        dbAll('collections'),
      ]);

      (progs || []).forEach(p => { if (p && p.bookId) state.progress.set(p.bookId, p); });
      (durs || []).forEach(d => { if (d && d.key) state.durations.set(d.key, d.seconds); });
      if (settings) Object.assign(state.settings, settings);
      state.collections = (cols || [])
        .filter(c => c && c.id && Array.isArray(c.bookIds))
        .sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));

      if (handle) {
        let perm = 'denied';
        try { perm = await handle.queryPermission({ mode: 'read' }); } catch { /* 旧数据 */ }
        if (perm === 'granted') {
          const books = await scanLibrary(handle);
          await setLibrary(books);
        } else {
          state.pendingHandle = handle;
        }
      }
    } catch (err) {
      console.warn('恢复上次状态失败', err);
    }
  }

  async function afterAuth() {
    applyPermissions();

    const lastUser = localStorage.getItem('tingshu.user');
    if (!lastUser || lastUser !== state.user) await clearUserCaches();
    localStorage.setItem('tingshu.user', state.user);

    await loadUserLayout();

    // 服务器书库优先；连不上时保留本机文件夹模式
    const netBooks = await loadServerLibrary();
    if (netBooks) {
      state.pendingHandle = null;
      await setLibrary(netBooks);
    }

    // 从路由器拉一次进度 + 合集 + 书籍元数据，多端同步
    await pullServerProgress();
    await pullServerCollections();
    await pullServerMeta();
    await pullServerPauseMarks();

    applyTheme();
    applyLayout();
    renderLibrary();

    // 恢复上次听到的位置（不自动播放）
    const candidates = state.books.filter(b => state.progress.has(b.id));
    candidates.sort((a, b) => (progressOf(b.id).updatedAt || 0) - (progressOf(a.id).updatedAt || 0));
    const last = candidates[0];
    if (last) {
      const p = progressOf(last.id);
      const idx = Math.min(p.index || 0, last.tracks.length - 1);
      const done = new Set(p.done || []);
      await playFrom(last, idx, { autoplay: false, time: done.has(idx) ? 0 : p.time || 0 });
      el.audio.pause();
      updatePlayState();
    }
  }

  async function init() {
    await restoreSession();

    applyTheme();
    bind();
    setupMediaSession();
    ensureBookIcon();

    setRate(state.settings.rate);
    setVolume(state.settings.volume, true);
    updateTimerUI();
    updatePlayState();
    document.body.classList.toggle('mini-hidden', !state.current);
    el.sortBtn.textContent = SORT_LABELS[state.settings.sort] || SORT_LABELS.recent;
    applyLayout();

    const auth = await fetchAuthStatus();
    if (!auth) {
      showAuth('login', '连不上服务器，请检查地址或服务是否启动');
      return;
    }
    if (!auth.configured) {
      showAuth('setup');
      return;
    }
    if (!auth.authenticated) {
      showAuth('login');
      return;
    }

    state.user = auth.user;
    state.role = auth.role;
    applyPermissions();
    await afterAuth();
  }

  init();
})();
