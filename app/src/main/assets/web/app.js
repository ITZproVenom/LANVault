/* LANVault web client, rebuilt from a clean UI layer.
   Keeps the HTTP API contract; layout, rendering, navigation and controls are newly authored. */
(function () {
  'use strict';

  var state = {
    info: {}, settings: {}, volumes: [], entries: [], path: '',
    route: 'browse', view: 'grid', monitoring: null, uploadBusy: false
  };
  var el = {};
  var refreshTimer = null;
  var monitorTimer = null;
  var toastTimer = null;

  document.addEventListener('DOMContentLoaded', boot);

  function boot() {
    [
      'statusDot','sidebarFooter','volumeList','pathBar','view','notice','noticeText',
      'noticeAction','dropzone','viewToggle','newFolderButton','uploadButton','filePicker',
      'overlay','overlaySheet','toast','uploadProgress'
    ].forEach(function (id) { el[id] = document.getElementById(id); });

    el.viewToggle.addEventListener('click', function () {
      state.view = state.view === 'grid' ? 'list' : 'grid';
      el.viewToggle.innerHTML = state.view === 'grid'
        ? '&#9776; <span>List view</span>' : '&#9638; <span>Grid view</span>';
      renderFiles();
    });
    el.newFolderButton.addEventListener('click', createFolder);
    el.uploadButton.addEventListener('click', function () { el.filePicker.click(); });
    el.filePicker.addEventListener('change', function () {
      transferFiles(Array.prototype.slice.call(el.filePicker.files || []));
      el.filePicker.value = '';
    });
    el.dropzone.addEventListener('click', function () { el.filePicker.click(); });
    bindDrop(el.dropzone);
    el.overlay.addEventListener('click', function (event) {
      if (event.target === el.overlay) closeDialog();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') closeDialog();
    });
    window.addEventListener('hashchange', navigate);

    navigate();
    refreshState();
    refreshTimer = window.setInterval(refreshState, 6000);
  }

  function request(path, options) {
    return fetch(path, options || {}).then(function (response) {
      if (response.status === 401) throw new Error('Sign in required. Set up access in LANVault Settings and reload.');
      if (!response.ok) {
        return response.text().catch(function () { return ''; }).then(function (text) {
          var message = '';
          try { message = JSON.parse(text).error || ''; } catch (_) { message = text; }
          throw new Error(message || ('Request failed (' + response.status + ')'));
        });
      }
      return response.text().then(function (text) {
        if (!text) return {};
        try { return JSON.parse(text); } catch (_) { return { text: text }; }
      });
    });
  }

  function postJson(path, body) {
    return request(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    });
  }

  function postForm(path, values) {
    return request(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: Object.keys(values).map(function (key) {
        return encodeURIComponent(key) + '=' + encodeURIComponent(String(values[key]));
      }).join('&')
    });
  }

  function navigate() {
    var parts = location.hash.replace(/^#\/?/, '').split('/');
    var name = parts[0] || 'browse';
    state.route = ['browse','uploads','settings','logs','monitoring'].indexOf(name) >= 0 ? name : 'browse';
    state.path = state.route === 'browse' ? decodeHashPath(parts.slice(1).join('/')) : state.path;
    document.querySelectorAll('[data-nav]').forEach(function (link) {
      link.classList.toggle('active', link.getAttribute('data-nav') === state.route);
    });
    var browsing = state.route === 'browse';
    el.pathBar.style.display = browsing ? '' : 'none';
    el.viewToggle.style.display = browsing ? '' : 'none';
    el.newFolderButton.style.display = browsing ? '' : 'none';
    el.uploadButton.style.display = browsing ? '' : 'none';
    el.dropzone.style.display = browsing ? '' : 'none';
    if (monitorTimer) { window.clearInterval(monitorTimer); monitorTimer = null; }

    updatePageHeading();
    if (browsing) loadDirectory(state.path);
    else if (state.route === 'settings') renderSettings();
    else if (state.route === 'uploads') renderTransfers();
    else if (state.route === 'logs') renderLogs();
    else renderMonitoring();
  }

  function decodeHashPath(value) {
    try { return decodeURIComponent(value || ''); } catch (_) { return value || ''; }
  }

  function updatePageHeading() {
    var data = {
      browse: ['Your files, at home.', 'A simple front door to every drive connected to your TV.', 'Everything, together.', 'Browse, preview, and stream directly from the source.'],
      uploads: ['Moves without the fuss.', 'Transfers between this device and your local storage.', 'Transfer activity', 'Copy files to the TV over your local network.'],
      settings: ['Your setup, your rules.', 'Make local access work the way you want it to.', 'Control the details', 'Protocols, permissions, access and TV behaviour.'],
      logs: ['A clear view of activity.', 'Useful details from the server, all in one place.', 'Recent activity', 'A live record of what LANVault is doing.'],
      monitoring: ['Know your network.', 'A live look at the TV, its interfaces, and AdGuard Home.', 'Network health', 'Service status and monitoring controls for this TV.']
    }[state.route];
    [['pageTitle',data[0]],['pageSubtitle',data[1]],['contentTitle',data[2]],['contentSubtitle',data[3]]]
      .forEach(function (pair) {
        var node = document.getElementById(pair[0]);
        if (node) node.textContent = pair[1];
      });
  }

  function refreshState() {
    request('/api/state').then(function (data) {
      state.info = data || {};
      state.settings = data.settings || {};
      state.volumes = data.volumes || [];
      el.statusDot.classList.toggle('offline', !data.running);
      el.sidebarFooter.textContent = data.running
        ? 'Port ' + data.port + ' · ' + (data.volumeCount || 0) + ' drive(s)'
        : 'Server offline';
      renderVolumes();
      updateNotice(data);
    }).catch(function (error) {
      el.sidebarFooter.textContent = error.message;
      el.statusDot.classList.add('offline');
    });
  }

  function updateNotice(info) {
    if (info.portInUseOnPreferred) {
      setNotice('Port ' + info.requestedPort + ' is already in use. LANVault is running on port ' + info.port + '.', 'Keep port ' + info.port, function () {
        postForm('/api/settings', Object.assign({}, state.settings, { serverPort: info.port })).then(function () {
          toast('Port preference saved'); refreshState();
        }).catch(toast);
      });
    } else if (!info.allFilesAccess) {
      setNotice('Full storage access has not been granted. Some drives may not be visible yet.', 'Choose storage', function () {
        request('/api/storage?action=pick').then(function () { toast('Use the TV remote to select a drive.'); }).catch(toast);
      });
    } else {
      el.notice.classList.remove('show');
    }
  }

  function setNotice(message, actionText, action) {
    el.noticeText.textContent = message;
    el.noticeAction.textContent = actionText;
    el.noticeAction.onclick = action;
    el.notice.classList.add('show');
  }

  function renderVolumes() {
    el.volumeList.replaceChildren();
    if (!state.volumes.length) {
      el.volumeList.innerHTML = '<div class="volume-placeholder"><span></span><span>No drives detected yet</span></div>';
      return;
    }
    state.volumes.forEach(function (volume) {
      var node = document.createElement('button');
      node.type = 'button';
      node.className = 'volume-item' + (state.path.split('/')[0] === volume.slug && state.path ? ' active' : '');
      node.setAttribute('aria-label', 'Open ' + volume.displayName);
      var symbol = volume.kind === 'usb' ? '↯' : volume.kind === 'internal' ? '▤' : '▱';
      var icon = document.createElement('span');
      icon.textContent = symbol;
      var label = document.createElement('span');
      label.textContent = volume.displayName;
      node.appendChild(icon);
      node.appendChild(label);
      if (volume.needsPermission) {
        var badge = document.createElement('span');
        badge.className = 'badge locked'; badge.textContent = 'access';
        node.appendChild(badge);
      } else if (!volume.writable) {
        var ro = document.createElement('span');
        ro.className = 'badge'; ro.textContent = 'read-only';
        node.appendChild(ro);
      }
      node.addEventListener('click', function () { location.hash = '#/browse/' + encodeHashPath(volume.slug); });
      el.volumeList.appendChild(node);
    });
  }

  function loadDirectory(path) {
    el.view.innerHTML = '<div class="empty">Opening your storage…</div>';
    request('/api/list?path=' + encodeURIComponent(path)).then(function (payload) {
      state.entries = (payload && payload.entries) || [];
      renderFiles();
      renderBreadcrumbs();
    }).catch(function (error) {
      state.entries = [];
      el.view.innerHTML = '<div class="empty">' + escapeHtml(error.message) + '</div>';
      renderBreadcrumbs();
    });
  }

  function renderBreadcrumbs() {
    el.pathBar.replaceChildren();
    var root = document.createElement('span');
    root.className = 'seg'; root.textContent = 'All drives';
    root.addEventListener('click', function () { location.hash = '#/browse'; });
    el.pathBar.appendChild(root);
    (state.path ? state.path.split('/') : []).forEach(function (part, index, parts) {
      var separator = document.createElement('span');
      separator.className = 'sep'; separator.textContent = '/';
      el.pathBar.appendChild(separator);
      var item = document.createElement('span');
      item.className = 'seg'; item.textContent = part;
      item.addEventListener('click', function () {
        location.hash = '#/browse/' + encodeHashPath(parts.slice(0, index + 1).join('/'));
      });
      el.pathBar.appendChild(item);
    });
  }

  function renderFiles() {
    el.view.replaceChildren();
    renderVolumes();
    var count = state.entries.length;
    var heading = document.getElementById('contentTitle');
    var subtitle = document.getElementById('contentSubtitle');
    if (heading) heading.textContent = state.path ? state.path.split('/').pop() : 'Everything, together.';
    if (subtitle) subtitle.textContent = count + (count === 1 ? ' item' : ' items') + ' · streamed directly from your local storage';
    if (!count) {
      var empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = state.path ? 'Nothing here yet. Upload files or create a folder to get started.' : 'No shared files found. Connect or grant access to a drive on your TV.';
      el.view.appendChild(empty);
      return;
    }
    if (state.view === 'grid') renderFileGrid();
    else renderFileList();
  }

  function renderFileGrid() {
    var grid = document.createElement('div'); grid.className = 'grid';
    state.entries.forEach(function (entry) {
      var card = document.createElement('article'); card.className = 'card'; card.tabIndex = 0;
      var thumb = document.createElement('div'); thumb.className = 'thumb';
      if (entry.isDirectory) {
        thumb.innerHTML = '<span class="glyph" aria-hidden="true">▤</span>';
      } else if (entry.kind === 'image') {
        var image = document.createElement('img');
        image.loading = 'lazy'; image.alt = ''; image.src = '/api/thumbnail?path=' + encodeURIComponent(entry.path);
        image.onerror = function () { thumb.replaceChildren(glyph('▧')); };
        thumb.appendChild(image);
      } else {
        thumb.appendChild(glyph(iconFor(entry)));
        if (entry.kind === 'video') {
          var play = document.createElement('div'); play.className = 'play'; play.textContent = '▶'; thumb.appendChild(play);
        }
      }
      var meta = document.createElement('div'); meta.className = 'meta';
      var name = document.createElement('div'); name.className = 'name'; name.textContent = entry.name;
      var sub = document.createElement('div'); sub.className = 'sub';
      sub.textContent = entry.isDirectory ? 'Folder' : formatBytes(entry.size);
      meta.append(name, sub);
      card.append(thumb, meta);
      card.title = entry.name;
      card.addEventListener('click', function () { openEntry(entry); });
      card.addEventListener('keydown', function (event) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openEntry(entry); } });
      grid.appendChild(card);
    });
    el.view.appendChild(grid);
  }

  function glyph(value) {
    var span = document.createElement('span');
    span.className = 'glyph'; span.setAttribute('aria-hidden','true'); span.textContent = value;
    return span;
  }

  function renderFileList() {
    var list = document.createElement('div'); list.className = 'list';
    var header = document.createElement('div');
    header.className = 'row header';
    header.innerHTML = '<div>Name</div><div class="col-mobile-hide">Size</div><div class="col-mobile-hide">Modified</div><div></div>';
    list.appendChild(header);
    state.entries.forEach(function (entry) {
      var row = document.createElement('div'); row.className = 'row';
      var name = document.createElement('div'); name.className = 'name'; name.tabIndex = 0;
      var ic = document.createElement('span'); ic.textContent = iconFor(entry);
      var label = document.createElement('span'); label.textContent = entry.name;
      name.append(ic,label);
      name.addEventListener('click', function () { openEntry(entry); });
      name.addEventListener('keydown', function (event) { if (event.key === 'Enter') openEntry(entry); });
      var size = document.createElement('div'); size.className = 'col-mobile-hide'; size.textContent = entry.isDirectory ? 'Folder' : formatBytes(entry.size);
      var modified = document.createElement('div'); modified.className = 'col-mobile-hide'; modified.textContent = entry.lastModified ? formatDate(entry.lastModified) : '—';
      var actions = document.createElement('div'); actions.className = 'row-actions';
      if (!entry.isDirectory) {
        var download = document.createElement('button'); download.className = 'button button-soft'; download.textContent = 'Download';
        download.addEventListener('click', function (event) { event.stopPropagation(); downloadEntry(entry); });
        actions.appendChild(download);
      }
      var more = document.createElement('button'); more.className = 'button button-soft'; more.textContent = '•••'; more.setAttribute('aria-label','More actions for ' + entry.name);
      more.addEventListener('click', function (event) { event.stopPropagation(); showEntryActions(entry); });
      actions.appendChild(more);
      row.append(name,size,modified,actions); list.appendChild(row);
    });
    el.view.appendChild(list);
  }

  function iconFor(entry) {
    if (entry.isDirectory || entry.kind === 'folder') return '▤';
    return ({ image:'▧', video:'▶', audio:'♫', subtitle:'≋', file:'↗' })[entry.kind] || '↗';
  }

  function openEntry(entry) {
    if (entry.isDirectory) { location.hash = '#/browse/' + encodeHashPath(entry.path); return; }
    if (entry.kind === 'image') showImage(entry);
    else if (entry.kind === 'video' || entry.kind === 'audio') showPlayer(entry);
    else showFileDetails(entry);
  }

  function downloadEntry(entry) {
    window.location.href = '/api/download?path=' + encodeURIComponent(entry.path);
  }

  function showImage(entry) {
    var images = state.entries.filter(function (item) { return item.kind === 'image' && !item.isDirectory; });
    var index = Math.max(0, images.indexOf(entry));
    el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">IMAGE PREVIEW</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="closeDialog">Close</button></div>' +
      '<img id="imagePreview" alt="" class="image-preview" />' +
      '<div class="dialog-actions"><span id="imagePosition" class="hint"></span><button class="button button-soft" id="prevImage">← Previous</button><button class="button button-soft" id="nextImage">Next →</button><button class="button button-primary" id="downloadImage">Download</button></div>';
    setText('dialogTitle', entry.name);
    function showAt() {
      var item = images[index];
      document.getElementById('imagePreview').src = '/api/file?path=' + encodeURIComponent(item.path);
      document.getElementById('dialogTitle').textContent = item.name;
      document.getElementById('imagePosition').textContent = (index + 1) + ' of ' + images.length;
      document.getElementById('prevImage').disabled = images.length < 2;
      document.getElementById('nextImage').disabled = images.length < 2;
    }
    document.getElementById('closeDialog').addEventListener('click', closeDialog);
    document.getElementById('prevImage').addEventListener('click', function () { index = (index + images.length - 1) % images.length; showAt(); });
    document.getElementById('nextImage').addEventListener('click', function () { index = (index + 1) % images.length; showAt(); });
    document.getElementById('downloadImage').addEventListener('click', function () { downloadEntry(images[index]); });
    showAt(); openDialog();
  }

  function showPlayer(entry) {
    var ext = extensionOf(entry.name);
    var unsupported = ['mkv','avi','wmv','flv','divx','asf','m2ts','ts','flac','wma','aiff','mid','midi','amr','ogg','oga','opus'];
    var warning = unsupported.indexOf(ext) >= 0 ? 'This browser may not support .' + ext + '. You can still download it or open it in a compatible media app.' : '';
    var tag = entry.kind === 'audio' ? 'audio' : 'video';
    el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">DIRECT STREAM</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="closeDialog">Close</button></div>' +
      '<p class="hint">Playback comes straight from the TV. The complete file is not downloaded first.</p>' +
      (warning ? '<div class="monitor-alert">' + escapeHtml(warning) + '</div>' : '') +
      '<' + tag + ' id="mediaPlayer" controls autoplay playsinline preload="metadata" class="media-player"><source src="/api/file?path=' + encodeURIComponent(entry.path) + '"></' + tag + '>' +
      '<p id="playerError" class="playback-error" hidden></p><div class="dialog-actions"><button class="button button-primary" id="downloadMedia">Download file</button></div>';
    setText('dialogTitle', entry.name);
    document.getElementById('closeDialog').addEventListener('click', closeDialog);
    document.getElementById('downloadMedia').addEventListener('click', function () { downloadEntry(entry); });
    var player = document.getElementById('mediaPlayer');
    player.addEventListener('error', function () { var node = document.getElementById('playerError'); node.hidden = false; node.textContent = warning || 'This browser could not play the file. Download it to use a compatible player.'; });
    player.addEventListener('stalled', function () { var node = document.getElementById('playerError'); node.hidden = false; node.textContent = 'The stream stalled. Check that the TV is reachable and try again.'; });
    if (warning) { var node = document.getElementById('playerError'); node.hidden = false; node.textContent = warning; }
    openDialog();
  }

  function showFileDetails(entry) {
    el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">FILE DETAILS</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="closeDialog">Close</button></div>' +
      '<div class="detail-grid"><div><span>Size</span><strong id="detailSize"></strong></div><div><span>Type</span><strong id="detailType"></strong></div><div><span>Modified</span><strong id="detailModified"></strong></div></div>' +
      '<p class="hint">The file stays on the TV until you choose to download it.</p><div class="dialog-actions"><button class="button button-primary" id="downloadDetail">Download</button></div>';
    setText('dialogTitle',entry.name); setText('detailSize',formatBytes(entry.size)); setText('detailType',entry.mimeType || 'Unknown');
    setText('detailModified',entry.lastModified ? formatDate(entry.lastModified) : 'Unknown');
    document.getElementById('closeDialog').addEventListener('click', closeDialog);
    document.getElementById('downloadDetail').addEventListener('click', function () { downloadEntry(entry); });
    openDialog();
  }

  function showEntryActions(entry) {
    var buttons = [];
    if (!entry.isDirectory) buttons.push('<button class="button button-soft" data-action="open">Preview / Open</button><button class="button button-soft" data-action="download">Download</button>');
    buttons.push('<button class="button button-soft" data-action="rename">Rename</button><button class="button button-danger" data-action="delete">Delete</button>');
    el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">ITEM ACTIONS</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="closeDialog">Close</button></div><div class="action-stack">' + buttons.join('') + '</div>';
    setText('dialogTitle',entry.name);
    document.getElementById('closeDialog').addEventListener('click', closeDialog);
    el.overlaySheet.querySelectorAll('[data-action]').forEach(function (button) {
      button.addEventListener('click', function () {
        var action = button.getAttribute('data-action'); closeDialog();
        if (action === 'open') openEntry(entry);
        else if (action === 'download') downloadEntry(entry);
        else if (action === 'rename') renameEntry(entry);
        else if (action === 'delete') deleteEntry(entry);
      });
    });
    openDialog();
  }

  function openDialog() { el.overlay.classList.add('show'); }
  function closeDialog() {
    var player = document.getElementById('mediaPlayer');
    if (player) { try { player.pause(); player.removeAttribute('src'); player.load(); } catch (_) {} }
    el.overlay.classList.remove('show');
    el.overlaySheet.replaceChildren();
  }

  function renameEntry(entry) {
    askDialog('Rename item', 'Choose a new name for this file or folder.', entry.name, 'Rename').then(function (value) {
      if (value === null || !value.trim()) return;
      var name = value.trim();
      request('/api/rename?path=' + encodeURIComponent(entry.path) + '&name=' + encodeURIComponent(name)).then(function () {
        toast('Renamed successfully'); loadDirectory(state.path);
      }).catch(toast);
    });
  }

  function deleteEntry(entry) {
    confirmDialog('Delete this item?', '“' + entry.name + '” will be removed from the shared drive. This cannot be undone.', 'Delete').then(function (yes) {
      if (!yes) return;
      request('/api/delete?path=' + encodeURIComponent(entry.path)).then(function () {
        toast('Item deleted'); loadDirectory(state.path);
      }).catch(toast);
    });
  }

  function createFolder() {
    if (!state.path) { toast('Open a drive before creating a folder.'); return; }
    askDialog('Create a folder', 'Give the new folder a name.', 'New folder', 'Create').then(function (value) {
      if (value === null || !value.trim()) return;
      request('/api/mkdir?path=' + encodeURIComponent(state.path) + '&name=' + encodeURIComponent(value.trim())).then(function () {
        toast('Folder created'); loadDirectory(state.path);
      }).catch(toast);
    });
  }

  function askDialog(title, description, initial, actionLabel) {
    return new Promise(function (resolve) {
      el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">LANVAULT</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="cancelDialog">Cancel</button></div>' +
        '<p class="hint">' + escapeHtml(description) + '</p><form id="valueDialogForm"><label class="dialog-label" for="dialogInput">Name</label><input type="text" id="dialogInput" required maxlength="255" /><div class="dialog-actions"><button type="button" class="button button-soft" id="cancelDialogBottom">Cancel</button><button type="submit" class="button button-primary">' + escapeHtml(actionLabel) + '</button></div></form>';
      setText('dialogTitle',title); var input = document.getElementById('dialogInput'); input.value = initial || '';
      var done = false;
      function finish(value) { if (done) return; done = true; closeDialog(); resolve(value); }
      document.getElementById('cancelDialog').addEventListener('click',function(){finish(null);});
      document.getElementById('cancelDialogBottom').addEventListener('click',function(){finish(null);});
      document.getElementById('valueDialogForm').addEventListener('submit',function(event){event.preventDefault();finish(input.value);});
      openDialog(); window.setTimeout(function(){input.focus();input.select();},30);
    });
  }

  function confirmDialog(title, description, actionLabel) {
    return new Promise(function (resolve) {
      el.overlaySheet.innerHTML = '<div class="dialog-heading"><div><span class="section-kicker">PLEASE CONFIRM</span><h2 id="dialogTitle"></h2></div><button class="button button-soft" id="cancelDialog">Close</button></div><p class="hint" id="dialogDescription"></p><div class="dialog-actions"><button class="button button-soft" id="cancelConfirm">Cancel</button><button class="button button-danger" id="confirmAction"></button></div>';
      setText('dialogTitle',title); setText('dialogDescription',description); setText('confirmAction',actionLabel);
      function finish(value) { closeDialog(); resolve(value); }
      document.getElementById('cancelDialog').addEventListener('click',function(){finish(false);});
      document.getElementById('cancelConfirm').addEventListener('click',function(){finish(false);});
      document.getElementById('confirmAction').addEventListener('click',function(){finish(true);});
      openDialog();
    });
  }

  function bindDrop(target) {
    ['dragenter','dragover'].forEach(function (name) {
      target.addEventListener(name,function(event){event.preventDefault();target.classList.add('active');});
    });
    ['dragleave','drop'].forEach(function (name) {
      target.addEventListener(name,function(event){event.preventDefault();target.classList.remove('active');});
    });
    target.addEventListener('drop',function(event){transferFiles(Array.prototype.slice.call((event.dataTransfer && event.dataTransfer.files) || []));});
  }

  function transferFiles(files) {
    if (!files.length) return;
    if (!state.path) { toast('Choose a drive or folder before uploading.'); return; }
    if (state.uploadBusy) { toast('A transfer is already running.'); return; }
    state.uploadBusy = true;
    var target = state.path, next = 0, finished = 0;
    el.uploadProgress.style.display = 'block';
    el.uploadProgress.setAttribute('aria-valuenow','0');
    el.uploadProgress.firstElementChild.style.width = '0%';
    el.dropzone.classList.add('active');
    function uploadNext() {
      if (next >= files.length) {
        state.uploadBusy = false; el.dropzone.classList.remove('active');
        el.uploadProgress.firstElementChild.style.width = '100%';
        toast('Transfer complete · ' + files.length + ' file(s)');
        window.setTimeout(function(){el.uploadProgress.style.display='none';},700);
        loadDirectory(target); return;
      }
      var file = files[next++], requestUpload = new XMLHttpRequest();
      requestUpload.open('PUT','/api/put?path=' + encodeURIComponent(target) + '&name=' + encodeURIComponent(file.name));
      requestUpload.upload.onprogress = function (event) {
        if (event.lengthComputable) {
          var total = event.total || 1;
          var percent = Math.round(((finished + event.loaded / total) / files.length) * 100);
          el.uploadProgress.firstElementChild.style.width = percent + '%';
          el.uploadProgress.setAttribute('aria-valuenow',String(percent));
        }
      };
      requestUpload.onload = function () {
        if (requestUpload.status >= 200 && requestUpload.status < 300) { finished++; uploadNext(); }
        else {
          state.uploadBusy=false; el.dropzone.classList.remove('active'); el.uploadProgress.style.display='none';
          toast(file.name + ': ' + parseUploadError(requestUpload.responseText,requestUpload.status));
        }
      };
      requestUpload.onerror = function () {
        state.uploadBusy=false; el.dropzone.classList.remove('active'); el.uploadProgress.style.display='none';
        toast(file.name + ': connection lost during transfer.');
      };
      requestUpload.send(file);
    }
    uploadNext();
  }

  function parseUploadError(text,status) {
    try { return JSON.parse(text).error || ('upload failed (' + status + ')'); } catch (_) { return 'upload failed (' + status + ')'; }
  }

  function renderTransfers() {
    el.view.innerHTML = '<section class="panel transfer-panel"><span class="section-kicker">LOCAL TRANSFERS</span><h2>Bring files onto your TV.</h2>' +
      '<p class="hint">Uploads are written straight to the selected drive. Playback and downloads stream from the TV instead of buffering the entire file first.</p>' +
      '<div class="transfer-destination"><span class="transfer-destination-icon">▤</span><div><strong>Destination folder</strong><span id="transferDestination"></span></div><button class="button button-soft" id="transferBrowse">Choose folder</button></div>' +
      '<div class="transfer-drop" id="transferDrop"><span class="transfer-drop-symbol">↑</span><strong>Drop files here</strong><span>or choose files from this device</span><button class="button button-primary" id="pickFiles">Choose files</button></div>' +
      '<div class="transfer-notes"><div><strong>Direct transfer</strong><p>File contents are streamed over your LAN.</p></div><div><strong>Local by default</strong><p>Files are not sent to a cloud account.</p></div></div></section>';
    setText('transferDestination',state.path || 'No folder selected');
    document.getElementById('pickFiles').addEventListener('click',function(){el.filePicker.click();});
    document.getElementById('transferBrowse').addEventListener('click',function(){location.hash='#/browse';});
    bindDrop(document.getElementById('transferDrop'));
  }

  function renderSettings() {
    var s = state.settings || {}, info = state.info || {}, ips = info.ipv4Addresses || [];
    var reachable = location.hostname;
    var smb = info.smbServer || {};
    var html = '<section class="panel settings-panel"><div class="settings-intro"><span class="section-kicker">PREFERENCES</span><h2>One place for all the controls.</h2><p class="hint">Settings apply to the server running on this TV. Your data stays on the local network.</p></div>' +
      '<div class="settings-group"><h3>Connect to LANVault</h3><p class="hint">Use the browser address for the dashboard. Use SMB from the iPhone Files app.</p>' +
      addressCards('Web dashboard',ips,function(ip){return 'http://' + ip + ':' + (info.port || 8080) + '/ui/';},reachable) +
      smbCards(ips,smb,reachable) +
      addressCards('WebDAV · compatible clients',ips,function(ip){return 'http://' + ip + ':' + (info.port || 8080) + '/';},reachable) +
      '<div class="settings-callout"><strong>From iPhone Files</strong><p id="iphoneHowTo">' + escapeHtml(iphoneInstructions(info,smb,reachable)) + '</p></div></div>' +
      '<form id="settingsForm"><div class="settings-group"><h3>Server identity</h3>' +
      fieldNumber('serverPort','Web and WebDAV port','A restart is required if you change this port.',s.serverPort || 8080) +
      fieldText('deviceName','Server name','The name shown to clients.',s.deviceName || 'LANVault') +
      '</div><div class="settings-group"><h3>Sharing protocols</h3>' +
      fieldToggle('shareWebdav','WebDAV for compatible clients','Keep the HTTP WebDAV endpoint available.',s.shareWebdav) +
      fieldToggle('shareSmb','Native iPhone Files (SMB2/3)','Expose TV and USB volumes to Files → Connect to Server.',s.shareSmb) +
      '</div><div class="settings-group"><h3>Access and security</h3>' +
      fieldToggle('requireAuth','Require login','Protect browser/API access and SMB shares.',s.requireAuth) +
      fieldText('username','Username','Used when registered-user authentication is enabled.',s.username || 'lanvault') +
      fieldPassword('password','Password','Choose a unique password for your local server.',s.password || '') +
      '<p class="settings-warning">A trusted local network is still important. Avoid exposing these ports directly to the public internet.</p>' +
      '</div><div class="settings-group"><h3>File permissions</h3>' +
      fieldToggle('allowUploads','Allow uploads','Let clients copy new files onto writable drives.',s.allowUploads) +
      fieldToggle('allowDeletes','Allow deletions','Let clients delete files from shared storage.',s.allowDeletes) +
      fieldToggle('allowCreateFolders','Allow creating folders','Let clients create folders inside writable drives.',s.allowCreateFolders) +
      fieldToggle('showHiddenFiles','Show hidden files','Include files and folders whose names start with a dot.',s.showHiddenFiles) +
      '</div><div class="settings-group"><h3>TV behaviour</h3>' +
      fieldToggle('autoStartOnBoot','Start after the TV boots','Run the sharing server automatically.',s.autoStartOnBoot) +
      fieldToggle('keepCpuAwake','Keep transfers awake','Try to keep the CPU awake while sharing.',s.keepCpuAwake) +
      '</div><div class="settings-actions"><button type="submit" class="button button-primary" id="saveSettings">Save preferences</button><button type="button" class="button button-soft" id="restartServer">Restart server</button><button type="button" class="button button-soft" id="clearCache">Clear cache</button></div><p class="settings-note" id="settingsNote" role="status"></p></form></section>';
    el.view.innerHTML = html;

    document.getElementById('settingsForm').addEventListener('submit',function(event){
      event.preventDefault();
      var values = {};
      ['serverPort','deviceName','requireAuth','username','password','allowUploads','allowDeletes','allowCreateFolders','showHiddenFiles','autoStartOnBoot','keepCpuAwake','shareWebdav','shareSmb'].forEach(function (id) {
        var node=document.getElementById(id);
        values[id] = node.type === 'checkbox' ? node.checked : node.value;
      });
      var button=document.getElementById('saveSettings'); button.disabled=true;
      postForm('/api/settings',values).then(function(payload){
        state.settings=payload.settings || state.settings;
        setText('settingsNote',payload.restartRequired ? 'Saved. Restart the server to apply the new port.' : 'Preferences saved.');
        toast('Settings saved'); refreshState();
      }).catch(function(error){setText('settingsNote',error.message);}).then(function(){button.disabled=false;});
    });
    document.getElementById('restartServer').addEventListener('click',function(){
      postJson('/api/restart',{}).then(function(){toast('Server restart requested');window.setTimeout(refreshState,900);}).catch(toast);
    });
    document.getElementById('clearCache').addEventListener('click',function(){
      postJson('/api/clear-cache',{}).then(function(){toast('Temporary cache cleared');}).catch(toast);
    });
  }

  function addressCards(label,ips,makeUrl,reachable) {
    if (!ips.length) return '<div class="link-box"><span>' + escapeHtml(label) + '</span><strong>No network address detected</strong></div>';
    return ips.map(function(ip){
      var url=makeUrl(ip), works=(ip===reachable);
      return '<div class="link-box"><div class="address-copy"><span>' + escapeHtml(label) + '</span><code>' + escapeHtml(url) + '</code></div>' +
        (works ? '<span class="address-tag">THIS DEVICE</span>' : '') +
        '<button class="button button-soft small" data-copy="' + escapeHtml(url) + '">Copy</button></div>';
    }).join('');
  }

  function smbCards(ips,smb,reachable) {
    if (!smb.enabled) return '<div class="link-box"><div class="address-copy"><span>iPhone Files · SMB2/3</span><code>Enable SMB in the server preferences.</code></div></div>';
    if (!smb.running || !smb.port) return '<div class="link-box"><div class="address-copy"><span>iPhone Files · SMB2/3</span><code>' + escapeHtml(smb.message || 'SMB server is starting.') + '</code></div></div>';
    return ips.map(function(ip){
      var url='smb://' + ip + (Number(smb.port) === 445 ? '' : ':' + smb.port);
      return '<div class="link-box"><div class="address-copy"><span>iPhone Files · SMB2/3</span><code>' + escapeHtml(url) + '</code></div>' +
        (ip===reachable ? '<span class="address-tag">THIS DEVICE</span>' : '') +
        '<button class="button button-soft small" data-copy="' + escapeHtml(url) + '">Copy</button></div>';
    }).join('') + '<p class="hint">Files → Browse → ••• → Connect to Server. Use Guest if login is disabled, or Registered User when authentication is enabled.</p>';
  }

  function iphoneInstructions(info,smb,reachable) {
    var ip=reachable || ((info.ipv4Addresses || [])[0]) || '<tv-ip>';
    if (!smb.enabled) return 'Enable the SMB2/3 server in LANVault preferences.';
    if (!smb.running || !smb.port) return smb.message || 'SMB server is starting.';
    var url='smb://' + ip + (Number(smb.port)===445 ? '' : ':' + smb.port);
    return 'On iPhone: Files → Browse → ••• → Connect to Server. Enter ' + url + '. Choose ' + (info.requiresAuth ? 'Registered User and enter your LANVault username and password.' : 'Guest if prompted.');
  }

  function fieldText(id,label,description,value) {
    return '<div class="setting-field"><div><label for="' + id + '">' + escapeHtml(label) + '</label><p>' + escapeHtml(description || '') + '</p></div><input type="text" id="' + id + '" value="' + escapeHtml(value == null ? '' : value) + '" /></div>';
  }
  function fieldPassword(id,label,description,value) {
    return '<div class="setting-field"><div><label for="' + id + '">' + escapeHtml(label) + '</label><p>' + escapeHtml(description || '') + '</p></div><input type="password" id="' + id + '" autocomplete="new-password" value="' + escapeHtml(value == null ? '' : value) + '" /></div>';
  }
  function fieldNumber(id,label,description,value) {
    return '<div class="setting-field"><div><label for="' + id + '">' + escapeHtml(label) + '</label><p>' + escapeHtml(description || '') + '</p></div><input type="number" min="1024" max="65535" id="' + id + '" value="' + escapeHtml(value == null ? '' : value) + '" /></div>';
  }
  function fieldToggle(id,label,description,value) {
    return '<div class="setting-field setting-toggle"><div><label for="' + id + '">' + escapeHtml(label) + '</label><p>' + escapeHtml(description || '') + '</p></div><label class="toggle-control"><input type="checkbox" id="' + id + '"' + (value ? ' checked' : '') + ' /><span class="toggle-track"></span></label></div>';
  }

  function renderLogs() {
    el.view.innerHTML = '<section class="panel log-panel"><div class="section-header-row"><div><span class="section-kicker">SERVER EVENTS</span><h2>Activity log</h2><p class="hint">Recent events reported by the server running on the TV.</p></div><button class="button button-soft" id="refreshLogs">Refresh</button></div><pre class="log-view" id="logBox">Loading recent events…</pre></section>';
    document.getElementById('refreshLogs').addEventListener('click',loadLogs);
    loadLogs();
  }
  function loadLogs() {
    var box=document.getElementById('logBox'); if (!box) return;
    box.textContent='Loading recent events…';
    request('/api/logs').then(function(payload){
      var lines=payload.lines || [];
      box.textContent=lines.length ? lines.map(function(line){
        return '[' + formatTime(line.timestamp) + '] ' + String(line.level || 'info').toUpperCase() + '  ' + line.message;
      }).join('\n') : 'No events have been recorded yet.';
    }).catch(function(error){box.textContent=error.message;});
  }

  function renderMonitoring() {
    el.view.innerHTML = '<div class="monitor-page">' +
      '<section class="monitor-section monitor-intro"><div><span class="section-kicker">LIVE SYSTEM OVERVIEW</span><h2>Network health</h2><p class="hint">Understand the TV, check local services, and manage AdGuard integrations.</p></div><button class="button button-primary" id="monitorRefresh">↻ Refresh</button></section>' +
      '<div id="monitorAuthWarning" class="monitor-alert" hidden></div>' +
      '<section class="monitor-section"><div class="monitor-heading"><div><h3>TV gateway</h3><p class="hint">Available Android network, memory and thermal signals.</p></div><span class="monitor-badge" id="localBadge">Checking</span></div><div class="monitor-cards" id="monitorLocalCards"></div><details class="monitor-details"><summary>Interfaces and router neighbours</summary><pre class="monitor-json" id="monitorLocalDetails"></pre></details><div class="monitor-proxy-form"><label for="monitorProxyPort">Every Proxy TCP port <span>0 disables the check</span></label><div class="monitor-form-row"><input type="number" id="monitorProxyPort" min="0" max="65535" placeholder="e.g. 8080" /><button class="button button-soft" id="monitorProxySave">Save check</button></div><p class="hint" id="monitorProxyMessage">This checks a localhost TCP listener; it does not change Every Proxy.</p></div></section>' +
      '<section class="monitor-section"><div class="monitor-heading"><div><h3>AdGuard Home · built into this TV</h3><p class="hint">The bundled server is a separate process. Start it here and open its dashboard for first-time setup.</p></div><span class="monitor-badge" id="aghRuntimeBadge">Checking</span></div><div class="monitor-cards" id="aghRuntimeCards"></div><p class="hint" id="aghRuntimeMessage">Checking the bundled server…</p><div class="monitor-actions"><button class="button button-primary" id="aghRuntimeStart">Start service</button><button class="button button-soft" id="aghRuntimeStop">Stop service</button><button class="button button-soft" id="aghRuntimeCredentialsButton">Show first-run login</button><label class="monitor-inline-check"><input type="checkbox" id="aghRuntimeAutostart" /> Start with LANVault</label></div><pre class="monitor-json" id="aghRuntimeCredentials" hidden></pre><div id="aghRuntimeLinks" class="monitor-link-list"></div><details class="monitor-details"><summary>Service output</summary><pre class="monitor-json" id="aghRuntimeLog"></pre></details><p class="monitor-disclaimer">This build uses DNS port 53535 to avoid requiring root. A typical router expects port 53; do not point router DNS here until DNS is confirmed reachable and you have a working forwarding or privileged-port configuration.</p></section>' +
      '<section class="monitor-section"><div class="monitor-heading"><div><h3>Another AdGuard Home</h3><p class="hint">Optional connection to an existing server elsewhere on your network.</p></div><span class="monitor-badge" id="monitorHomeBadge">Not configured</span></div><div class="monitor-form-grid"><div class="field"><div><label for="monitorHomeUrl">Base URL</label><div class="desc">Example: http://192.168.1.20:3000</div></div><input type="text" id="monitorHomeUrl" placeholder="http://192.168.1.20:3000" /></div><div class="field"><div><label for="monitorHomeUser">Username</label></div><input type="text" id="monitorHomeUser" autocomplete="username" /></div><div class="field"><div><label for="monitorHomePassword">Password</label><div class="desc">Saved encrypted on the TV.</div></div><input type="password" id="monitorHomePassword" autocomplete="current-password" /></div></div><div class="monitor-actions"><button class="button button-primary" id="monitorHomeSave">Save and test</button><button class="button button-soft" id="monitorHomeForget">Disconnect</button></div><p class="hint" id="monitorHomeMessage">Not connected.</p><div class="monitor-cards" id="monitorHomeCards"></div><div class="monitor-two-col"><div><h4>Clients</h4><pre class="monitor-json" id="monitorHomeClients">No data.</pre></div><div><h4>Recent DNS queries</h4><pre class="monitor-json" id="monitorHomeQueries">No data.</pre></div></div><details class="monitor-details"><summary>Raw response</summary><pre class="monitor-json" id="monitorHomeRaw"></pre></details></section>' +
      '<section class="monitor-section"><div class="monitor-heading"><div><h3>AdGuard DNS account</h3><p class="hint">Read-only sync of your current cloud profiles, devices, and available statistics.</p></div><span class="monitor-badge" id="monitorCloudBadge">Signed out</span></div><div id="monitorCloudSignIn"><div class="monitor-form-grid"><div class="field"><div><label for="monitorCloudEmail">Account email</label></div><input type="email" id="monitorCloudEmail" autocomplete="username" /></div><div class="field"><div><label for="monitorCloudPassword">Password</label></div><input type="password" id="monitorCloudPassword" autocomplete="current-password" /></div><div class="field"><div><label for="monitorCloudMfa">Two-factor code</label><div class="desc">Only if your account uses 2FA.</div></div><input type="text" id="monitorCloudMfa" inputmode="numeric" autocomplete="one-time-code" /></div></div><div class="monitor-actions"><button class="button button-primary" id="monitorCloudSignInButton">Sign in</button></div></div><div id="monitorCloudConnected" hidden><p class="hint" id="monitorCloudMessage"></p><div class="monitor-actions"><button class="button button-primary" id="monitorCloudSync">Sync now</button><button class="button button-soft" id="monitorCloudSignOut">Sign out</button></div></div><div class="monitor-cards" id="monitorCloudCards"></div><div class="monitor-two-col"><div><h4>DNS profiles</h4><pre class="monitor-json" id="monitorCloudServers">No synced data.</pre></div><div><h4>Registered devices</h4><pre class="monitor-json" id="monitorCloudDevices">No synced data.</pre></div><div><h4>Queries by device · 24 hours</h4><pre class="monitor-json" id="monitorCloudDeviceStats">No synced data.</pre></div><div><h4>Top domains · 24 hours</h4><pre class="monitor-json" id="monitorCloudDomainStats">No synced data.</pre></div></div><details class="monitor-details"><summary>Time-series stats · 24 hours</summary><pre class="monitor-json" id="monitorCloudTimeStats"></pre></details></section>' +
      '<section class="monitor-section"><h3>Bandwidth is different from DNS</h3><p class="hint">DNS statistics are not upload/download totals. Per-device bandwidth needs router traffic counters, an API, or flow telemetry. LANVault reports only counters Android actually exposes and does not invent per-device totals.</p></section></div>';

    document.getElementById('monitorRefresh').addEventListener('click',refreshMonitoring);
    document.getElementById('aghRuntimeStart').addEventListener('click',startEmbeddedAdGuard);
    document.getElementById('aghRuntimeStop').addEventListener('click',stopEmbeddedAdGuard);
    document.getElementById('aghRuntimeCredentialsButton').addEventListener('click',showEmbeddedCredentials);
    document.getElementById('aghRuntimeAutostart').addEventListener('change',saveAdGuardAutostart);
    document.getElementById('monitorHomeSave').addEventListener('click',saveHomeConnection);
    document.getElementById('monitorHomeForget').addEventListener('click',forgetHomeConnection);
    document.getElementById('monitorCloudSignInButton').addEventListener('click',signInCloud);
    document.getElementById('monitorCloudSync').addEventListener('click',syncCloud);
    document.getElementById('monitorCloudSignOut').addEventListener('click',signOutCloud);
    document.getElementById('monitorProxySave').addEventListener('click',saveProxyCheck);
    refreshMonitoring();
    monitorTimer=window.setInterval(refreshMonitoring,15000);
  }

  function monitorRequest(path,method,body) {
    if (!method) return request(path);
    return postJson(path,body);
  }

  function refreshMonitoring() {
    return monitorRequest('/api/monitoring/status').then(function(data){
      var cards=[
        ['Android VPN',data.vpnActive?'Active':'Not detected'],
        ['Free system memory',data.systemMemoryAvailableMb==null?'Unavailable':formatNumber(data.systemMemoryAvailableMb)+' MB'],
        ['App heap',formatNumber(data.appHeapUsedMb)+' / '+formatNumber(data.appHeapMaxMb)+' MB'],
        ['Highest exposed temperature',data.thermal&&data.thermal.celsius!=null?data.thermal.celsius+' °C':'Not exposed'],
        ['Every Proxy',data.everyProxyPort>0?(data.everyProxyListening?'Listener detected':'Not reachable'):'Not configured']
      ];
      renderMetricCards('monitorLocalCards',cards);
      setText('monitorLocalDetails',JSON.stringify({activeTransports:data.activeTransports,uptimeSeconds:data.uptimeSeconds,thermal:data.thermal,networkInterfaces:data.networkInterfaces,routerNeighbors:data.routerNeighbors},null,2));
      setValue('monitorProxyPort',data.everyProxyPort || 0);
      setText('localBadge','Updated');
      var warning=document.getElementById('monitorAuthWarning'); if(warning) warning.hidden=true;
      return refreshEmbeddedAdGuard().then(function(){return monitorRequest('/api/monitoring/home');});
    }).then(function(home){
      if(!document.getElementById('monitorHomeBadge')) return;
      setText('monitorHomeBadge',!home.configured?'Not configured':(home.connected?'Connected':'Connection issue'));
      setText('monitorHomeMessage',home.message || 'No connection status.');
      var stats=home.stats || {};
      renderMetricCards('monitorHomeCards',[
        ['DNS queries',stats.num_dns_queries],['Blocked by filters',stats.num_blocked_filtering],
        ['Blocked malware',stats.num_replaced_safebrowsing],
        ['Average processing',stats.avg_processing_time==null?'—':Number(stats.avg_processing_time).toFixed(3)+' s']
      ]);
      setText('monitorHomeClients',JSON.stringify(home.clients,null,2) || 'No client data.');
      setText('monitorHomeQueries',JSON.stringify(home.queryLog,null,2) || 'No query log.');
      setText('monitorHomeRaw',JSON.stringify(home,null,2));
      if(home.baseUrl) setValue('monitorHomeUrl',home.baseUrl);
      return monitorRequest('/api/monitoring/cloud');
    }).then(renderCloudStatus).catch(function(error){
      var warning=document.getElementById('monitorAuthWarning');
      if(warning){warning.hidden=false;warning.textContent=error.message+' Set a unique, non-default username and password in LANVault Settings before using monitoring integrations.';}
      setText('monitorHomeMessage',error.message);setText('monitorCloudMessage',error.message);
    });
  }

  function renderMetricCards(id,cards) {
    var node=document.getElementById(id); if(!node)return;
    node.innerHTML=cards.map(function(card){return '<div class="monitor-card"><span>'+escapeHtml(card[0])+'</span><strong>'+escapeHtml(card[1]==null?'—':String(card[1]))+'</strong></div>';}).join('');
  }

  function refreshEmbeddedAdGuard() {
    return monitorRequest('/api/monitoring/embedded').then(function(data){
      setText('aghRuntimeBadge',data.running?(data.adminReachable?'Running':'Starting'):(data.binaryBundled?'Stopped':'Binary unavailable'));
      setText('aghRuntimeMessage',data.message || 'No runtime status.');
      setChecked('aghRuntimeAutostart',!!data.autoStart);
      setDisabled('aghRuntimeStart',!!data.running || !data.binaryBundled || !data.supportedAbi);
      setDisabled('aghRuntimeStop',!data.running);
      setDisabled('aghRuntimeCredentialsButton',!data.adminCredentialsAvailable);
      renderMetricCards('aghRuntimeCards',[
        ['Bundled version',data.version || '—'],['Android ABI',data.abi || 'Unknown'],
        ['Admin dashboard',data.adminReachable?'Reachable':'Not reachable'],
        ['DNS port '+String(data.dnsPort || 53535),data.dnsTcpReachable?'TCP reachable':'Not detected']
      ]);
      var links=document.getElementById('aghRuntimeLinks');
      links.innerHTML=(data.adminUrls || []).map(function(url){
        return '<a class="monitor-link" href="'+escapeHtml(url)+'" target="_blank" rel="noopener">Open AdGuard Home dashboard <span>'+escapeHtml(url)+'</span></a>';
      }).join('') || '<p class="hint">Start AdGuard Home to enable its setup dashboard on port '+escapeHtml(data.adminPort || 3000)+'.</p>';
      setText('aghRuntimeLog',data.logTail || 'No output yet.');
      return data;
    });
  }

  function startEmbeddedAdGuard() {
    setText('aghRuntimeMessage','Starting the bundled service…');setDisabled('aghRuntimeStart',true);
    monitorRequest('/api/monitoring/embedded/start','POST',{}).then(function(){toast('Start requested');return refreshEmbeddedAdGuard();})
      .catch(function(error){setText('aghRuntimeMessage',error.message);}).then(function(){setDisabled('aghRuntimeStart',false);});
  }
  function stopEmbeddedAdGuard() {
    setDisabled('aghRuntimeStop',true);
    monitorRequest('/api/monitoring/embedded/stop','POST',{}).then(function(){toast('AdGuard Home stopped');return refreshEmbeddedAdGuard();})
      .catch(function(error){setText('aghRuntimeMessage',error.message);}).then(function(){setDisabled('aghRuntimeStop',false);});
  }
  function showEmbeddedCredentials() {
    var target=document.getElementById('aghRuntimeCredentials');
    target.hidden=false;target.textContent='Loading encrypted first-run credentials…';
    monitorRequest('/api/monitoring/embedded/credentials','POST',{}).then(function(data){
      target.textContent=data.available?'Username: '+data.username+'\nPassword: '+data.password+'\n\nSign in to the dashboard and change this password.':(data.message || 'No generated login is available.');
    }).catch(function(error){target.textContent=error.message;});
  }
  function saveAdGuardAutostart() {
    var enabled=document.getElementById('aghRuntimeAutostart').checked;
    monitorRequest('/api/monitoring/embedded/autostart','POST',{enabled:enabled}).then(function(){toast(enabled?'AdGuard Home will start with LANVault':'Auto-start disabled');}).catch(toast);
  }
  function saveHomeConnection() {
    var button=document.getElementById('monitorHomeSave');button.disabled=true;
    monitorRequest('/api/monitoring/home/config','POST',{baseUrl:document.getElementById('monitorHomeUrl').value,username:document.getElementById('monitorHomeUser').value,password:document.getElementById('monitorHomePassword').value})
      .then(function(){setValue('monitorHomePassword','');toast('Connection settings saved');return refreshMonitoring();})
      .catch(function(error){setText('monitorHomeMessage',error.message);}).then(function(){button.disabled=false;});
  }
  function forgetHomeConnection() {
    confirmDialog('Disconnect this AdGuard Home?','Saved connection credentials will be erased from this TV.','Disconnect').then(function(yes){
      if(!yes)return;return monitorRequest('/api/monitoring/home/forget','POST',{}).then(function(){setValue('monitorHomePassword','');toast('Connection removed');refreshMonitoring();});
    }).catch(toast);
  }
  function signInCloud() {
    var button=document.getElementById('monitorCloudSignInButton');button.disabled=true;
    monitorRequest('/api/monitoring/cloud/login','POST',{email:document.getElementById('monitorCloudEmail').value,password:document.getElementById('monitorCloudPassword').value,mfaCode:document.getElementById('monitorCloudMfa').value})
      .then(function(cloud){setValue('monitorCloudPassword','');renderCloudStatus(cloud);toast('AdGuard account response received');refreshMonitoring();})
      .catch(function(error){setText('monitorCloudMessage',error.message);toast(error.message);}).then(function(){button.disabled=false;});
  }
  function syncCloud() {
    var button=document.getElementById('monitorCloudSync');button.disabled=true;
    monitorRequest('/api/monitoring/cloud/refresh','POST',{}).then(function(data){renderCloudStatus(data);toast('Account data refreshed');}).catch(toast).then(function(){button.disabled=false;});
  }
  function signOutCloud() {
    confirmDialog('Sign out of AdGuard DNS?','Saved session tokens will be erased from this TV.','Sign out').then(function(yes){
      if(!yes)return;return monitorRequest('/api/monitoring/cloud/logout','POST',{}).then(function(){toast('Signed out');refreshMonitoring();});
    }).catch(toast);
  }
  function saveProxyCheck() {
    var button=document.getElementById('monitorProxySave');button.disabled=true;
    var port=parseInt(document.getElementById('monitorProxyPort').value || '0',10);
    monitorRequest('/api/monitoring/proxy','POST',{port:port}).then(function(data){
      setText('monitorProxyMessage',data.listening?'TCP listener detected on 127.0.0.1:'+data.port+'.':(Number(data.port)===0?'Listener check disabled.':'No listener on 127.0.0.1:'+data.port+'.'));
      refreshMonitoring();
    }).catch(function(error){setText('monitorProxyMessage',error.message);}).then(function(){button.disabled=false;});
  }

  function renderCloudStatus(cloud) {
    if(!document.getElementById('monitorCloudBadge'))return;
    var connected=!!cloud.connected;
    setText('monitorCloudBadge',connected?'Connected':'Signed out');
    var signIn=document.getElementById('monitorCloudSignIn'),connectedNode=document.getElementById('monitorCloudConnected');
    signIn.hidden=connected;connectedNode.hidden=!connected;
    if(!connected){
      setText('monitorCloudMessage',cloud.message || 'Sign in to sync your DNS account.');
      renderMetricCards('monitorCloudCards',[]);
      ['monitorCloudServers','monitorCloudDevices','monitorCloudDeviceStats','monitorCloudDomainStats','monitorCloudTimeStats'].forEach(function(id){setText(id,'No synced data.');});
      return;
    }
    setText('monitorCloudMessage',(cloud.email || 'AdGuard account')+' · '+(cloud.message || 'Synced'));
    renderMetricCards('monitorCloudCards',[
      ['DNS profiles',countCollection(cloud.dnsServers)],['Devices',countCollection(cloud.devices)],
      ['Device queries',countStats(cloud.deviceStats24h)],['Domain queries',countStats(cloud.domainStats24h)]
    ]);
    showCollection('monitorCloudServers',cloud.dnsServers);showCollection('monitorCloudDevices',cloud.devices);
    showCollection('monitorCloudDeviceStats',cloud.deviceStats24h);showCollection('monitorCloudDomainStats',cloud.domainStats24h);
    showCollection('monitorCloudTimeStats',cloud.timeStats24h);
  }
  function showCollection(id,result) {
    if(!result || !result.ok){setText(id,result&&result.error?result.error:'No synced data.');return;}
    setText(id,JSON.stringify(result.data,null,2));
  }
  function countCollection(result) {
    if(!result || !result.ok || result.data==null)return 'Unavailable';
    var data=result.data;
    if(Array.isArray(data))return data.length;
    if(Array.isArray(data.items))return data.items.length;
    if(Array.isArray(data.data))return data.data.length;
    if(Array.isArray(data.dns_servers))return data.dns_servers.length;
    if(Array.isArray(data.devices))return data.devices.length;
    return 'Synced';
  }
  function countStats(result) {
    if(!result || !result.ok || result.data==null)return 'Unavailable';
    var data=result.data,items=Array.isArray(data)?data:(data.data || data.items || data.stats || []);
    if(!Array.isArray(items))return 'Synced';
    return items.reduce(function(total,item){
      var count=Number(item.queries!=null?item.queries:(item.query_count!=null?item.query_count:item.count));
      return total+(Number.isFinite(count)?count:0);
    },0);
  }

  function encodeHashPath(path) { return String(path || '').split('/').map(encodeURIComponent).join('/'); }
  function formatBytes(value) {
    var number=Number(value || 0);if(!Number.isFinite(number) || number<1)return '0 B';
    var units=['B','KB','MB','GB','TB'],index=Math.min(units.length-1,Math.floor(Math.log(number)/Math.log(1024)));
    var amount=number/Math.pow(1024,index);
    return (amount>=10 || index===0?Math.round(amount):amount.toFixed(1))+' '+units[index];
  }
  function formatDate(value) {
    var date=new Date(Number(value));if(!Number.isFinite(date.getTime()))return '—';
    return date.toLocaleDateString([], {month:'short',day:'numeric',year:'numeric'})+' · '+date.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
  }
  function formatTime(value) {
    var date=new Date(Number(value));return Number.isFinite(date.getTime())?date.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'}):'--:--:--';
  }
  function formatNumber(value) {
    var num=Number(value);return Number.isFinite(num)?String(Math.round(num*10)/10):'—';
  }
  function extensionOf(name) { return String(name || '').split('.').pop().toLowerCase(); }
  function escapeHtml(value) {
    return String(value===undefined || value===null?'':value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
  }
  function setText(id,value) { var node=typeof id==='string'?document.getElementById(id):id;if(node)node.textContent=value==null?'':String(value); }
  function setValue(id,value) { var node=document.getElementById(id);if(node)node.value=value==null?'':String(value); }
  function setChecked(id,value) { var node=document.getElementById(id);if(node)node.checked=!!value; }
  function setDisabled(id,value) { var node=document.getElementById(id);if(node)node.disabled=!!value; }
  function toast(value) {
    if(!el.toast)return;
    el.toast.textContent=typeof value==='string'?value:(value&&value.message)||'Something went wrong';
    el.toast.classList.add('show');window.clearTimeout(toastTimer);
    toastTimer=window.setTimeout(function(){el.toast.classList.remove('show');},3000);
  }
  document.addEventListener('click',function(event){
    var button=event.target.closest('[data-copy]');
    if(!button)return;
    var value=button.getAttribute('data-copy');
    if(navigator.clipboard && navigator.clipboard.writeText){
      navigator.clipboard.writeText(value).then(function(){toast('Address copied');}).catch(function(){copyFallback(value);});
    } else copyFallback(value);
  });
  function copyFallback(value) {
    var input=document.createElement('textarea');input.value=value;input.style.position='fixed';input.style.opacity='0';
    document.body.appendChild(input);input.select();
    try{document.execCommand('copy');toast('Address copied');}catch(_){toast(value);}
    input.remove();
  }
})();