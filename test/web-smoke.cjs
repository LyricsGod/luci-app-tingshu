/**
 * 网页端冒烟测试（jsdom）
 *
 * 用一份假的服务器数据把 app.js 完整跑一遍：登录态 → 书架渲染 → 主题/排序
 * → 弹层开关 → 书籍详情，确认重构后没有断链、没有未捕获异常。
 *
 * 运行：npm test
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
const appJs = fs.readFileSync(path.join(ROOT, 'app.js'), 'utf8');

const errors = [];
process.on('unhandledRejection', (e) => errors.push('unhandledRejection: ' + ((e && e.message) || e)));

const dom = new JSDOM(html, {
  url: 'http://127.0.0.1:8686/',
  runScripts: 'outside-only',
  pretendToBeVisual: true,
});
const w = dom.window;
w.addEventListener('error', (e) => errors.push('window.error: ' + ((e.error && e.error.message) || e.message)));

/* ---------------- 极简 IndexedDB ----------------
   jsdom 不带 IndexedDB，而 app.js 的进度/设置都走它。
   这里按用到的 API 做内存实现，让持久化路径也真正被执行。 */
function installFakeIDB(win) {
  const stores = new Map();

  const later = (fn) => {
    const req = {};
    setTimeout(() => {
      try {
        req.result = fn();
        if (req.onsuccess) req.onsuccess();
      } catch (err) {
        req.error = err;
        if (req.onerror) req.onerror();
      }
    }, 0);
    return req;
  };

  const storeApi = (name) => {
    const bucket = () => stores.get(name);
    return {
      get: (key) => later(() => bucket().data.get(key)),
      getAll: () => later(() => Array.from(bucket().data.values())),
      put: (value, key) => later(() => {
        const k = key !== undefined ? key : (bucket().keyPath ? value[bucket().keyPath] : undefined);
        bucket().data.set(k, value);
        return k;
      }),
      delete: (key) => later(() => { bucket().data.delete(key); }),
      clear: () => later(() => { bucket().data.clear(); }),
    };
  };

  win.indexedDB = {
    open() {
      const db = {
        objectStoreNames: { contains: (n) => stores.has(n) },
        createObjectStore: (n, opt) => {
          stores.set(n, { keyPath: opt && opt.keyPath, data: new Map() });
        },
        transaction: (n) => ({ objectStore: () => storeApi(n) }),
        close() {},
      };
      const req = {};
      setTimeout(() => {
        req.result = db;
        if (req.onupgradeneeded) req.onupgradeneeded();
        if (req.onsuccess) req.onsuccess();
      }, 0);
      return req;
    },
  };
}

installFakeIDB(w);

/* ---------------- 假的服务器 ----------------
   cgi-ts/* 与 library.json 都返回固定数据，和真实 CGI 的字段保持一致。 */
const hex = (s) => Buffer.from(s, 'utf8').toString('hex');

const BOOKS = {
  root: '/mnt/books',
  books: [
    {
      id: 'santi',
      title: '三体',
      tracks: [
        { title: '01 地球往事', path: 'santi/01.mp3' },
        { title: '02 红岸', path: 'santi/02.mp3' },
      ],
    },
    {
      id: 'ming',
      title: '明朝那些事儿',
      tracks: [
        { title: '01', path: 'ming/01.mp3' },
        { title: '02', path: 'ming/02.mp3' },
        { title: '03. 龙岭迷窟 03', path: 'ming/03.mp3' },
      ],
    },
  ],
};

const routes = {
  'cgi-ts/auth': { ok: true, configured: true, authenticated: true, user: 'admin', role: 'admin' },
  'cgi-ts/layout': { ok: true, layout: { columns: 3 } },
  'cgi-ts/progress': {
    ok: true,
    items: { [hex('net:santi')]: { index: 1, time: 42, done: [0], updatedAt: 1700000000000 } },
  },
  'cgi-ts/collections': {
    ok: true,
    data: [{ id: 'c1', name: '科幻', bookIds: ['net:santi'], createdAt: 1, updatedAt: 1 }],
  },
  'cgi-ts/meta': { ok: true, items: {} },
  'cgi-ts/pausemarks': { ok: true, items: [] },
  'cgi-ts/backup': {
    ok: true, users: ['liuyu', 'test'], booksDir: '/mnt/audiobooks', freeKb: 1024000,
    sizes: { user: 12, users: 24, meta: 1024, media: 36657156, full: 36658192 },
  },
  'library.json': BOOKS,
};

/* 记录推送出去的请求体，用来核对数据字段（例如暂停记录的来源标记） */
const pushedBodies = [];
const progressPushes = [];
const requests = [];

w.fetch = (input, init) => {
  const url = typeof input === 'string' ? input : (input && input.url) || '';
  requests.push({ url, init: init || null });
  if (init && init.body && typeof init.body === 'string' && url.includes('cgi-ts/pausemarks')) {
    try { pushedBodies.push(JSON.parse(init.body)); } catch { /* 忽略 */ }
  }
  if (init && init.body && typeof init.body === 'string' && url.includes('cgi-ts/progress')) {
    progressPushes.push(init.body);
  }
  const key = Object.keys(routes).find((k) => url.startsWith(k));
  const body = key ? routes[key] : { ok: true };
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    blob: () => Promise.resolve(new w.Blob(['fake-audio'])),
  });
};

/* jsdom 没实现的浏览器能力，按需打桩 */
w.matchMedia = (query) => ({
  matches: false,
  media: query,
  addEventListener() {},
  removeEventListener() {},
});
w.URL.createObjectURL = () => 'blob:fake-' + Math.random().toString(36).slice(2);
w.URL.revokeObjectURL = () => {};
w.scrollTo = () => {};
/* jsdom 没带 TextEncoder / TextDecoder，播放路径要给书籍 id 转十六进制 */
w.TextEncoder = TextEncoder;
w.TextDecoder = TextDecoder;
w.HTMLCanvasElement.prototype.getContext = () => null;
w.HTMLMediaElement.prototype.play = function () { return Promise.resolve(); };
w.HTMLMediaElement.prototype.pause = function () {};
w.HTMLMediaElement.prototype.load = function () {};
Object.defineProperty(w.HTMLElement.prototype, 'clientWidth', { get() { return 300; } });
Object.defineProperty(w.HTMLElement.prototype, 'clientHeight', { get() { return 300; } });

/* ---------------- 断言 ---------------- */
const $ = (s) => w.document.querySelector(s);
const $$ = (s) => Array.from(w.document.querySelectorAll(s));
const click = (el) => el.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
const press = (key) => w.document.dispatchEvent(new w.KeyboardEvent('keydown', { key, bubbles: true }));

const results = [];
const check = (name, cond) => results.push([cond ? 'PASS' : 'FAIL', name]);

(async () => {
  try {
    w.eval(appJs);
  } catch (err) {
    errors.push('eval: ' + err.message);
  }
  await new Promise((r) => setTimeout(r, 500));

  check('书架渲染出 2 张卡片（1 合集 + 1 书）', $$('.book-card').length === 2);
  check('合集卡片使用九宫格封面', !!$('.collection-card .mosaic'));
  check('登录用户显示为 admin', $('#userLabel').textContent === 'admin');
  check('按服务器布局显示每行 3 本', $('#bookGrid').style.gridTemplateColumns.includes('3'));
  check('继续收听卡片出现', !$('#resumeHero').hidden);
  check('书架计数包含合集数量', /合集/.test($('#shelfCount').textContent));
  check('进度写入了 localStorage 用户标记', w.localStorage.getItem('tingshu.user') === 'admin');

  click($('#themeBtn'));
  check('主题按钮切换 data-theme', ['dark', 'light', 'auto'].includes(w.document.documentElement.dataset.theme));

  const beforeSort = $('#sortBtn').textContent;
  click($('#sortBtn'));
  check('排序按钮文案会变化', $('#sortBtn').textContent !== beforeSort);

  click($('#newCollectionBtn'));
  check('新建合集弹层打开', !$('#colEditSheet').hidden);
  press('Escape');
  check('Esc 关闭新建合集弹层', $('#colEditSheet').hidden);

  click($('#pickBtn'));
  check('服务器模式下打开上传弹层', !$('#uploadSheet').hidden);
  press('Escape');
  check('Esc 关闭上传弹层', $('#uploadSheet').hidden);

  const card = $$('.book-card').find((c) => c.dataset.id === 'net:ming');
  check('找到《明朝那些事儿》卡片', !!card);
  click(card);
  check('进入书籍详情', !$('#bookView').hidden && $('#libraryView').hidden);
  check('章节列表渲染 3 条', $$('#trackList .track-item').length === 3);
  check('书名显示正确', $('#bookTitle').textContent === '明朝那些事儿');

  /* ---- 暂停记录：来源标记（网页端记 web，列表里显示小图标） ---- */
  click($$('#trackList .track-item')[0]);
  // 切换章节后 600ms 内触发的 pause 会被当成切章忽略，等它过去再模拟暂停
  await new Promise((r) => setTimeout(r, 700));
  const audio = $('#audio');
  Object.defineProperty(audio, 'currentTime', { configurable: true, get: () => 42 });
  Object.defineProperty(audio, 'duration', { configurable: true, get: () => 300 });
  audio.dispatchEvent(new w.Event('play'));
  audio.dispatchEvent(new w.Event('pause'));
  await new Promise((r) => setTimeout(r, 80));
  click($('#bookMarksBtn'));
  await new Promise((r) => setTimeout(r, 60));
  check('暂停记录列表有 1 条', $$('#pausemarksList .pm-item').length === 1);
  check('记录前面有来源小图标', $$('#pausemarksList .pm-src').length === 1);
  check('网页端记录标记为 web', !!$('#pausemarksList .pm-src.web'));
  await new Promise((r) => setTimeout(r, 600));
  const pmPush = (pushedBodies[pushedBodies.length - 1] || {}).items || [];
  check('推送的数据带上 source=web', pmPush.some((m) => m && m.source === 'web'));
  const localMarkId = (pmPush[0] || {}).id;

  /* ---- 云端合并：另一端记的会并进来，删除墓碑能删掉本地那份 ---- */
  const remoteMark = {
    id: 'pm-remote-1', bookId: 'net:ming', bookTitle: '明朝那些事儿', index: 2,
    trackTitle: '龙岭迷窟 03', time: 9, reason: 'pause', createdAt: Date.now() + 1000, source: 'app',
  };
  routes['cgi-ts/pausemarks'] = { ok: true, items: [remoteMark], removed: {} };
  w.document.dispatchEvent(new w.Event('visibilitychange'));
  await new Promise((r) => setTimeout(r, 150));
  click($('#bookMarksBtn'));
  await new Promise((r) => setTimeout(r, 60));
  check('另一端的记录会并进来', $$('#pausemarksList .pm-item').length === 2);
  check('并进来的记录显示移动端图标', $$('#pausemarksList .pm-src.app').length === 1);
  const mergedSub = $$('#pausemarksList .pm-item')[0].querySelector('.pm-sub').textContent;
  check('章节号按网页端的短格式显示', mergedSub.startsWith('03 ·'));

  routes['cgi-ts/pausemarks'] = {
    ok: true,
    items: [remoteMark],
    removed: { [localMarkId]: Date.now() + 5000 },
  };
  w.document.dispatchEvent(new w.Event('visibilitychange'));
  await new Promise((r) => setTimeout(r, 150));
  click($('#bookMarksBtn'));
  await new Promise((r) => setTimeout(r, 60));
  check('服务器上的删除墓碑会删掉本地记录', $$('#pausemarksList .pm-item').length === 1);

  /* ---- 删除：删掉的记录不能被 push 前的拉取又并回来 ---- */
  click($('#pausemarksList .pm-del'));
  await new Promise((r) => setTimeout(r, 900));
  const delPush = pushedBodies[pushedBodies.length - 1] || {};
  check('删除后推送的记录里没有这条', (delPush.items || []).every((m) => m.id !== 'pm-remote-1'));
  check('删除写进了墓碑', !!(delPush.removed || {})['pm-remote-1']);
  w.document.dispatchEvent(new w.Event('visibilitychange'));
  await new Promise((r) => setTimeout(r, 150));
  click($('#bookMarksBtn'));
  await new Promise((r) => setTimeout(r, 60));
  check('删掉的记录不会被拉取又并回来', $$('#pausemarksList .pm-item').length === 0);

  press('Escape');
  check('Esc 关闭暂停记录', $('#pausemarksSheet').hidden);

  /* ---- 全本已读 / 全本未读 ---- */
  const tools = $('#trackTools');
  check('目录工具条有「全本已读 / 全本未读 / 多选」', !!tools.querySelector('[data-act="readAll"]')
    && !!tools.querySelector('[data-act="unreadAll"]') && !!tools.querySelector('[data-act="selStart"]'));

  click(tools.querySelector('[data-act="readAll"]'));
  await new Promise((r) => setTimeout(r, 80));
  // 当前播放的那章显示 ♪，所以「已读」要看 done / playing 两种标记
  const marked = (i) => {
    const li = $$('#trackList .track-item')[i];
    return !!li && (li.classList.contains('done') || li.classList.contains('playing'));
  };
  check('全本已读后 3 章都带已读标记', marked(0) && marked(1) && marked(2));
  check('全本已读会推送进度到服务器', /d=0,1,2(?:&|$)/.test(progressPushes[progressPushes.length - 1] || ''));
  check('全本已读后进度显示已听完', /已听完/.test($('#bookPct').textContent));
  check('全本已读后播放按钮变成重新收听', $('#bookPlayLabel').textContent === '重新收听');

  w.confirm = () => true;
  click(tools.querySelector('[data-act="unreadAll"]'));
  await new Promise((r) => setTimeout(r, 80));
  check('全本未读后没有已读章节', !marked(1) && !marked(2));
  check('全本未读会推送空的 done', /d=(?:&|$)/.test(progressPushes[progressPushes.length - 1] || ''));
  check('全本未读后进度回到 0%', /^0%/.test($('#bookPct').textContent));

  /* ---- 章节多选：勾几章批量标已读 / 未读 ---- */
  click(tools.querySelector('[data-act="selStart"]'));
  check('进入多选模式后每章前面有勾选框', $$('#trackList .track-item .t-check').length === 3);
  let rows = $$('#trackList .track-item');
  const srcBefore = $('#audio').src;
  click(rows[0]);
  click(rows[2]);
  check('点章节只是勾选，不会开始播放', $('#audio').src === srcBefore);
  check('工具条显示已选 2 章', /已选 2 \/ 3/.test(tools.textContent));
  check('勾中的章节有选中样式', $$('#trackList .track-item.selected').length === 2);

  click(tools.querySelector('[data-act="selRead"]'));
  await new Promise((r) => setTimeout(r, 80));
  check('多选标已读只影响勾中的章节', /d=0,2(?:&|$)/.test(progressPushes[progressPushes.length - 1] || ''));
  check('标完已读会退出多选模式', $$('#trackList .track-item .t-check').length === 0);
  check('批量标已读后勾中的章节显示已读', marked(0) && marked(2) && !marked(1));

  click(tools.querySelector('[data-act="selStart"]'));
  rows = $$('#trackList .track-item');
  click(rows[0]);
  click(tools.querySelector('[data-act="selUnread"]'));
  await new Promise((r) => setTimeout(r, 80));
  check('多选标未读会去掉勾中的章节', /d=2(?:&|$)/.test(progressPushes[progressPushes.length - 1] || ''));
  check('标未读后另一章还是已读', !marked(1) && marked(2));

  click(tools.querySelector('[data-act="selStart"]'));
  click(tools.querySelector('[data-act="selAll"]'));
  check('全选按钮选中全部章节', $$('#trackList .track-item.selected').length === 3);
  click(tools.querySelector('[data-act="selExit"]'));
  check('点完成退出多选模式', $$('#trackList .track-item .t-check').length === 0);

  click($('#backBtn'));
  check('返回书架', $('#bookView').hidden && !$('#libraryView').hidden);

  /* ---- 顶栏刷新按钮：重新拉书库 + 暂停记录 ---- */
  check('顶栏有刷新按钮', !!$('#refreshBtn'));
  const libBefore = requests.filter((r) => r.url.startsWith('library.json')).length;
  const pmBefore = requests.filter((r) => r.url.includes('cgi-ts/pausemarks')).length;
  click($('#refreshBtn'));
  await new Promise((r) => setTimeout(r, 400));
  check('刷新按钮重新拉了书库', requests.filter((r) => r.url.startsWith('library.json')).length > libBefore);
  check('刷新按钮重新拉了暂停记录', requests.filter((r) => r.url.includes('cgi-ts/pausemarks')).length > pmBefore);

  /* ---- 服务器备份：管理员权限 + 5 种模式 ---- */
  check('管理员可见备份入口', !$('#backupBtn').hidden);
  click($('#backupBtn'));
  check('备份弹层打开', !$('#backupSheet').hidden);
  check('备份模式有 5 种', $$('#backupModes input[name="backupMode"]').length === 5);
  await new Promise((r) => setTimeout(r, 20));
  check('用户下拉已填充', $('#backupUser').options.length === 2);
  check('显示备份体积', /预计/.test($('#backupSize').textContent));
  const mediaRadio = $$('#backupModes input').find((i) => i.value === 'media');
  click(mediaRadio);
  check('切到媒体库后隐藏用户选择', $('#backupUserRow').hidden);
  press('Escape');
  check('Esc 关闭备份弹层', $('#backupSheet').hidden);

  /* ---- 上传进度里的体积单位（字节不能当 KB 用，否则 3 MB 会显示成 3 GB） ---- */
  click($('#pickBtn'));
  check('上传弹层能再次打开', !$('#uploadSheet').hidden);

  const statuses = [];
  const statusEl = $('#uploadStatus');
  const nodeDesc = Object.getOwnPropertyDescriptor(w.Node.prototype, 'textContent');
  Object.defineProperty(statusEl, 'textContent', {
    configurable: true,
    get() { return nodeDesc.get.call(this); },
    set(v) { statuses.push(String(v)); nodeDesc.set.call(this, v); },
  });

  const folderFile = new w.File([new Uint8Array(3 * 1024 * 1024)], 'a.mp3', { type: 'audio/mpeg' });
  Object.defineProperty(folderFile, 'webkitRelativePath', { value: '测试书/a.mp3' });
  const dirInput = $('#uploadDirInput');
  Object.defineProperty(dirInput, 'files', { value: [folderFile], configurable: true });
  dirInput.dispatchEvent(new w.Event('change', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 500));
  check('文件夹上传按 MB 显示已传体积', statuses.some((t) => /正在上传 1\/1 · 3\.0 MB/.test(t)));
  check('文件夹上传不会把 3 MB 显示成 GB', !statuses.some((t) => /GB/.test(t)));

  click($('#pickBtn'));
  const archiveFile = new w.File([new Uint8Array(5 * 1024 * 1024)], 'book.tar.gz', { type: 'application/gzip' });
  const archiveInput = $('#uploadFileInput');
  Object.defineProperty(archiveInput, 'files', { value: [archiveFile], configurable: true });
  archiveInput.dispatchEvent(new w.Event('change', { bubbles: true }));
  check('压缩包上传按 MB 显示体积', /book\.tar\.gz（5\.0 MB）/.test($('#uploadStatus').textContent));
  await new Promise((r) => setTimeout(r, 300));

  check('没有未捕获异常', errors.length === 0);

  for (const [state, name] of results) console.log(state.padEnd(5), name);
  if (errors.length) {
    console.log('\n错误:');
    errors.forEach((e) => console.log(' -', e));
  }
  const passed = results.filter((r) => r[0] === 'PASS').length;
  console.log(`\n总计 ${passed}/${results.length}`);
  process.exit(passed === results.length ? 0 : 1);
})();
