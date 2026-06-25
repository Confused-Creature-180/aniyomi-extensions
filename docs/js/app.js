/* ============================================
   AniKoto Extensions — Site JavaScript
   Fetches index.min.json from repo branch,
   renders extension cards dynamically.
   ============================================ */

const REPO_BASE = 'https://raw.githubusercontent.com/Confused-Creature-180/aniyomi-extensions/repo';
const INDEX_URL = REPO_BASE + '/index.min.json';
const REPO_URL = INDEX_URL; // URL users paste into Aniyomi

/* --- Render extension cards from index.min.json --- */
async function loadExtensions() {
  const grid = document.getElementById('extension-grid');
  const loadingText = document.getElementById('loading-text');

  try {
    const response = await fetch(INDEX_URL);
    if (!response.ok) throw new Error('HTTP ' + response.status);

    const extensions = await response.json();

    if (!extensions || extensions.length === 0) {
      grid.innerHTML = '<p style="text-align:center;color:var(--text-muted);grid-column:1/-1;">No extensions available yet. Check back soon.</p>';
      return;
    }

    // Sort: NSFW last, then by name
    extensions.sort((a, b) => {
      if (a.nsfw !== b.nsfw) return a.nsfw - b.nsfw;
      return a.name.localeCompare(b.name);
    });

    grid.innerHTML = '';

    extensions.forEach(ext => {
      const card = document.createElement('div');
      card.className = 'extension-card';
      if (ext.nsfw === 1) card.classList.add('nsfw');

      const iconUrl = REPO_BASE + '/icon/' + ext.pkg + '.png';
      const source = (ext.sources && ext.sources.length > 0) ? ext.sources[0] : null;

      // Build badges
      const badges = [];
      badges.push('<span class="badge badge-lang">' + escapeHtml(ext.lang ? ext.lang.toUpperCase() : 'ALL') + '</span>');
      badges.push('<span class="badge badge-version">v' + escapeHtml(ext.version) + '</span>');
      if (ext.nsfw === 1) {
        badges.push('<span class="badge badge-nsfw">NSFW</span>');
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

      card.innerHTML =
        '<div class="card-header">' +
          '<img src="' + escapeHtml(iconUrl) + '" alt="' + escapeHtml(ext.name) + '" class="ext-icon" loading="lazy" ' +
               'onerror="this.style.opacity=\'0.2\'">' +
          '<div class="card-title">' +
            '<h3>' + escapeHtml(ext.name) + '</h3>' +
            '<div class="badges">' + badges.join('') + '</div>' +
          '</div>' +
        '</div>' +
        sourceHtml +
        '<div class="card-footer">' +
          '<span>versionCode: ' + ext.code + '</span>' +
          '<span>' + (ext.sources ? ext.sources.length : 0) + ' source' + (ext.sources && ext.sources.length !== 1 ? 's' : '') + '</span>' +
        '</div>';

      grid.appendChild(card);
    });

    if (loadingText) loadingText.style.display = 'none';
  } catch (err) {
    console.error('Failed to load extensions:', err);
    grid.innerHTML = '<p style="text-align:center;color:var(--text-muted);grid-column:1/-1;">' +
      'Failed to load extensions. <a href="." style="color:var(--accent-hover)">Refresh</a>' +
    '</p>';
    if (loadingText) loadingText.style.display = 'none';
  }
}

/* --- Copy URL buttons --- */
function setupCopyButtons() {
  const buttons = document.querySelectorAll('[id^="copy-url-btn"]');
  buttons.forEach(btn => {
    btn.addEventListener('click', async function() {
      const url = this.getAttribute('data-url') || REPO_URL;
      const label = this.querySelector('.btn-label');

      try {
        await navigator.clipboard.writeText(url);
      } catch (err) {
        // Fallback for non-secure contexts
        const textarea = document.createElement('textarea');
        textarea.value = url;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }

      // Visual feedback
      this.classList.add('copied');
      const originalText = label ? label.textContent : this.textContent;
      if (label) {
        label.textContent = 'Copied!';
      } else {
        this.textContent = 'Copied!';
      }

      setTimeout(() => {
        this.classList.remove('copied');
        if (label) {
          label.textContent = originalText;
        } else {
          this.textContent = originalText;
        }
      }, 2000);
    });
  });
}

/* --- HTML escape (prevent XSS from index data) --- */
function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

/* --- Init --- */
document.addEventListener('DOMContentLoaded', function() {
  loadExtensions();
  setupCopyButtons();
});
