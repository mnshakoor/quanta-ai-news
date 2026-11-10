/* Quanta AI News client. Vanilla JS, hash-routed single-page app. */
(() => {
  'use strict';

  // ---------------------------------------------------------------- utilities
  const $ = (sel, el = document) => el.querySelector(sel);
  const view = $('#view');
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const safeUrl = (u) => { try { const x = new URL(u, location.href); return /^https?:$/.test(x.protocol) ? x.href : '#'; } catch { return '#'; } };
  const cssUrl = (u) => `url("${safeUrl(u).replace(/["\\\n\r]/g, '')}")`;
  const BIAS_COLOR = { 'Left': 'var(--left)', 'Lean Left': 'var(--lean-left)', 'Center': 'var(--center)', 'Lean Right': 'var(--lean-right)', 'Right': 'var(--right)' };
  const pillClass = (b) => 'pill pill-' + String(b).replace(/\s+/g, '-');
  const initials = (n) => n.replace(/^The\s+/i, '').split(/\s+/).map((w) => w[0]).join('').slice(0, 2).toUpperCase();

  function ago(iso) {
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    if (s < 86400) return Math.floor(s / 3600) + 'h ago';
    return Math.floor(s / 86400) + 'd ago';
  }

  // Data layer. With the Java server running, calls go to its live API. On static hosting
  // (GitHub Pages) the API is absent, so the same calls are answered from the JSON snapshot
  // that the scheduled build publishes under data/.
  let mode = null;            // 'api' | 'static'
  let staticList = null;      // cached data/stories.json
  async function getJson(url, opts) {
    const r = await fetch(url, opts);
    const body = await r.json().catch(() => null);
    if (!r.ok || body === null) throw Object.assign(new Error((body && body.error) || ('HTTP ' + r.status)), { status: r.status });
    return body;
  }
  async function detectMode() {
    if (mode) return mode;
    try { await getJson('api/health'); mode = 'api'; } catch { mode = 'static'; }
    return mode;
  }
  async function api(path, opts) {
    if ((await detectMode()) === 'api') return getJson('api' + path, opts);
    const [route, qs] = path.split('?');
    const q = new URLSearchParams(qs || '');
    if (route === '/meta') {
      const m = await getJson('data/meta.json?t=' + Date.now());
      if (staticList && staticList.refreshedAt !== m.refreshedAt) staticList = null; // new snapshot published
      return m;
    }
    if (route === '/sources') return getJson('data/sources.json');
    if (route.startsWith('/stories/')) {
      const id = route.slice('/stories/'.length);
      if (!/^[A-Za-z0-9]+$/.test(id)) throw new Error('Story not found.');
      try { return await getJson('data/stories/' + id + '.json'); }
      catch { throw new Error('Story not found. It may have aged out of the current news cycle.'); }
    }
    if (route === '/stories') {
      if (!staticList) staticList = { ...(await getJson('data/stories.json')), refreshedAt: state.meta?.refreshedAt };
      return filterStatic(staticList.items, q);
    }
    if (route === '/refresh') throw new Error('Refresh runs on a schedule in static hosting.');
    throw new Error('Unknown endpoint');
  }
  // Mirrors Views.listStories on the server.
  function filterStatic(all, q) {
    const view = q.get('view') || 'top', bias = q.get('bias') || 'all';
    const cat = (q.get('category') || '').toLowerCase(), text = (q.get('q') || '').toLowerCase().trim();
    const ids = q.get('ids'), limit = Math.min(200, Math.max(1, parseInt(q.get('limit') || '60', 10)));
    let list = all.slice();
    if (ids) { const want = new Set(ids.split(',')); list = list.filter((s) => want.has(s.id)); }
    if (view === 'blindspot') list = list.filter((s) => s.blindspot);
    if (view === 'polarized') list = list.filter((s) => s.polarized);
    if (view === 'latest') list.sort((a, b) => new Date(b.lastUpdated) - new Date(a.lastUpdated));
    if (bias !== 'all') list = list.filter((s) => s.counts[bias] > 0);
    if (cat) list = list.filter((s) => s.category.toLowerCase() === cat);
    if (text) list = list.filter((s) => (s.title + ' ' + s.summary + ' ' + s.outlets.map((o) => o.name).join(' ')).toLowerCase().includes(text));
    return { total: list.length, items: list.slice(0, limit) };
  }

  const store = {
    get(k, d) { try { const v = localStorage.getItem('qan.' + k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem('qan.' + k, JSON.stringify(v)); } catch { /* storage unavailable */ } }
  };

  const ICON = {
    eye: '<svg viewBox="0 0 24 24"><path d="M9.9 4.2A10.9 10.9 0 0 1 12 4c7 0 10 8 10 8a17.6 17.6 0 0 1-2.2 3.4M6.6 6.6A17.4 17.4 0 0 0 2 12s3 8 10 8a10.6 10.6 0 0 0 5.4-1.4"/><path d="m2 2 20 20"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/></svg>',
    flame: '<svg viewBox="0 0 24 24"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3.3.4 1.4 1.2 2.4 2.5 2.8z"/></svg>',
    bookmark: '<svg viewBox="0 0 24 24"><path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>',
    link: '<svg viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></svg>',
    back: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>',
    refresh: '<svg viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/></svg>'
  };

  // ---------------------------------------------------------------- app state
  const state = { meta: null, category: '', sort: 'top', bias: 'all', lastRefreshSeen: null };
  const saved = () => new Set(store.get('saved', []));
  function toggleSaved(id) {
    const s = saved();
    s.has(id) ? s.delete(id) : s.add(id);
    store.set('saved', [...s]);
    return s.has(id);
  }

  // ---------------------------------------------------------------- shared renderers
  function biasBar(p, large) {
    const seg = (cls, v, label) => v > 0 ? `<span class="${cls}" style="width:${v}%">${large && v >= 9 ? label + ' ' + v + '%' : ''}</span>` : '';
    return `<div class="bias-bar${large ? ' lg' : ''}" role="img" aria-label="Coverage: ${p.left}% left, ${p.center}% center, ${p.right}% right">
      ${seg('l', p.left, 'L')}${seg('c', p.center, 'C')}${seg('r', p.right, 'R')}</div>`;
  }

  function tags(s) {
    let h = '';
    if (s.blindspot) h += `<span class="tag tag-blind" title="Blindspot rule: see Method">${ICON.eye}Blindspot: ${s.blindspot === 'right' ? 'Right' : 'Left'}</span>`;
    if (s.polarized) h += `<span class="tag tag-pol">${ICON.flame}Polarized</span>`;
    if (s.hasSummary) h += '<span class="tag tag-ai" title="Has an AI summary">AI</span>';
    return h;
  }

  function thumb(s, cls = 'thumb') {
    return s.image
      ? `<div class="${cls}" style="background-image:${esc(cssUrl(s.image))}"></div>`
      : `<div class="${cls} ph" data-cat="${esc(s.category)}"></div>`;
  }

  function saveBtn(id) {
    const on = saved().has(id);
    return `<button class="save-btn${on ? ' on' : ''}" data-save="${esc(id)}" aria-pressed="${on}" title="${on ? 'Remove from saved' : 'Save story'}">${ICON.bookmark}</button>`;
  }

  function outletChips(s, max = 5) {
    const list = s.outlets.slice(0, max).map((o) => `<span class="chip" title="${esc(o.bias)}"><span class="sw" style="background:${BIAS_COLOR[o.bias]}"></span>${esc(o.name)}</span>`).join('');
    const more = s.outlets.length > max ? `<span class="chip">+${s.outlets.length - max}</span>` : '';
    return list + more;
  }

  function storyCard(s) {
    return `<article class="card story">
      <div>
        <div class="meta-row"><span class="cat">${esc(s.category)}</span><span>${ago(s.lastUpdated)}</span>${tags(s)}</div>
        <h2><a href="#/story/${esc(s.id)}">${esc(s.title)}</a></h2>
        <p class="summary">${esc(s.summary)}</p>
        ${biasBar(s.pct)}
        <div class="bias-legend"><span class="l">L ${s.pct.left}%</span><span class="c">C ${s.pct.center}%</span><span class="r">R ${s.pct.right}%</span></div>
        <div class="foot-row"><div class="outlets">${outletChips(s, 4)}</div>
          <div class="meta-row"><b>${s.sourceCount} sources</b>${saveBtn(s.id)}</div></div>
      </div>
      <a href="#/story/${esc(s.id)}" tabindex="-1" aria-hidden="true">${thumb(s)}</a>
    </article>`;
  }

  function leadCard(s) {
    return `<article class="card lead">
      <a href="#/story/${esc(s.id)}" tabindex="-1" aria-hidden="true">${thumb(s)}</a>
      <div class="body">
        <div class="meta-row"><span class="cat">${esc(s.category)}</span><span>${ago(s.lastUpdated)}</span>${tags(s)}</div>
        <h2><a href="#/story/${esc(s.id)}">${esc(s.title)}</a></h2>
        <p class="summary">${esc(s.summary)}</p>
        <div style="margin-top:auto">${biasBar(s.pct)}
          <div class="bias-legend"><span class="l">Left ${s.pct.left}%</span><span class="c">Center ${s.pct.center}%</span><span class="r">Right ${s.pct.right}%</span></div></div>
        <div class="foot-row"><div class="outlets">${outletChips(s, 4)}</div><div class="meta-row"><b>${s.sourceCount} sources</b>${saveBtn(s.id)}</div></div>
      </div>
    </article>`;
  }

  function miniStory(s) {
    return `<a class="mini-story" href="#/story/${esc(s.id)}">
      <div class="meta-row"><span class="cat">${esc(s.category)}</span>${tags(s)}</div>
      <div class="t">${esc(s.title)}</div>${biasBar(s.pct)}</a>`;
  }

  const skeleton = (n = 4) => Array.from({ length: n }, () => '<div class="skeleton"></div>').join('');
  const errorBox = (e) => `<div class="empty-state">Could not load data: ${esc(e.message)}. Is the server running?</div>`;

  // ---------------------------------------------------------------- my news bias
  function recordRead(s) {
    const h = store.get('history', []);
    if (h.length && h[h.length - 1].id === s.id) return;
    h.push({ id: s.id, t: Date.now(), title: s.title, pct: s.pct, blind: s.blindspot, cat: s.category });
    store.set('history', h.slice(-500));
  }
  function recordClick(source) {
    const c = store.get('clicks', {});
    c[source.name] = c[source.name] || { n: 0, bias: source.bias };
    c[source.name].n++;
    store.set('clicks', c);
  }
  function biasProfile() {
    const h = store.get('history', []);
    if (!h.length) return null;
    const sum = h.reduce((a, x) => ({ left: a.left + x.pct.left, center: a.center + x.pct.center, right: a.right + x.pct.right }), { left: 0, center: 0, right: 0 });
    const t = sum.left + sum.center + sum.right || 1;
    const pct = { left: Math.round(100 * sum.left / t), center: Math.round(100 * sum.center / t), right: 0 };
    pct.right = Math.max(0, 100 - pct.left - pct.center);
    return { n: h.length, pct, history: h };
  }
  function miniBiasCard() {
    const p = biasProfile();
    return `<div class="card card-pad"><h3>My News Bias</h3>
      ${p ? `${biasBar(p.pct)}<div class="bias-legend"><span class="l">L ${p.pct.left}%</span><span class="c">C ${p.pct.center}%</span><span class="r">R ${p.pct.right}%</span></div>
        <p class="muted" style="font-size:12.5px;margin:10px 0 0">Average coverage mix across the ${p.n} stories you opened. Stored only in this browser. <a href="#/my-bias" style="color:var(--accent)">Details</a></p>`
      : '<p class="muted" style="font-size:13px;margin:0">Open stories to build a private profile of the coverage mix you read. Nothing leaves this browser.</p>'}</div>`;
  }

  // ---------------------------------------------------------------- views
  function storyQuery(extra = {}) {
    const p = new URLSearchParams({ view: state.sort, bias: state.bias, limit: '80', ...extra });
    if (state.category) p.set('category', state.category);
    return '/stories?' + p.toString();
  }

  function filterBar() {
    const sortBtn = (v, l) => `<button data-sort="${v}" class="${state.sort === v ? 'on' : ''}">${l}</button>`;
    const biasBtn = (v, l, c) => `<button data-bias="${v}" class="${state.bias === v ? 'on' : ''}">${c ? `<span class="sw" style="background:${c}"></span>` : ''}${l}</button>`;
    return `<div class="section-head">
      <div class="section-title">${state.category ? esc(state.category) : 'Top stories'}</div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <div class="seg" role="group" aria-label="Sort">${sortBtn('top', 'Top')}${sortBtn('latest', 'Latest')}${sortBtn('polarized', 'Polarized')}</div>
        <div class="seg" role="group" aria-label="Covered by">${biasBtn('all', 'All')}${biasBtn('left', 'Left', 'var(--left)')}${biasBtn('center', 'Center', 'var(--center)')}${biasBtn('right', 'Right', 'var(--right)')}</div>
      </div></div>`;
  }

  async function viewHome() {
    view.innerHTML = `<div class="grid-home"><section>${filterBar()}${skeleton()}</section><aside class="rail">${skeleton(2)}</aside></div>`;
    try {
      const [list, blind] = await Promise.all([api(storyQuery()), api('/stories?view=blindspot&limit=5')]);
      const m = state.meta || {};
      const items = list.items;
      const main = items.length
        ? (state.sort === 'top' && state.bias === 'all' ? leadCard(items[0]) + `<div class="story-list">${items.slice(1).map(storyCard).join('')}</div>`
          : `<div class="story-list">${items.map(storyCard).join('')}</div>`)
        : '<div class="empty-state">No stories match these filters.</div>';
      const cov = m.coverage || { left: 0, center: 0, right: 0 };
      view.innerHTML = `<div class="grid-home">
        <section>${filterBar()}<p class="muted" style="font-size:13px;margin:-6px 0 14px">${list.total} stories</p>${main}</section>
        <aside class="rail">
          <div class="card card-pad"><h3>Coverage snapshot</h3>
            <div class="stat-grid">
              <div class="stat"><b>${m.storyCount ?? 0}</b><span>Stories</span></div>
              <div class="stat"><b>${m.articleCount ?? 0}</b><span>Articles</span></div>
              <div class="stat"><b>${m.feedsOk ?? 0}/${m.feedsTotal ?? 0}</b><span>Feeds online</span></div>
              <div class="stat"><b style="color:var(--warn)">${m.blindspotCount ?? 0}</b><span>Blindspots</span></div>
            </div>
            ${biasBar(cov)}<div class="bias-legend"><span class="l">L ${cov.left}%</span><span class="c">C ${cov.center}%</span><span class="r">R ${cov.right}%</span></div>
            <p class="muted" style="font-size:12px;margin:10px 0 0">Share of outlet appearances across all current stories.</p>
          </div>
          <div class="card card-pad"><h3 style="display:flex;justify-content:space-between">Blindspot <a href="#/blindspot" style="font-size:12px;color:var(--accent)">See all</a></h3>
            ${blind.items.length ? blind.items.map(miniStory).join('') : '<p class="muted" style="font-size:13px;margin:0">No stories currently meet the blindspot rule.</p>'}
          </div>
          ${miniBiasCard()}
        </aside></div>`;
    } catch (e) { view.innerHTML = errorBox(e); }
  }

  async function viewBlindspot() {
    view.innerHTML = `<h1 class="page-title">Blindspot</h1><p class="page-lede">Stories covered heavily by one side of the spectrum and barely at all by the other.</p>${skeleton(3)}`;
    try {
      const { items } = await api('/stories?view=blindspot&limit=100');
      const forLeft = items.filter((s) => s.blindspot === 'left');
      const forRight = items.filter((s) => s.blindspot === 'right');
      const col = (arr) => arr.length ? `<div class="story-list">${arr.map(storyCard).join('')}</div>` : '<div class="empty-state">None right now.</div>';
      const mth = state.meta?.method || {};
      view.innerHTML = `<h1 class="page-title">Blindspot</h1>
        <p class="page-lede">A story qualifies when at least ${mth.blindspotMinSources ?? 3} outlets cover it, one side supplies at least ${Math.round((mth.blindspotDominant ?? 0.6) * 100)}% of that coverage, and the other side supplies ${Math.round((mth.blindspotAbsent ?? 0.1) * 100)}% or less.</p>
        <div class="bs-cols">
          <section><div class="bs-head for-left"><h2>Blindspot for the Left</h2><p>Covered mostly by right-leaning outlets. ${forLeft.length} ${forLeft.length === 1 ? 'story' : 'stories'}.</p></div>${col(forLeft)}</section>
          <section><div class="bs-head for-right"><h2>Blindspot for the Right</h2><p>Covered mostly by left-leaning outlets. ${forRight.length} ${forRight.length === 1 ? 'story' : 'stories'}.</p></div>${col(forRight)}</section>
        </div>`;
    } catch (e) { view.innerHTML = errorBox(e); }
  }

  // AI summary panel. Every bullet shows the outlets it is grounded in; content is escaped like all feed text.
  function aiSummaryBlock(s) {
    const a = s.aiSummary;
    if (!a || !a.bullets || !a.bullets.length) return '';
    const chip = (o) => `<span class="chip" title="${esc(o.bias)}"><span class="sw" style="background:${BIAS_COLOR[o.bias] || 'var(--muted)'}"></span>${esc(o.name)}</span>`;
    const sideLabel = { left: 'Left', center: 'Center', right: 'Right' };
    const framing = Object.entries(a.framing || {}).filter(([k, v]) => sideLabel[k] && v);
    const report = state.meta?.reportUrl
      ? `<a class="ai-report" target="_blank" rel="noopener noreferrer" href="${esc(state.meta.reportUrl + '?title=' + encodeURIComponent('Summary issue: ' + s.title.slice(0, 120)) + '&body=' + encodeURIComponent('Story: ' + location.href + '\n\nWhat is wrong:\n'))}">Report a problem</a>` : '';
    return `<section class="ai-summary" aria-label="AI summary">
      <div class="ai-head"><span class="ai-badge">AI summary</span>
        <span class="muted">${esc(a.model)} · based on ${s.sourceCount} sources · ${ago(a.generatedAt)}</span></div>
      <ul>${a.bullets.map((b) => `<li><span>${esc(b.text)}</span><span class="ai-cites">${b.sources.map(chip).join('')}</span></li>`).join('')}</ul>
      ${framing.length ? `<div class="ai-framing">${framing.map(([k, v]) => `<div class="ai-side ${k[0]}"><b>${sideLabel[k]}</b><span>${esc(v)}</span></div>`).join('')}</div>` : ''}
      <div class="ai-foot"><span>Generated only from the outlets' headlines and feed excerpts listed on this page. Claims are attributed to their sources, not verified.</span>${report}</div>
    </section>`;
  }

  async function viewStory(id) {
    view.innerHTML = skeleton(3);
    let s;
    try { s = await api('/stories/' + encodeURIComponent(id)); }
    catch (e) {
      view.innerHTML = `<a class="crumb" href="#/">${ICON.back}Back</a><div class="empty-state">${esc(e.message)}</div>`;
      return;
    }
    recordRead(s);
    document.title = s.title + ' | Quanta AI News';
    const tab = { cur: 'all' };
    const groupOf = (b) => (b === 'Left' || b === 'Lean Left') ? 'left' : (b === 'Right' || b === 'Lean Right') ? 'right' : 'center';
    const firstBy = (g) => s.articles.find((a) => groupOf(a.source.bias) === g);
    const compareCol = (g, label) => {
      const a = firstBy(g);
      return `<div class="col ${g[0]}"><h4>${label}</h4>${a
        ? `<a class="hl" href="${esc(safeUrl(a.link))}" target="_blank" rel="noopener noreferrer" data-src="${esc(a.source.name)}" data-bias="${esc(a.source.bias)}">${esc(a.title)}</a><div class="src">${esc(a.source.name)} · ${ago(a.published)}</div>`
        : '<div class="empty">No coverage from this side.</div>'}</div>`;
    };
    const five = Object.entries(s.biasFive);
    const fiveTotal = five.reduce((a, [, n]) => a + n, 0) || 1;
    const maxOwn = Math.max(1, ...Object.values(s.ownership));
    const factTotal = Object.values(s.factuality).reduce((a, n) => a + n, 0) || 1;

    const articleRows = (filter) => {
      const list = s.articles.filter((a) => filter === 'all' || groupOf(a.source.bias) === filter);
      if (!list.length) return '<div class="empty-state" style="margin-top:14px">No outlets on this side covered the story.</div>';
      return list.map((a) => `<div class="article">
        <div class="avatar" style="background:${BIAS_COLOR[a.source.bias]}">${esc(initials(a.source.name))}</div>
        <div><div class="top"><span class="name">${esc(a.source.name)}</span><span class="${pillClass(a.source.bias)}">${esc(a.source.bias)}</span>
          <span class="fact fact-${esc(a.source.factuality)}">${esc(a.source.factuality)} factuality</span><span class="muted">${ago(a.published)}</span></div>
          <h3><a href="${esc(safeUrl(a.link))}" target="_blank" rel="noopener noreferrer" data-src="${esc(a.source.name)}" data-bias="${esc(a.source.bias)}">${esc(a.title)}</a></h3>
          ${a.description && a.description !== a.title ? `<p>${esc(a.description)}</p>` : ''}
          <div class="muted" style="font-size:12px;margin-top:4px">Owner: ${esc(a.source.owner)} (${esc(a.source.ownership)})</div></div></div>`).join('');
    };
    const counts = { all: s.articles.length, left: 0, center: 0, right: 0 };
    s.articles.forEach((a) => counts[groupOf(a.source.bias)]++);

    const render = () => {
      const isSaved = saved().has(s.id);
      view.innerHTML = `<a class="crumb" href="#/">${ICON.back}All stories</a>
      <div class="detail"><article>
        <div class="meta-row"><span class="cat">${esc(s.category)}</span><span>Updated ${ago(s.lastUpdated)}</span>${tags(s)}</div>
        <h1>${esc(s.title)}</h1>
        <p class="lede">${esc(s.summary)}</p>
        <div class="actions">
          <button class="btn${isSaved ? ' on' : ''}" data-save="${esc(s.id)}">${ICON.bookmark}${isSaved ? 'Saved' : 'Save'}</button>
          <button class="btn" id="copyLink">${ICON.link}Copy link</button>
        </div>
        ${s.demo ? '<div class="note" style="margin-bottom:18px">Illustrative demo story. Headlines and outlet angles are fictional.</div>' : ''}
        ${aiSummaryBlock(s)}
        ${s.image ? `<div class="hero-img" style="background-image:${esc(cssUrl(s.image))}"></div>` : ''}
        ${s.blindspot ? `<div class="blind-callout">${ICON.eye}<div><b>Blindspot for the ${s.blindspot === 'right' ? 'Right' : 'Left'}.</b> ${s.blindspot === 'right' ? 'Right-leaning' : 'Left-leaning'} outlets account for little or none of the coverage of this story, so readers who rely on them may not see it.</div></div>` : ''}
        <div class="section-head"><div class="section-title">Compare headlines</div></div>
        <div class="compare">${compareCol('left', 'Left')}${compareCol('center', 'Center')}${compareCol('right', 'Right')}</div>
        <div class="section-head"><div class="section-title">Full coverage · ${s.articleCount} articles</div></div>
        <div class="tabs" role="tablist">${['all', 'left', 'center', 'right'].map((k) => `<button role="tab" data-tab="${k}" class="${tab.cur === k ? 'on' : ''}">${k[0].toUpperCase() + k.slice(1)} (${counts[k]})</button>`).join('')}</div>
        <div id="articles">${articleRows(tab.cur)}</div>
      </article>
      <aside class="rail">
        <div class="card card-pad"><h3>Bias distribution</h3>${biasBar(s.pct, true)}
          <div class="five" style="margin-top:12px" aria-label="Five-point distribution">${five.filter(([, n]) => n).map(([b, n]) => `<span style="width:${100 * n / fiveTotal}%;background:${BIAS_COLOR[b]}" title="${esc(b)}: ${n}"></span>`).join('')}</div>
          <div style="margin-top:10px">${five.map(([b, n]) => `<div class="kv"><span><span class="${pillClass(b)}">${esc(b)}</span></span><b>${n}</b></div>`).join('')}</div>
        </div>
        <div class="card card-pad"><h3>Coverage details</h3>
          <div class="kv"><span>Total sources</span><b>${s.sourceCount}</b></div>
          <div class="kv"><span>Leaning left</span><b style="color:var(--left)">${s.counts.left}</b></div>
          <div class="kv"><span>Center</span><b style="color:var(--center)">${s.counts.center}</b></div>
          <div class="kv"><span>Leaning right</span><b style="color:var(--right)">${s.counts.right}</b></div>
          <div class="kv"><span>First reported by</span><b>${esc(s.firstReportedBy.name)}<br><span class="muted" style="font-weight:500;font-size:12px">${ago(s.firstReportedBy.at)}</span></b></div>
          <div class="kv"><span>Last updated</span><b>${ago(s.lastUpdated)}</b></div>
        </div>
        <div class="card card-pad"><h3>Factuality of sources</h3>
          <div class="five">${Object.entries(s.factuality).filter(([, n]) => n).map(([f, n]) => `<span style="width:${100 * n / factTotal}%;background:var(--${f === 'High' ? 'good' : f === 'Mixed' ? 'warn' : 'bad'})" title="${esc(f)}: ${n}"></span>`).join('')}</div>
          <div style="margin-top:10px">${Object.entries(s.factuality).map(([f, n]) => `<div class="kv"><span class="fact fact-${esc(f)}">${esc(f)}</span><b>${n}</b></div>`).join('')}</div>
        </div>
        <div class="card card-pad"><h3>Ownership</h3>${Object.entries(s.ownership).sort((a, b) => b[1] - a[1]).map(([o, n]) => `<div class="hbar"><span>${esc(o)}</span><div class="track"><span style="width:${100 * n / maxOwn}%"></span></div><span class="n">${n}</span></div>`).join('')}</div>
      </aside></div>`;
    };
    render();
    view.onclick = (e) => {
      const t = e.target.closest('[data-tab]');
      if (t) { tab.cur = t.dataset.tab; $('#articles').innerHTML = articleRows(tab.cur); view.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab.cur)); return; }
      if (e.target.closest('#copyLink')) {
        navigator.clipboard?.writeText(location.href).then(() => toast('Link copied.', null), () => toast('Copy failed.', null));
        return;
      }
      if (e.target.closest('.btn[data-save]')) { toggleSaved(s.id); render(); }
    };
  }

  async function viewSources() {
    view.innerHTML = `<h1 class="page-title">Sources</h1>${skeleton(2)}`;
    let data;
    try { data = await api('/sources'); } catch (e) { view.innerHTML = errorBox(e); return; }
    const st = { q: '', bias: 'all', sort: 'name', dir: 1 };
    const order = { 'Left': 0, 'Lean Left': 1, 'Center': 2, 'Lean Right': 3, 'Right': 4, 'High': 0, 'Mixed': 1, 'Low': 2 };
    const draw = () => {
      let rows = data.items.filter((s) => (st.bias === 'all' || s.group === st.bias) &&
        (s.name + ' ' + s.owner).toLowerCase().includes(st.q.toLowerCase()));
      rows.sort((a, b) => {
        const x = a[st.sort], y = b[st.sort];
        const cx = order[x] ?? x, cy = order[y] ?? y;
        return (cx > cy ? 1 : cx < cy ? -1 : 0) * st.dir;
      });
      $('#srcBody').innerHTML = rows.map((s) => `<tr>
        <td><a href="${esc(safeUrl(s.homepage))}" target="_blank" rel="noopener noreferrer"><b>${esc(s.name)}</b></a></td>
        <td><span class="${pillClass(s.bias)}">${esc(s.bias)}</span></td>
        <td><span class="fact fact-${esc(s.factuality)}">${esc(s.factuality)}</span></td>
        <td>${esc(s.ownership)}<div class="muted" style="font-size:12px">${esc(s.owner)}</div></td>
        <td>${esc(s.country)}</td>
        <td>${!s.live ? '<span class="muted">Not polled</span>' : s.feedOk === undefined ? '<span class="muted">Pending</span>'
          : s.feedOk ? `<span class="feed-dot ok"></span>${s.feedArticles} items` : `<span class="feed-dot err"></span><span title="${esc(s.feedError)}">Error</span>`}</td>
        <td class="num">${s.storyCount}</td></tr>`).join('') || '<tr><td colspan="7" class="muted">No outlets match.</td></tr>';
      $('#srcCount').textContent = rows.length + ' outlets';
    };
    const th = (k, l) => `<th data-k="${k}">${l}</th>`;
    view.innerHTML = `<h1 class="page-title">Sources</h1>
      <p class="page-lede">Every outlet in the registry, its rating, ownership, and live feed health. Ratings live in <code>config/sources.tsv</code> and can be edited.</p>
      <div class="note" style="margin-bottom:18px">${esc(data.provenance)}</div>
      <div class="toolbar"><input class="input" id="srcQ" placeholder="Filter outlets or owners" aria-label="Filter outlets">
        <div class="seg" id="srcBias">${['all', 'left', 'center', 'right'].map((b) => `<button data-b="${b}" class="${b === 'all' ? 'on' : ''}">${b[0].toUpperCase() + b.slice(1)}</button>`).join('')}</div>
        <span class="muted" id="srcCount" style="font-size:13px"></span></div>
      <div class="card table-wrap"><table><thead><tr>${th('name', 'Outlet')}${th('bias', 'Bias')}${th('factuality', 'Factuality')}${th('ownership', 'Ownership')}${th('country', 'Country')}${th('feedArticles', 'Feed')}${th('storyCount', 'Stories')}</tr></thead><tbody id="srcBody"></tbody></table></div>`;
    $('#srcQ').oninput = (e) => { st.q = e.target.value; draw(); };
    $('#srcBias').onclick = (e) => { const b = e.target.closest('[data-b]'); if (!b) return; st.bias = b.dataset.b; $('#srcBias').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); draw(); };
    view.querySelector('thead').onclick = (e) => { const h = e.target.closest('[data-k]'); if (!h) return; st.dir = st.sort === h.dataset.k ? -st.dir : 1; st.sort = h.dataset.k; draw(); };
    draw();
  }

  function viewMyBias() {
    const p = biasProfile();
    const clicks = Object.entries(store.get('clicks', {})).sort((a, b) => b[1].n - a[1].n);
    if (!p) {
      view.innerHTML = `<h1 class="page-title">My News Bias</h1><p class="page-lede">This profile records the coverage mix of stories you open. It stays in this browser and is never sent to the server.</p>
        <div class="empty-state">No reading history yet. <a href="#/" style="color:var(--accent)">Browse stories</a> to start.</div>`;
      return;
    }
    const blindRead = p.history.filter((h) => h.blind).length;
    const cats = {};
    p.history.forEach((h) => { cats[h.cat] = (cats[h.cat] || 0) + 1; });
    const maxCat = Math.max(1, ...Object.values(cats));
    const sideClicks = { left: 0, center: 0, right: 0 };
    clicks.forEach(([, v]) => { const g = /Left/.test(v.bias) ? 'left' : /Right/.test(v.bias) ? 'right' : 'center'; sideClicks[g] += v.n; });
    const tc = sideClicks.left + sideClicks.center + sideClicks.right;
    const clickPct = tc ? { left: Math.round(100 * sideClicks.left / tc), center: Math.round(100 * sideClicks.center / tc), right: 0 } : null;
    if (clickPct) clickPct.right = 100 - clickPct.left - clickPct.center;
    const lean = p.pct.left - p.pct.right;
    const reading = Math.abs(lean) < 10 ? 'broadly balanced across the spectrum' : lean > 0 ? 'weighted toward stories covered mostly by left-leaning outlets' : 'weighted toward stories covered mostly by right-leaning outlets';

    view.innerHTML = `<h1 class="page-title">My News Bias</h1>
      <p class="page-lede">Based on ${p.n} stories you opened. The profile stays in this browser and is never sent to the server.</p>
      <div class="grid-home"><section style="display:flex;flex-direction:column;gap:20px">
        <div class="card card-pad"><h3>Coverage mix of stories you read</h3>${biasBar(p.pct, true)}
          <p style="margin:12px 0 0;color:var(--ink-2)">Your reading is ${reading}. Difference between left and right shares: ${Math.abs(lean)} points.</p></div>
        <div class="card card-pad"><h3>Outlets you clicked through to</h3>
          ${clickPct ? biasBar(clickPct, true) + `<div style="margin-top:12px">${clicks.slice(0, 12).map(([n, v]) => `<div class="kv"><span>${esc(n)} <span class="${pillClass(v.bias)}">${esc(v.bias)}</span></span><b>${v.n}</b></div>`).join('')}</div>`
            : '<p class="muted" style="margin:0">No outbound clicks recorded yet.</p>'}</div>
        <div class="card card-pad"><h3>Recent reading</h3>${p.history.slice(-10).reverse().map((h) => `<a class="mini-story" href="#/story/${esc(h.id)}"><div class="meta-row"><span class="cat">${esc(h.cat)}</span><span>${ago(new Date(h.t).toISOString())}</span></div><div class="t">${esc(h.title)}</div>${biasBar(h.pct)}</a>`).join('')}</div>
      </section>
      <aside class="rail">
        <div class="card card-pad"><h3>Summary</h3>
          <div class="kv"><span>Stories read</span><b>${p.n}</b></div>
          <div class="kv"><span>Blindspot stories read</span><b>${blindRead}</b></div>
          <div class="kv"><span>Outbound clicks</span><b>${tc}</b></div></div>
        <div class="card card-pad"><h3>Topics</h3>${Object.entries(cats).sort((a, b) => b[1] - a[1]).map(([c, n]) => `<div class="hbar"><span>${esc(c)}</span><div class="track"><span style="width:${100 * n / maxCat}%"></span></div><span class="n">${n}</span></div>`).join('')}</div>
        <button class="btn" id="resetBias">Clear my history</button>
      </aside></div>`;
    $('#resetBias').onclick = () => { if (confirm('Clear your reading history and click data from this browser?')) { store.set('history', []); store.set('clicks', {}); viewMyBias(); } };
  }

  async function viewSaved() {
    const ids = [...saved()];
    view.innerHTML = `<h1 class="page-title">Saved stories</h1><p class="page-lede">Bookmarks are stored in this browser.</p>${ids.length ? skeleton(2) : ''}`;
    if (!ids.length) { view.insertAdjacentHTML('beforeend', '<div class="empty-state">Nothing saved yet. Use the bookmark icon on any story.</div>'); return; }
    try {
      const { items } = await api('/stories?view=top&limit=200&ids=' + encodeURIComponent(ids.join(',')));
      const missing = ids.length - items.length;
      view.innerHTML = `<h1 class="page-title">Saved stories</h1><p class="page-lede">Bookmarks are stored in this browser.${missing > 0 ? ` ${missing} saved ${missing === 1 ? 'story has' : 'stories have'} aged out of the current news cycle.` : ''}</p>
        ${items.length ? `<div class="story-list">${items.map(storyCard).join('')}</div>` : '<div class="empty-state">Your saved stories have aged out of the current cycle.</div>'}
        ${missing > 0 ? '<button class="btn" id="pruneSaved" style="margin-top:16px">Remove expired bookmarks</button>' : ''}`;
      const pr = $('#pruneSaved');
      if (pr) pr.onclick = () => { store.set('saved', items.map((s) => s.id)); viewSaved(); };
    } catch (e) { view.innerHTML = errorBox(e); }
  }

  async function viewSearch(q) {
    view.innerHTML = `<h1 class="page-title">Search</h1>${skeleton(3)}`;
    try {
      const { items, total } = await api('/stories?view=top&limit=100&q=' + encodeURIComponent(q));
      view.innerHTML = `<h1 class="page-title">Results for "${esc(q)}"</h1><p class="page-lede">${total} matching stories across headlines, summaries and outlet names.</p>
        ${items.length ? `<div class="story-list">${items.map(storyCard).join('')}</div>` : '<div class="empty-state">No stories match.</div>'}`;
    } catch (e) { view.innerHTML = errorBox(e); }
  }

  function viewMethod() {
    const m = state.meta?.method || {};
    view.innerHTML = `<div class="prose"><h1 class="page-title">Method</h1>
      <p class="page-lede">How stories are assembled, how coverage is scored, and where the limits sit.</p>
      <h2>1. Collection</h2>
      <p>${state.meta?.static ? 'A scheduled build' : 'The server'} polls the RSS or Atom feed of each outlet in the registry every ${state.meta?.refreshMinutes ?? 15} minutes${state.meta?.static ? ' (scheduler timing can drift by several minutes)' : ''} and keeps articles published in the last 48 hours. Feed health for each outlet is shown on the <a href="#/sources" style="color:var(--accent)">Sources</a> page.</p>
      <h2>2. Story clustering</h2>
      <p>Each article is converted to a TF-IDF vector over normalized headline terms (double weight) and summary terms. Articles are processed newest first and joined to the existing cluster whose centroid has the highest cosine similarity, provided it clears <code>${m.clusterThreshold ?? 0.3}</code> and falls within a 36-hour window. A cluster is published as a story only when at least <code>${m.minSources ?? 2}</code> distinct outlets report it.</p>
      <h2>3. Coverage distribution</h2>
      <p>Each outlet counts once per story, regardless of how many articles it ran. The five-point rating collapses to three groups: Left and Lean Left as left, Center as center, Lean Right and Right as right.</p>
      <h2>4. Blindspot and polarization rules</h2>
      <ul><li>Blindspot: at least ${m.blindspotMinSources ?? 3} outlets, one side supplying at least ${Math.round((m.blindspotDominant ?? 0.6) * 100)}% of outlets, the other side ${Math.round((m.blindspotAbsent ?? 0.1) * 100)}% or less.</li>
      <li>Polarized: at least 4 outlets, center below 25%, and at least two outlets on each side.</li></ul>
      <h2>5. AI summaries</h2>
      <p>${state.meta?.summaryCount ? `${state.meta.summaryCount} stories currently carry an AI summary generated by <code>${esc(state.meta.summaryModel || '')}</code>.` : 'AI summaries appear when the build is configured with a model API key.'}
      The model receives only the headlines and feed excerpts in the story, labelled by source, and must cite those labels on every bullet. Before publishing, each bullet is checked automatically: it must cite at least one real source, and every number it contains must appear in the text of the sources it cites. Bullets that fail are removed, and a summary with fewer than two remaining bullets is not shown. Summaries attribute claims to their sources and are not independent verification. Unchanged stories reuse their earlier summary.</p>
      <h2>6. Ratings and their limits</h2>
      <div class="note">Outlet bias, factuality and ownership values are provisional seeds approximated from public aggregate rating charts (AllSides, Ad Fontes Media, Media Bias/Fact Check). They describe outlets, not individual articles, and they have not been independently validated here. Calibrate them in <code>config/sources.tsv</code> against a cited methodology before analytic or published use.</div>
      <p>Known constraints: keyword clustering can split one event into two stories or merge adjacent events; feed availability shifts coverage counts (an outlet without a working feed is invisible, which can manufacture an apparent blindspot); and category labels come from a keyword lexicon, not human review.</p>
      <h2>7. Privacy</h2>
      <p>Reading history, outbound clicks and bookmarks are stored in your browser's local storage. The server keeps no user data.</p></div>`;
  }

  // ---------------------------------------------------------------- chrome
  function renderTopics() {
    const cats = state.meta?.categories || {};
    const entries = Object.entries(cats).sort((a, b) => b[1] - a[1]);
    $('#topics').innerHTML = `<button class="topic${state.category ? '' : ' active'}" data-cat="">All topics</button>` +
      entries.map(([c, n]) => `<button class="topic${state.category === c ? ' active' : ''}" data-cat="${esc(c)}">${esc(c)}<span class="n">${n}</span></button>`).join('');
  }

  function renderStatus() {
    const m = state.meta;
    const pill = $('#statusPill');
    pill.classList.remove('live', 'demo');
    if (!m) { $('#statusText').textContent = 'Offline'; return; }
    pill.classList.add(m.mode);
    $('#statusText').textContent = m.refreshing ? 'Refreshing' : m.mode === 'live' ? 'Live · ' + ago(m.refreshedAt) : 'Demo data';
    pill.title = `${m.feedsOk}/${m.feedsTotal} feeds online. Updated ${ago(m.refreshedAt)}.`;
    $('#demoBanner').classList.toggle('hidden', m.mode !== 'demo');
  }

  let toastAction = null;
  function toast(text, action) {
    $('#toastText').textContent = text;
    toastAction = action;
    $('#toastBtn').classList.toggle('hidden', !action);
    $('#toast').classList.remove('hidden');
    if (!action) setTimeout(() => $('#toast').classList.add('hidden'), 2200);
  }
  $('#toastBtn').onclick = () => { $('#toast').classList.add('hidden'); toastAction && toastAction(); };

  async function loadMeta(initial) {
    try {
      const m = await api('/meta');
      const changed = state.lastRefreshSeen && m.refreshedAt !== state.lastRefreshSeen && !m.refreshing;
      state.meta = m;
      if (initial || !state.lastRefreshSeen) state.lastRefreshSeen = m.refreshedAt;
      renderStatus();
      renderTopics();
      if (changed) {
        toast(`New coverage: ${m.storyCount} stories from ${m.feedsOk} feeds.`, () => { state.lastRefreshSeen = m.refreshedAt; route(); });
      }
    } catch { state.meta = null; renderStatus(); }
  }

  // ---------------------------------------------------------------- routing
  function route() {
    const h = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = h.split('?');
    const parts = path.split('/').filter(Boolean);
    const name = parts[0] || 'home';
    document.querySelectorAll('.mainnav a').forEach((a) => a.classList.toggle('active', a.dataset.route === name));
    document.title = 'Quanta AI News';
    view.onclick = null;
    $('#toast').classList.add('hidden');
    window.scrollTo(0, 0);
    switch (name) {
      case 'home': return viewHome();
      case 'blindspot': return viewBlindspot();
      case 'story': return viewStory(parts[1]);
      case 'sources': return viewSources();
      case 'my-bias': return viewMyBias();
      case 'saved': return viewSaved();
      case 'method': return viewMethod();
      case 'search': return viewSearch(new URLSearchParams(qs).get('q') || '');
      default: return viewHome();
    }
  }

  // ---------------------------------------------------------------- global events
  document.addEventListener('click', (e) => {
    const save = e.target.closest('.save-btn[data-save]');
    if (save) {
      e.preventDefault();
      const on = toggleSaved(save.dataset.save);
      save.classList.toggle('on', on);
      save.setAttribute('aria-pressed', on);
      return;
    }
    const src = e.target.closest('a[data-src]');
    if (src) recordClick({ name: src.dataset.src, bias: src.dataset.bias || 'Center' });
    const sort = e.target.closest('[data-sort]');
    if (sort) { state.sort = sort.dataset.sort; viewHome(); return; }
    const bias = e.target.closest('.seg [data-bias]');
    if (bias) { state.bias = bias.dataset.bias; viewHome(); return; }
    const cat = e.target.closest('.topic[data-cat]');
    if (cat) {
      state.category = cat.dataset.cat;
      renderTopics();
      if (location.hash && location.hash !== '#/') location.hash = '#/'; else viewHome();
    }
  });
  $('#searchForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const q = $('#searchInput').value.trim();
    location.hash = q ? '#/search?q=' + encodeURIComponent(q) : '#/';
  });

  $('#themeBtn').onclick = () => {
    const cur = document.documentElement.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('qan.theme', next); } catch { /* ignore */ }
  };

  window.addEventListener('hashchange', route);
  loadMeta(true).then(route);
  setInterval(() => loadMeta(false), 60000);
  setInterval(renderStatus, 30000);
})();
