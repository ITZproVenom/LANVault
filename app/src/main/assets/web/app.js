/* LANVault web interface. Talks to /api/* only; streaming and downloads go through
   /api/file and /api/download, both of which honour HTTP Range requests so video and
   audio never have to be fully downloaded. */
(function () {
  'use strict';

  var state = {
    volumes: [],
    settings: {},
    info: {},
    path: '',
    entries: [],
    view: 'grid',
    route: 'browse'
  };

  var els = {};

  document.addEventListener('DOMContentLoaded', function () {
    ['statusDot', 'sidebarFooter', 'volumeList', 'pathBar', 'view', 'notice', 'noticeText',
      'noticeAction', 'dropzone', 'viewToggle', 'newFolderButton', 'uploadButton', 'filePicker',
      'overlay', 'overlaySheet', 'toast', 'uploadProgress'].forEach(function (id) {
      els[id] = document.getElementById(id);
    });
    document.querySelectorAll('[data-nav]').forEach(function (link) {
      link.addEventListener('click', function () {
        document.querySelectorAll('[data-nav]').forEach(function (other) {
          other.classList.remove('active');
        });
        link.classList.add('active');
      });
    });
    els.viewToggle.addEventListener('click', function () {
      state.view = state.view === 'grid' ? 'list' : 'grid';
      els.viewToggle.textContent = state.view === 'grid' ? 'List view' : 'Grid view';
      renderEntries();
    });
    els.newFolderButton.addEventListener('click', createFolder);
    els.uploadButton.addEventListener('click', function () {
      els.filePicker.click();
    });
    els.filePicker.addEventListener('change', function (event) {
      uploadFiles(Array.prototype.slice.call(event.target.files));
      els.filePicker.value = '';
    });
    els.dropzone.addEventListener('click', function () { els.filePicker.click(); });
    bindDragAndDrop();
    els.overlay.addEventListener('click', function (event) {
      if (event.target === els.overlay) closeOverlay();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') closeOverlay();
    });

    window.addEventListener('hashchange', route);
    refresh();
    setInterval(refresh, 6000);
    route();
  });

  function api(path, options) {
    return fetch(path, options).then(function (response) {
      if (response.status === 401) {
        throw new Error('Authentication required. Sign in from the LANVault settings page.');
      }
      if (!response.ok) {
        return response.json().catch(function () {
          throw new Error('Request failed (' + response.status + ')');
        }).then(function (payload) {
          throw new Error(payload.error || 'Request failed (' + response.status + ')');
        });
      }
      return response.json();
    });
  }

  function refresh() {
    api('/api/state').then(function (payload) {
      state.info = payload;
      state.settings = payload.settings || {};
      state.volumes = payload.volumes || [];
      els.statusDot.classList.toggle('offline', !payload.running);
      els.sidebarFooter.textContent = payload.running
        ? 'Port ' + payload.port + ' · ' + payload.volumeCount + ' shared'
        : 'Server offline';
      renderVolumes();
      updateNotice(payload);
      if (state.route === 'browse') renderPathBar();
    }).catch(function (error) {
      els.sidebarFooter.textContent = error.message;
    });
  }

  function updateNotice(info) {
    if (info.allFilesAccess) {
      els.notice.classList.remove('show');
      return;
    }
    els.noticeText.textContent =
      'This TV has not granted full storage access. Some drives cannot be listed yet.';
    els.noticeAction.textContent = 'Open drive picker';
    els.noticeAction.onclick = function () {
      api('/api/storage?action=pick').then(function () {
        toast('Use the TV remote to choose the drive, then reload.');
      }).catch(toast);
    };
    els.notice.classList.add('show');
  }

  function route() {
    var hash = location.hash.replace(/^#\/?/, '');
    var parts = hash.split('/');
    var name = parts[0] || 'browse';
    document.querySelectorAll('[data-nav]').forEach(function (link) {
      link.classList.toggle('active', link.getAttribute('data-nav') === name);
    });
    state.route = name === 'settings' || name === 'logs' || name === 'uploads' ? name : 'browse';
    if (state.route === 'browse') {
      state.path = decodeURIComponent(parts.slice(1).join('/') || '');
      els.pathBar.style.display = '';
      loadDirectory(state.path);
    } else {
      els.pathBar.style.display = 'none';
    }
    render();
  }

  function render() {
    if (state.route === 'settings') renderSettings();
    else if (state.route === 'logs') renderLogs();
    else if (state.route === 'uploads') renderTransfers();
    else renderEntries();
  }

  /* ---------------------------------------------------------------- volumes */

  function renderVolumes() {
    els.volumeList.innerHTML = '';
    if (!state.volumes.length) {
      els.volumeList.innerHTML = '<div class="volume-item">No drives yet</div>';
      return;
    }
    state.volumes.forEach(function (volume) {
      var node = document.createElement('div');
      node.className = 'volume-item' + (state.path.split('/')[0] === volume.slug && state.path ? ' active' : '');
      var icon = volume.kind === 'usb' ? '⛭' : volume.kind === 'internal' ? '▤' : '▭';
      node.innerHTML = '<span>' + icon + '</span><span></span>';
      node.querySelector('span:last-child').textContent = volume.displayName;
      if (volume.needsPermission) {
        var badge = document.createElement('span');
        badge.className = 'badge locked';
        badge.textContent = 'locked';
        node.appendChild(badge);
      } else if (!volume.writable) {
        var read = document.createElement('span');
        read.className = 'badge';
        read.textContent = 'ro';
        node.appendChild(read);
      }
      node.addEventListener('click', function () {
        location.hash = '#/browse/' + volume.slug;
      });
      els.volumeList.appendChild(node);
    });
  }

  /* ---------------------------------------------------------------- browse */

  function loadDirectory(path) {
    api('/api/list?path=' + encodeURIComponent(path)).then(function (payload) {
      state.entries = payload.entries || [];
      renderEntries();
      renderPathBar();
    }).catch(function (error) {
      state.entries = [];
      els.view.innerHTML = '<div class="empty">' + escapeHtml(error.message) + '</div>';
    });
  }

  function renderPathBar() {
    var segments = state.path ? state.path.split('/') : [];
    els.pathBar.innerHTML = '';
    var root = document.createElement('span');
    root.className = 'seg';
    root.textContent = 'All drives';
    root.addEventListener('click', function () {
      location.hash = '#/browse';
    });
    els.pathBar.appendChild(root);
    segments.forEach(function (segment, index) {
      var sep = document.createElement('span');
      sep.className = 'sep';
      sep.textContent = '/';
      els.pathBar.appendChild(sep);
      var node = document.createElement('span');
      node.className = 'seg';
      node.textContent = segment;
      var targetPath = segments.slice(0, index + 1).join('/');
      node.addEventListener('click', function () {
        location.hash = '#/browse/' + targetPath;
      });
      els.pathBar.appendChild(node);
    });
  }

  function renderEntries() {
    renderVolumes();
    if (!state.entries.length) {
      els.view.innerHTML = '<div class="empty">This folder is empty.</div>';
      return;
    }
    if (state.view === 'grid') renderGrid();
    else renderList();
  }

  function renderGrid() {
    els.view.innerHTML = '';
    var grid = document.createElement('div');
    grid.className = 'grid';
    state.entries.forEach(function (entry) {
      var card = document.createElement('div');
      card.className = 'card';

      var thumb = document.createElement('div');
      thumb.className = 'thumb';
      if (entry.isDirectory) {
        thumb.innerHTML = '<span class="glyph">▤</span>';
      } else if (entry.kind === 'image') {
        var image = document.createElement('img');
        image.loading = 'lazy';
        image.src = '/api/thumbnail?path=' + encodeURIComponent(entry.path);
        image.alt = entry.name;
        thumb.appendChild(image);
      } else if (entry.kind === 'video') {
        thumb.innerHTML = '<span class="glyph">▶</span><div class="play">▶</div>';
      } else {
        thumb.innerHTML = '<span class="glyph">' + kindGlyph(entry) + '</span>';
      }

      var meta = document.createElement('div');
      meta.className = 'meta';
      var name = document.createElement('div');
      name.className = 'name';
      name.textContent = entry.name;
      var sub = document.createElement('div');
      sub.className = 'sub';
      sub.textContent = entry.isDirectory ? 'Folder' : formatBytes(entry.size);
      meta.appendChild(name);
      meta.appendChild(sub);

      card.appendChild(thumb);
      card.appendChild(meta);
      card.title = entry.name;
      card.addEventListener('click', function () { openEntry(entry); });
      grid.appendChild(card);
    });
    els.view.appendChild(grid);
  }

  function kindGlyph(entry) {
    switch (entry.kind) {
      case 'video': return '▶';
      case 'audio': return '♪';
      case 'image': return '▨';
      case 'folder': return '▤';
      default: return '≡';
    }
  }

  function renderList() {
    els.view.innerHTML = '';
    var list = document.createElement('div');
    list.className = 'list';

    var header = document.createElement('div');
    header.className = 'row header';
    header.innerHTML = '<div>Name</div><div class="col-mobile-hide">Size</div>' +
      '<div class="col-mobile-hide">Modified</div><div></div>';
    list.appendChild(header);

    state.entries.forEach(function (entry) {
      var row = document.createElement('div');
      row.className = 'row';

      var name = document.createElement('div');
      name.className = 'name';
      name.innerHTML = '<span>' + kindGlyph(entry) + '</span>';
      var label = document.createElement('span');
      label.textContent = entry.name;
      label.style.overflow = 'hidden';
      label.style.textOverflow = 'ellipsis';
      label.style.whiteSpace = 'nowrap';
      name.appendChild(label);
      name.addEventListener('click', function () { openEntry(entry); });

      var size = document.createElement('div');
      size.className = 'col-mobile-hide';
      size.textContent = entry.isDirectory ? '—' : formatBytes(entry.size);

      var modified = document.createElement('div');
      modified.className = 'col-mobile-hide';
      modified.textContent = entry.lastModified ? formatDate(entry.lastModified) : '—';

      var actions = document.createElement('div');
      actions.className = 'row-actions';
      if (!entry.isDirectory) {
        var download = document.createElement('button');
        download.className = 'small ghost';
        download.textContent = 'Download';
        download.addEventListener('click', function (event) {
          event.stopPropagation();
          window.location = '/api/download?path=' + encodeURIComponent(entry.path);
        });
        actions.appendChild(download);
      }
      var more = document.createElement('button');
      more.className = 'small ghost';
      more.textContent = '⋯';
      more.addEventListener('click', function (event) {
        event.stopPropagation();
        showEntryMenu(entry, more);
      });
      actions.appendChild(more);

      row.appendChild(name);
      row.appendChild(size);
      row.appendChild(modified);
      row.appendChild(actions);
      list.appendChild(row);
    });
    els.view.appendChild(list);
  }

  function openEntry(entry) {
    if (entry.isDirectory) {
      location.hash = '#/browse/' + entry.path;
      return;
    }
    if (entry.kind === 'image') {
      showImage(entry);
    } else if (entry.kind === 'video' || entry.kind === 'audio') {
      showPlayer(entry);
    } else {
      showFileCard(entry);
    }
  }

  function showImage(entry) {
    var name = entry.name;
    var index = state.entries.findIndex(function (item) {
      return item.kind === 'image' && !item.isDirectory;
    });
    var images = state.entries.filter(function (item) {
      return item.kind === 'image' && !item.isDirectory;
    });
    var position = Math.max(0, images.indexOf(entry));
    var nav = '';
    if (images.length > 1) {
      nav = '<div style="display:flex;justify-content:space-between;margin-top:12px">' +
        '<button id="prevImage">Previous</button><span>' + (position + 1) + ' / ' + images.length +
        '</span><button id="nextImage">Next</button></div>';
    }
    els.overlaySheet.innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">' +
      '<strong style="max-width:70%;overflow:hidden;text-overflow:ellipsis">' + escapeHtml(name) + '</strong>' +
      '<div><button id="downloadImage" class="small">Download</button> <button id="closeImage" class="small ghost">Close</button></div>' +
      '</div>' +
      '<img id="viewerImage" src="/api/file?path=' + encodeURIComponent(entry.path) +
      '" style="max-width:100%;max-height:70vh;border-radius:10px" />' +
      nav;
    showOverlay();
    var viewer = document.getElementById('viewerImage');
    document.getElementById('downloadImage').addEventListener('click', function () {
      window.location = '/api/download?path=' + encodeURIComponent(entry.path);
    });
    document.getElementById('closeImage').addEventListener('click', closeOverlay);
    function step(delta) {
      position = (position + delta + images.length) % images.length;
      var target = images[position];
      viewer.src = '/api/file?path=' + encodeURIComponent(target.path) + '&t=' + Date.now();
      els.overlaySheet.querySelector('strong').textContent = target.name;
      var counter = els.overlaySheet.querySelector('span');
      if (counter) counter.textContent = (position + 1) + ' / ' + images.length;
    }
    var previous = document.getElementById('prevImage');
    var next = document.getElementById('nextImage');
    if (previous) previous.addEventListener('click', function () { step(-1); });
    if (next) next.addEventListener('click', function () { step(1); });
  }

  function showPlayer(entry) {
    var src = '/api/file?path=' + encodeURIComponent(entry.path);
    var tag = entry.kind === 'audio' ? 'audio' : 'video';
    els.overlaySheet.innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">' +
      '<strong style="max-width:70%;overflow:hidden;text-overflow:ellipsis">' + escapeHtml(entry.name) + '</strong>' +
      '<button id="closePlayer" class="small ghost">Close</button></div>' +
      '<' + tag + ' id="player" controls autoplay playsinline preload="metadata"' +
      (entry.kind === 'video' ? ' style="width:100%;background:#000"' : ' style="width:100%"') + '>' +
      '<source src="' + src + '" /></' + tag + '>' +
      '<p style="color:var(--muted);font-size:13px;margin-top:10px">' +
      'Streamed straight from the source — nothing is downloaded first.</p>' +
      '<button id="downloadPlayer" class="small" style="margin-top:4px">Download instead</button>';
    showOverlay();
    document.getElementById('closePlayer').addEventListener('click', closeOverlay);
    document.getElementById('downloadPlayer').addEventListener('click', function () {
      window.location = '/api/download?path=' + encodeURIComponent(entry.path);
    });
  }

  function showFileCard(entry) {
    els.overlaySheet.innerHTML =
      '<h2>' + escapeHtml(entry.name) + '</h2>' +
      '<p style="color:var(--muted)">' + formatBytes(entry.size) + ' · ' + (entry.mimeType || 'file') + '</p>' +
      '<button id="downloadOnly" class="primary">Download</button> ' +
      '<button id="closeCard" class="ghost">Close</button>';
    showOverlay();
    document.getElementById('downloadOnly').addEventListener('click', function () {
      window.location = '/api/download?path=' + encodeURIComponent(entry.path);
    });
    document.getElementById('closeCard').addEventListener('click', closeOverlay);
  }

  function showEntryMenu(entry, anchor) {
    var actions = [];
    if (!entry.isDirectory) {
      actions.push('<button data-act="download">Download</button>');
      actions.push('<button data-act="play">Open</button>');
    }
    actions.push('<button data-act="rename">Rename</button>');
    actions.push('<button data-act="delete" class="danger">Delete</button>');
    els.overlaySheet.innerHTML = '<h2>' + escapeHtml(entry.name) + '</h2>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap">' + actions.join('') + '</div>';
    showOverlay();
    els.overlaySheet.querySelectorAll('button').forEach(function (button) {
      button.addEventListener('click', function () {
        var action = button.getAttribute('data-act');
        if (action === 'download') window.location = '/api/download?path=' + encodeURIComponent(entry.path);
        if (action === 'play') openEntry(entry);
        if (action === 'rename') renameEntry(entry);
        if (action === 'delete') deleteEntry(entry);
        closeOverlay();
      });
    });
  }

  function renameEntry(entry) {
    var name = prompt('New name for ' + entry.name, entry.name);
    if (!name) return;
    var parent = entry.path.split('/').slice(0, -1).join('/');
    api('/api/rename?path=' + encodeURIComponent(entry.path) + '&name=' + encodeURIComponent(name))
      .then(function () {
        toast('Renamed to ' + name);
        loadDirectory(parent);
      })
      .catch(toast);
  }

  function deleteEntry(entry) {
    if (!confirm('Delete ' + entry.name + '? This cannot be undone.')) return;
    var parent = entry.path.split('/').slice(0, -1).join('/');
    api('/api/delete?path=' + encodeURIComponent(entry.path))
      .then(function () {
        toast('Deleted');
        loadDirectory(parent);
      })
      .catch(toast);
  }

  function createFolder() {
    if (!state.path) {
      toast('Pick a drive first');
      return;
    }
    var name = prompt('Folder name', 'New folder');
    if (!name) return;
    api('/api/mkdir?path=' + encodeURIComponent(state.path) + '&name=' + encodeURIComponent(name))
      .then(function () {
        toast('Folder created');
        loadDirectory(state.path);
      })
      .catch(toast);
  }

  /* ---------------------------------------------------------------- upload */

  function bindDragAndDrop() {
    ['dragenter', 'dragover'].forEach(function (eventName) {
      els.dropzone.addEventListener(eventName, function (event) {
        event.preventDefault();
        els.dropzone.classList.add('active');
      });
    });
    ['dragleave', 'drop'].forEach(function (eventName) {
      els.dropzone.addEventListener(eventName, function (event) {
        event.preventDefault();
        els.dropzone.classList.remove('active');
      });
    });
    els.dropzone.addEventListener('drop', function (event) {
      var files = Array.prototype.slice.call(event.dataTransfer.files || []);
      uploadFiles(files);
    });
  }

  function uploadFiles(files) {
    if (!files.length) return;
    if (!state.path) {
      toast('Pick a drive first');
      return;
    }
    var target = state.path;
    var pending = files.slice();
    els.uploadProgress.style.display = '';
    els.uploadProgress.firstElementChild.style.width = '0%';
    step();

    function step() {
      if (!pending.length) {
        els.uploadProgress.querySelector('div').style.width = '100%';
        setTimeout(function () {
          els.uploadProgress.style.display = 'none';
        }, 900);
        loadDirectory(target);
        return;
      }
      var file = pending.shift();
      var request = new XMLHttpRequest();
      request.open('PUT', '/api/put?path=' + encodeURIComponent(target) + '&name=' + encodeURIComponent(file.name));
      request.upload.onprogress = function (event) {
        if (event.lengthComputable) {
          els.uploadProgress.querySelector('div').style.width =
            Math.round((event.loaded / event.total) * 100) + '%';
        }
      };
      request.onload = function () {
        if (request.status === 200 || request.status === 201 || request.status === 204) {
          step();
        } else {
          toast(file.name + ': upload failed');
          els.uploadProgress.style.display = 'none';
        }
      };
      request.onerror = function () {
        toast(file.name + ': upload failed');
        els.uploadProgress.style.display = 'none';
      };
      request.send(file);
    }
  }

  /* ---------------------------------------------------------------- settings */

  function renderSettings() {
    var settings = state.settings;
    var addresses = (state.info.ipv4Addresses || []).join(', ');
    els.view.innerHTML =
      '<div class="panel">' +
      '<h2>Settings</h2>' +
      '<p class="hint">Everything on this page applies immediately to the LAN server. The TV screen only shows status.</p>' +

      '<h3>Access links</h3>' +
      '<div class="link-box"><span class="mono">Web interface: ' + escapeHtml(state.info.webInterfaceUrl || '') + '</span></div>' +
      '<div class="link-box"><span class="mono">iPhone Files &gt; Connect to Server: ' + escapeHtml(state.info.webdavUrl || '') + '</span></div>' +
      '<p class="hint">Detected addresses on this network: ' + escapeHtml(addresses || 'none') + '</p>' +

      '<h3>Server</h3>' +
      fieldNumber('serverPort', 'WebDAV &amp; web port', 'Port used by both the web interface and iPhone Files.') +
      fieldText('deviceName', 'Server name', 'Shown to iPhone Files as the share name.') +
      fieldCheck('shareWebdav', 'Serve WebDAV to iPhone Files', 'Needed for “Connect to Server”. Turning this off leaves only the web interface.') +

      '<h3>Access control</h3>' +
      fieldCheck('requireAuth', 'Require a username and password', 'Applies to the web interface and to iPhone Files.') +
      fieldText('username', 'Username', '') +
      fieldPassword('password', 'Password', 'Use with the iPhone “Registered User” login.') +

      '<h3>Permissions for clients</h3>' +
      fieldCheck('allowUploads', 'Allow uploads', 'Lets phones and browsers copy files to the TV.') +
      fieldCheck('allowDeletes', 'Allow deletions', 'Lets clients delete files from the TV.') +
      fieldCheck('allowCreateFolders', 'Allow new folders', 'Lets clients create folders on the TV.') +
      fieldCheck('showHiddenFiles', 'Show hidden files', 'Includes names starting with a dot.') +

      '<h3>TV behaviour</h3>' +
      fieldCheck('autoStartOnBoot', 'Start automatically after the TV boots', '') +
      fieldCheck('keepCpuAwake', 'Keep the CPU awake while sharing', 'Keeps transfers alive when the TV dims its screen.') +

      '<div style="margin-top:20px;display:flex;gap:10px;flex-wrap:wrap">' +
      '<button class="primary" id="saveSettings">Save settings</button>' +
      '<button class="ghost" id="restartServer">Restart server</button>' +
      '<button class="ghost" id="clearCache">Clear cache</button>' +
      '</div>' +
      '<p class="hint" id="settingsNote"></p>' +
      '</div>';

    document.getElementById('saveSettings').addEventListener('click', saveSettings);
    document.getElementById('restartServer').addEventListener('click', function () {
      api('/api/restart', { method: 'POST' }).then(function () {
        toast('Server restarted');
        refresh();
      }).catch(toast);
    });
    document.getElementById('clearCache').addEventListener('click', function () {
      api('/api/clear-cache', { method: 'POST' }).then(function () {
        toast('Cache cleared');
      }).catch(toast);
    });

    hydrateInputs('serverPort', settings.serverPort);
    hydrateInputs('deviceName', settings.deviceName);
    hydrateInputs('shareWebdav', settings.shareWebdav);
    hydrateInputs('requireAuth', settings.requireAuth);
    hydrateInputs('username', settings.username);
    hydrateInputs('password', settings.password);
    hydrateInputs('allowUploads', settings.allowUploads);
    hydrateInputs('allowDeletes', settings.allowDeletes);
    hydrateInputs('allowCreateFolders', settings.allowCreateFolders);
    hydrateInputs('showHiddenFiles', settings.showHiddenFiles);
    hydrateInputs('autoStartOnBoot', settings.autoStartOnBoot);
    hydrateInputs('keepCpuAwake', settings.keepCpuAwake);
  }

  function hydrateInputs(id, value) {
    var node = document.getElementById(id);
    if (!node) return;
    if (node.type === 'checkbox') node.checked = !!value;
    else node.value = value === undefined || value === null ? '' : value;
  }

  function fieldText(id, label, desc) {
    return '<div class="field"><div><label for="' + id + '">' + label + '</label>' +
      (desc ? '<div class="desc">' + desc + '</div>' : '') +
      '</div><input type="text" id="' + id + '" /></div>';
  }

  function fieldPassword(id, label, desc) {
    return '<div class="field"><div><label for="' + id + '">' + label + '</label>' +
      (desc ? '<div class="desc">' + desc + '</div>' : '') +
      '</div><input type="password" id="' + id + '" autocomplete="new-password" /></div>';
  }

  function fieldNumber(id, label, desc) {
    return '<div class="field"><div><label for="' + id + '">' + label + '</label>' +
      (desc ? '<div class="desc">' + desc + '</div>' : '') +
      '</div><input type="number" min="1024" max="65535" id="' + id + '" /></div>';
  }

  function fieldCheck(id, label, desc) {
    return '<div class="field"><div><label for="' + id + '">' + label + '</label>' +
      (desc ? '<div class="desc">' + desc + '</div>' : '') +
      '</div><div class="switch"><input type="checkbox" id="' + id + '" /></div></div>';
  }

  function saveSettings() {
    var body = [
      'serverPort=' + encodeURIComponent(document.getElementById('serverPort').value),
      'deviceName=' + encodeURIComponent(document.getElementById('deviceName').value),
      'requireAuth=' + checkboxValue('requireAuth'),
      'username=' + encodeURIComponent(document.getElementById('username').value),
      'password=' + encodeURIComponent(document.getElementById('password').value),
      'allowUploads=' + checkboxValue('allowUploads'),
      'allowDeletes=' + checkboxValue('allowDeletes'),
      'allowCreateFolders=' + checkboxValue('allowCreateFolders'),
      'showHiddenFiles=' + checkboxValue('showHiddenFiles'),
      'autoStartOnBoot=' + checkboxValue('autoStartOnBoot'),
      'keepCpuAwake=' + checkboxValue('keepCpuAwake'),
      'shareWebdav=' + checkboxValue('shareWebdav')
    ].join('&');

    api('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    }).then(function (payload) {
      state.settings = payload.settings || {};
      document.getElementById('settingsNote').textContent =
        payload.restartRequired ? 'Saved. Restart the server to apply the new port.' : 'Saved.';
      toast('Settings saved');
      renderSettings();
    }).catch(function (error) {
      document.getElementById('settingsNote').textContent = error.message;
    });
  }

  function checkboxValue(id) {
    var node = document.getElementById(id);
    return node && node.checked ? 'true' : 'false';
  }

  /* ---------------------------------------------------------------- logs */

  function renderLogs() {
    els.view.innerHTML = '<div class="log-view" id="logBox">Loading…</div>' +
      '<button class="ghost small" id="refreshLogs" style="margin-top:10px">Refresh</button>';
    document.getElementById('refreshLogs').addEventListener('click', renderLogs);
    api('/api/logs').then(function (payload) {
      var lines = payload.lines || [];
      document.getElementById('logBox').textContent = lines.length
        ? lines.map(function (line) {
            return '[' + formatTime(line.timestamp) + '] ' + line.level.toUpperCase() + ' ' + line.message;
          }).join('\n')
        : 'No log entries yet.';
    }).catch(function (error) {
      document.getElementById('logBox').textContent = error.message;
    });
  }

  /* ---------------------------------------------------------------- transfer */

  function renderTransfers() {
    els.view.innerHTML =
      '<div class="panel">' +
      '<h2>Transfer files</h2>' +
      '<p class="hint">Drag files into the box below, or use the picker. The current folder is ' +
      escapeHtml(state.path || 'no folder selected') + '.</p>' +
      '<div class="dropzone" id="transferDrop">Drop files here</div>' +
      '<button class="primary" id="pickFiles">Choose files</button>' +
      '<h3>How it works</h3>' +
      '<p class="hint">Uploads are streamed straight to the drive in the TV. Downloads and media ' +
      'playback are streamed with HTTP range requests, so a video starts playing before it has ' +
      'finished arriving.</p>' +
      '</div>';
    document.getElementById('pickFiles').addEventListener('click', function () {
      els.filePicker.click();
    });
    var drop = document.getElementById('transferDrop');
    drop.addEventListener('click', function () { els.filePicker.click(); });
    drop.addEventListener('dragover', function (event) {
      event.preventDefault();
      drop.classList.add('active');
    });
    drop.addEventListener('dragleave', function () {
      drop.classList.remove('active');
    });
    drop.addEventListener('drop', function (event) {
      event.preventDefault();
      drop.classList.remove('active');
      uploadFiles(Array.prototype.slice.call(event.dataTransfer.files || []));
    });
  }

  /* ---------------------------------------------------------------- helpers */

  function showOverlay() {
    els.overlay.classList.add('show');
  }

  function closeOverlay() {
    var player = document.getElementById('player');
    if (player) {
      try { player.pause(); } catch (error) { /* ignore */ }
      player.removeAttribute('src');
      player.load();
    }
    els.overlay.classList.remove('show');
    els.overlaySheet.innerHTML = '';
  }

  function formatBytes(value) {
    if (!value) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var exponent = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
    var scaled = value / Math.pow(1024, exponent);
    return (scaled >= 10 || exponent === 0 ? Math.round(scaled) : scaled.toFixed(1)) + ' ' + units[exponent];
  }

  function formatDate(millis) {
    var date = new Date(millis);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function formatTime(millis) {
    return new Date(millis).toLocaleTimeString();
  }

  function escapeHtml(value) {
    return String(value === undefined || value === null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  var toastTimer = null;
  function toast(message) {
    els.toast.textContent = typeof message === 'string' ? message : (message && message.message) || 'Done';
    els.toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      els.toast.classList.remove('show');
    }, 2600);
  }
})();
