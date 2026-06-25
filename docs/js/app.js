/* ============================================
   Confused Creature — Shared Site JS
   Theme toggle + Extension loading + Copy + Guides
   ============================================ */

const REPO_BASE = 'https://raw.githubusercontent.com/Confused-Creature-180/aniyomi-extensions/repo';
const INDEX_URL = REPO_BASE + '/index.min.json';
const REPO_URL = INDEX_URL;
const GITHUB_URL = 'https://github.com/Confused-Creature-180/aniyomi-extensions';
const PAGES_URL = 'https://confused-creature-180.github.io/aniyomi-extensions/';

/* --- Theme Toggle --- */
function initThemeToggle() {
  const toggle = document.querySelector('.theme-toggle');
  if (!toggle) return;

  toggle.addEventListener('click', function() {
    const root = document.documentElement;
    const body = document.body;

    // Add transitioning class for smooth color shift
    body.classList.add('theme-transitioning');

    const current = root.className;
    const next = current === 'dark' ? 'light' : 'dark';
    root.className = next;
    root.style.colorScheme = next;
    try {
      localStorage.setItem('theme', next);
    } catch(e) {}

    // Remove transitioning class after the transition completes
    setTimeout(function() {
      body.classList.remove('theme-transitioning');
    }, 450);
  });
}

/* --- Toast notification --- */
function showToast(message) {
  let toast = document.querySelector('.toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.className = 'toast';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2000);
}

/* --- Copy to clipboard --- */
async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
  }
}

function initCopyButtons() {
  document.querySelectorAll('[data-copy]').forEach(btn => {
    btn.addEventListener('click', async function() {
      const url = this.getAttribute('data-copy');
      await copyToClipboard(url);

      const original = this.querySelector('.btn-label');
      const originalText = original ? original.textContent : this.textContent;

      this.classList.add('copied');
      if (original) original.textContent = 'Copied!';
      else this.textContent = 'Copied!';

      showToast('URL copied to clipboard');

      setTimeout(() => {
        this.classList.remove('copied');
        if (original) original.textContent = originalText;
        else this.textContent = originalText;
      }, 2000);
    });
  });
}

/* --- HTML escape --- */
function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

/* --- Render extension cards --- */
async function loadExtensions(containerId, limit) {
  const grid = document.getElementById(containerId);
  if (!grid) return;

  try {
    const response = await fetch(INDEX_URL);
    if (!response.ok) throw new Error('HTTP ' + response.status);
    // Read as text first, then convert large "id" numbers to strings to
    // prevent JavaScript's float64 precision loss (source IDs exceed 2^53).
    const rawText = await response.text();
    const safeText = rawText.replace(/"id":(\d+)/g, '"id":"$1"');
    const extensions = JSON.parse(safeText);

    if (!extensions || extensions.length === 0) {
      grid.innerHTML = '<p style="text-align:center;color:var(--fg-muted);grid-column:1/-1;padding:40px;">No extensions available yet. Check back soon.</p>';
      return;
    }

    // Sort: NSFW last, then by name
    extensions.sort((a, b) => {
      if (a.nsfw !== b.nsfw) return a.nsfw - b.nsfw;
      return a.name.localeCompare(b.name);
    });

    const toShow = limit ? extensions.slice(0, limit) : extensions;

    grid.innerHTML = '';
    toShow.forEach(function(ext, i) {
      const card = createExtensionCard(ext);
      // Stagger the entrance animation
      card.style.animationDelay = (i * 0.08) + 's';
      grid.appendChild(card);
    });

    // If limited and there are more, show a "view all" link
    if (limit && extensions.length > limit) {
      const more = document.createElement('div');
      more.style.cssText = 'grid-column:1/-1;text-align:center;padding:20px;';
      more.innerHTML = '<a href="./extensions.html" style="font-family:var(--font-mono);font-weight:600;font-size:0.9rem;">View all ' + extensions.length + ' extensions &rarr;</a>';
      grid.appendChild(more);
    }
  } catch (err) {
    console.error('Failed to load extensions:', err);
    grid.innerHTML = '<p style="text-align:center;color:var(--fg-muted);grid-column:1/-1;padding:40px;">Failed to load extensions. <a href=".">Refresh</a></p>';
  }
}

function createExtensionCard(ext) {
  const card = document.createElement('div');
  card.className = 'extension-card';
  if (ext.nsfw === 1) card.classList.add('nsfw');

  const iconUrl = REPO_BASE + '/icon/' + ext.pkg + '.png';
  const apkUrl = REPO_BASE + '/apk/' + ext.apk;
  const source = (ext.sources && ext.sources.length > 0) ? ext.sources[0] : null;

  // Badges
  let badges = '';
  badges += '<span class="badge badge-lang">' + escapeHtml(ext.lang ? ext.lang.toUpperCase() : 'ALL') + '</span>';
  badges += '<span class="badge badge-version">v' + escapeHtml(ext.version) + '</span>';
  if (ext.nsfw === 1) {
    badges += '<span class="badge badge-nsfw">NSFW</span>';
  }

  // Source info
  let sourceHtml = '';
  if (source) {
    sourceHtml = '<div class="card-source">' +
      '<span>Source: </span>' +
      '<a href="' + escapeHtml(source.baseUrl) + '" target="_blank" rel="noopener">' +
        escapeHtml(source.name) +
      '</a>' +
    '</div>';
  }

  // Metadata — package on its own line (wraps), version code + source ID inline
  let metaHtml = '<div class="card-meta">';
  metaHtml += '<div class="meta-item"><span class="meta-label">Package</span><span class="meta-value meta-value-wrap">' + escapeHtml(ext.pkg) + '</span></div>';
  metaHtml += '<div class="meta-item-inline"><span class="meta-label">Version Code</span><span class="meta-value">' + ext.code + '</span></div>';
  if (source) {
    metaHtml += '<div class="meta-item-inline"><span class="meta-label">Source ID</span><span class="meta-value">' + source.id + '</span></div>';
  }
  metaHtml += '</div>';

  // Footer with download button
  let footerHtml = '<div class="card-footer">' +
    '<a href="' + escapeHtml(apkUrl) + '" class="btn btn-primary btn-small" download>' +
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>' +
      'Download APK' +
    '</a>' +
    '<button class="btn btn-secondary btn-small" data-copy="' + escapeHtml(REPO_URL) + '">' +
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>' +
      '<span class="btn-label">Copy URL</span>' +
    '</button>' +
  '</div>';

  card.innerHTML =
    '<div class="card-header">' +
      '<img src="' + escapeHtml(iconUrl) + '" alt="' + escapeHtml(ext.name) + '" class="ext-icon" loading="lazy" onerror="this.style.opacity=\'0.2\'">' +
      '<div class="card-title">' +
        '<h3>' + escapeHtml(ext.name) + '</h3>' +
        '<div class="badges">' + badges + '</div>' +
      '</div>' +
    '</div>' +
    sourceHtml +
    metaHtml +
    footerHtml;

  return card;
}

/* --- Guide accordions --- */
function initGuides() {
  document.querySelectorAll('.guide-header').forEach(header => {
    header.addEventListener('click', function() {
      const item = this.parentElement;
      item.classList.toggle('open');
    });
  });
}

/* --- Init --- */
document.addEventListener('DOMContentLoaded', function() {
  initThemeToggle();
  initCopyButtons();
  initGuides();

  // Load extensions if the grid exists
  const homeGrid = document.getElementById('extension-grid');
  if (homeGrid && homeGrid.getAttribute('data-full') === 'true') {
    loadExtensions('extension-grid', null);
  } else if (homeGrid) {
    loadExtensions('extension-grid', 6);
  }
});
