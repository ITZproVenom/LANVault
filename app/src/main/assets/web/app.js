/* LANVault web interface. Talks to /api/* only; streaming and downloads go through
   /api/file and /api/download, both of which honour HTTP Range requests so video and
   audio never have to be fully downloaded. */
(function () {
  'use strict';

  var state = {
    volumes: [],
    settings: {},
    portInfo: null,
    requiresAuth: false,
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
    window.setInterval(function () {
      if (state.route === 'monitoring') refreshMonitoringData();
    }, 15000);
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
      state.reachableAddress = (window.location && window.location.hostname) || null;
      state.settings = payload.settings || {};
      state.requiresAuth = !!(payload.settings || {}).requireAuth;
      state.volumes = payload.volumes || [];
      state.portInfo = {
        port: payload.port,
        requestedPort: payload.requestedPort,
        fellBack: !!payload.portInUseOnPreferred
      };
      els.statusDot.classList.toggle('offline', !payload.running);
      els.sidebarFooter.textContent = payload.running
        ? 'Port ' + payload.port + ' · ' + payload.volumeCount + ' shared'
        : 'Server offline';
      renderVolumes();
      updateNotices(payload);
      if (state.route === 'browse') renderPathBar();
    }).catch(function (error) {
      els.sidebarFooter.textContent = error.message;
    });
  }

  function updateNotices(info) {
    // A port conflict outranks the permission notice: without a binding port the whole
    // interface, including the permission prompt, is unreachable.
    if (info.portInUseOnPreferred) {
      els.noticeText.textContent =
        'Port ' + info.requestedPort + ' is already in use by another app on this TV, so the ' +
        'server is listening on port ' + info.port + ' instead.';
      els.noticeAction.textContent = 'Keep port ' + info.port;
      els.noticeAction.onclick = function () {
        postSettings({ serverPort: String(info.port) }).then(function () {
          toast('Now using port ' + info.port);
        }).catch(toast);
      };
      els.notice.classList.add('show');
      return;
    }
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

  function postSettings(values) {
    var parts = [];
    Object.keys(values || {}).forEach(function (key) {
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(values[key]));
    });
    return api('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: parts.join('&')
    }).then(function (payload) {
      state.settings = payload.settings || {};
      renderSettings();
      return payload;
    });
  }

  function route() {
    var hash = location.hash.replace(/^#\/?/, '');
    var parts = hash.split('/');
    var name = parts[0] || 'browse';
    document.querySelectorAll('[data-nav]').forEach(function (link) {
      link.classList.toggle('active', link.getAttribute('data-nav') === name);
    });
    state.route = name === 'settings' || name === 'logs' || name === 'uploads' || name === 'monitoring' ? name : 'browse';
    if (state.route === 'browse') {
      state.path = decodeURIComponent(parts.slice(1).join('/') || '');
      els.pathBar.style.display = '';
      loadDirectory(state.path);
    } else {
      els.pathBar.style.display = 'none';
    }
    var browsing = state.route === 'browse';
    els.viewToggle.style.display = browsing ? '' : 'none';
    els.newFolderButton.style.display = browsing ? '' : 'none';
    els.uploadButton.style.display = browsing ? '' : 'none';
    els.dropzone.style.display = browsing ? '' : 'none';
    render();
  }

  function render() {
    if (state.route === 'settings') renderSettings();
    else if (state.route === 'logs') renderLogs();
    else if (state.route === 'uploads') renderTransfers();
    else if (state.route === 'monitoring') renderMonitoring();
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
    return entry;
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

  /*
   * Media playable by the browser depends on the container. iPhone and iPad Safari in
   * particular cannot decode Matroska, WMV, AVI or FLAC, which is the usual reason a preview
   * does nothing. Surfacing the reason beats leaving a silent failure, and offer the download
   * so the user still gets the file.
   */
  var UNSUPPORTED_EXTENSIONS = ['mkv', 'avi', 'wmv', 'flv', 'mov', 'divx', 'asf', 'm2ts', 'ts',
    'flac', 'wma', 'aiff', 'mid', 'midi', 'amr', 'ogg', 'oga', 'opus'];

  function extensionOf(name) {
    return String(name || '').split('.').pop().toLowerCase();
  }

  function playbackWarning(entry) {
    if (!UNSUPPORTED_EXTENSIONS || UNSUPPORTED_EXTENSIONS.indexOf(extensionOf(entry.name)) < 0) {
      return null;
    }
    var kind = entry.kind === 'audio' ? 'This audio file' : 'This video';
    return kind + ' is in a format this browser cannot decode, so it cannot be played here. ' +
      'Use Download to save it, or open it in an app that supports .' + extensionOf(entry.name) + '.';
  }

  function showPlayer(entry) {
    var src = '/api/file?path=' + encodeURIComponent(entry.path);
    var tag = entry.kind === 'audio' ? 'audio' : 'video';
    var warning = playbackWarning(entry);
    els.overlaySheet.innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">' +
      '<strong style="max-width:70%;overflow:hidden;text-overflow:ellipsis">' + escapeHtml(entry.name) + '</strong>' +
      '<button id="closePlayer" class="small ghost">Close</button></div>' +
      (warning ? '<p style="color:var(--warning);font-size:13px;margin:0 0 10px">' + warning + '</p>' : '') +
      '<' + tag + ' id="player" controls autoplay playsinline preload="metadata"' +
      (entry.kind === 'video' ? ' style="width:100%;background:#000"' : ' style="width:100%"') + '>' +
      '<source src="' + src + '" /></' + tag + '>' +
      '<p id="playerError" style="color:var(--danger);font-size:13px;margin:10px 0 0;display:none"></p>' +
      '<p id="playerNote" style="color:var(--muted);font-size:13px;margin-top:10px' + (warning ? ';display:none' : '') + '">' +
      'Streamed straight from the source — nothing is downloaded first.</p>' +
      '<button id="downloadPlayer" class="small" style="margin-top:4px">Download instead</button>';
    showOverlay();
    document.getElementById('closePlayer').addEventListener('click', closeOverlay);
    document.getElementById('downloadPlayer').addEventListener('click', function () {
      window.location = '/api/download?path=' + encodeURIComponent(entry.path);
    });

    var player = document.getElementById('player');
    var errorLine = document.getElementById('playerError');
    var noteLine = document.getElementById('playerNote');
    function fail(reason) {
      player.style.display = 'none';
      noteLine.style.display = 'none';
      errorLine.style.display = '';
      errorLine.textContent = reason;
    }
    player.addEventListener('error', function () {
      fail(warning || 'This browser could not play the file. Use Download to save it.');
    });
    player.addEventListener('stalled', function () {
      fail('Loading stalled. Check that the TV is still reachable, then try again.');
    });
    if (warning) {
      fail(warning);
    }
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
      addressList('Web interface', 'ui/') +
      smbAddressList() +
      addressList('WebDAV URL (WebDAV-compatible clients)', '') +
      addressNote(addresses) +
      '<div class="link-box" style="flex-direction:column;align-items:flex-start">' +
      '<strong>Connecting from an iPhone</strong>' +
      '<span style="color:var(--muted);font-size:13px">' + iphoneInstructions() + '</span>' +
      '</div>' +
      (state.portInfo && state.portInfo.fellBack
        ? '<p class="hint" style="color:var(--warning)">Requested port ' +
          escapeHtml(String(state.portInfo.requestedPort)) + ' is held by another app on this TV. ' +
          'The server is live on port ' + escapeHtml(String(state.portInfo.port)) +
          '; the field above will pick it up if you save the form.</p>'
        : '') +
      '<h3>Server</h3>' +
      fieldNumber('serverPort', 'WebDAV &amp; web port', 'Port used by both the web interface and iPhone Files.') +
      fieldText('deviceName', 'Server name', 'Shown to WebDAV-compatible clients.') +
      fieldCheck('shareWebdav', 'Enable WebDAV for compatible clients', 'Keeps the HTTP WebDAV endpoint available for WebDAV-capable clients, separately from native iPhone Files SMB.') +
      fieldCheck('shareSmb', 'Enable native iPhone Files server (SMB2/3)', 'Exports directly mounted TV and USB volumes. It tries port 445 first and falls back to 1445 if port 445 is unavailable.') +

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
    hydrateInputs('shareSmb', settings.shareSmb);
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

  /*
   * A TV often has several interfaces and only one shares a network with the device in front
   * of you. Showing one address and hoping it is the right one is what breaks Connect to
   * Server, so list them all and flag the one this browser actually reached.
   */
  function addressList(label, suffix) {
    var list = (state.info.ipv4Addresses || []);
    if (!list.length) {
      return '<div class="link-box"><span class="muted">' + label + ': no address found</span></div>';
    }
    return list.map(function (address) {
      var working = state.reachableAddress && address === state.reachableAddress;
      var url = 'http://' + address + ':' + state.info.port + (suffix ? '/' + suffix : '/');
      return '<div class="link-box">' +
        '<span class="mono">' + label + ': ' + escapeHtml(url) + '</span>' +
        (working ? '<strong style="color:var(--accent);font-size:13px">working</strong>' : '') +
        '</div>';
    }).join('');
  }

  function addressNote(addresses) {
    var count = (state.info.ipv4Addresses || []).length;
    if (count <= 1) return '';
    return '<p class="hint">This TV has ' + count + ' addresses. The one marked ' +
      '<strong style="color:var(--accent)">working</strong> is the one that reached this browser, ' +
      'so it is the one your device can use. Detected: ' + escapeHtml(addresses || '') + '</p>';
  }

  function smbAddressList() {
    var smb = state.info.smbServer || {};
    var ips = state.info.ipv4Addresses || [];
    var port = Number(smb.port || 0);
    if (!smb.enabled) return '<div class="link-box"><span class="hint">SMB server disabled in Settings.</span></div>';
    if (!smb.running || !port) return '<div class="link-box"><span class="hint">iPhone Files server: ' + escapeHtml(smb.message || 'SMB starting.') + '</span></div>';
    return ips.map(function (ip) {
      var url = 'smb://' + ip + (port === 445 ? '' : ':' + port);
      var working = state.reachableAddress && ip === state.reachableAddress;
      return '<div class="link-box"><span class="mono">iPhone Files server: ' + escapeHtml(url) + '</span>' +
        (working ? '<strong style="color:var(--accent);font-size:13px">same interface as this browser</strong>' : '') + '</div>';
    }).join('') + '<p class="hint">Shares: ' + escapeHtml((smb.shareNames || []).join(', ')) + '. Enter this SMB address in Files → Browse → … → Connect to Server.</p>';
  }
  function iphoneInstructions() {
    var smb = state.info.smbServer || {};
    var ip = state.reachableAddress || (state.info.ipv4Addresses || [])[0] || '<tv-ip>';
    var port = Number(smb.port || 0);
    if (!smb.enabled) return 'Enable the native iPhone Files server (SMB2/3) in Settings.';
    if (!smb.running || !port) return escapeHtml(smb.message || 'SMB server is starting.');
    var url = 'smb://' + ip + (port === 445 ? '' : ':' + port);
    if (state.requiresAuth) return 'Files → Browse → … → Connect to Server, enter <code>' + escapeHtml(url) + '</code>, choose <strong>Registered User</strong>, and sign in with <strong>' + escapeHtml(state.settings.username || 'lanvault') + '</strong> and your LANVault password.';
    return 'Files → Browse → … → Connect to Server, enter <code>' + escapeHtml(url) + '</code>, and choose <strong>Guest</strong>. WebDAV remains separately available at <code>' + escapeHtml('http://' + ip + ':' + (state.info.port || 8081) + '/') + '</code> for compatible clients.';
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
      'shareWebdav=' + checkboxValue('shareWebdav'),
      'shareSmb=' + checkboxValue('shareSmb')
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



  function refreshEmbeddedAdGuardHome() {
    return monitorApi('/api/monitoring/embedded').then(function (data) {
      document.getElementById('aghRuntimeBadge').textContent =
        data.running ? (data.adminReachable ? 'Running' : 'Starting') : (data.binaryBundled ? 'Stopped' : 'Binary unavailable');
      document.getElementById('aghRuntimeMessage').textContent = data.message || 'No status available.';
      document.getElementById('aghRuntimeAutostart').checked = !!data.autoStart;
      document.getElementById('aghRuntimeStart').disabled = !!data.running || !data.binaryBundled || !data.supportedAbi;
      document.getElementById('aghRuntimeStop').disabled = !data.running;
      document.getElementById('aghRuntimeCredentialsButton').disabled = !data.adminCredentialsAvailable;
      document.getElementById('aghRuntimeCards').innerHTML = [
        ['Bundled version', data.version || '—'],
        ['Android ABI', data.abi || 'Unknown'],
        ['Admin interface', data.adminReachable ? 'Reachable' : 'Not reachable'],
        ['DNS port ' + String(data.dnsPort || 53535), data.dnsTcpReachable ? 'TCP listener detected' : 'TCP listener not detected']
      ].map(function (card) {
        return '<div class="monitor-card"><span>' + escapeHtml(card[0]) + '</span><strong>' + escapeHtml(String(card[1])) + '</strong></div>';
      }).join('');
      document.getElementById('aghRuntimeLinks').innerHTML = (data.adminUrls || []).map(function (url) {
        return '<a class="monitor-link" href="' + escapeHtml(url) + '" target="_blank" rel="noopener">Open AdGuard Home setup / dashboard · ' + escapeHtml(url) + '</a>';
      }).join('') || '<span class="hint">Start AdGuard Home to make the setup interface available at port ' + escapeHtml(String(data.adminPort || 3000)) + '.</span>';
      document.getElementById('aghRuntimeLog').textContent = data.logTail || 'No runtime log yet.';
      return data;
    }).catch(function (error) {
      setMonitorText('aghRuntimeMessage', error.message);
    });
  }

  function startEmbeddedAdGuardHome() {
    var button = document.getElementById('aghRuntimeStart');
    button.disabled = true;
    setMonitorText('aghRuntimeMessage', 'Starting the bundled Android AdGuard Home service…');
    monitorApi('/api/monitoring/embedded/start', 'POST', {}).then(function () {
      toast('AdGuard Home start requested');
      return refreshEmbeddedAdGuardHome();
    }).catch(function (error) {
      setMonitorText('aghRuntimeMessage', error.message);
      toast(error.message);
      return refreshEmbeddedAdGuardHome();
    }).then(function () { button.disabled = false; });
  }

  function showEmbeddedAdGuardHomeCredentials() {
    var button = document.getElementById('aghRuntimeCredentialsButton');
    var target = document.getElementById('aghRuntimeCredentials');
    button.disabled = true;
    target.style.display = '';
    target.textContent = 'Reading encrypted first-run credentials…';
    monitorApi('/api/monitoring/embedded/credentials', 'POST', {}).then(function (data) {
      target.textContent = data.available
        ? 'Username: ' + data.username + '\nPassword: ' + data.password + '\n\nOpen the AdGuard Home dashboard link above, sign in, and change this password.'
        : (data.message || 'No generated login is available.');
    }).catch(function (error) {
      target.textContent = error.message;
    }).then(function () { button.disabled = false; });
  }

  function stopEmbeddedAdGuardHome() {
    var button = document.getElementById('aghRuntimeStop');
    button.disabled = true;
    monitorApi('/api/monitoring/embedded/stop', 'POST', {}).then(function () {
      toast('AdGuard Home stopped');
      return refreshEmbeddedAdGuardHome();
    }).catch(function (error) {
      setMonitorText('aghRuntimeMessage', error.message);
    }).then(function () { button.disabled = false; });
  }

  function setEmbeddedAdGuardHomeAutostart() {
    var enabled = document.getElementById('aghRuntimeAutostart').checked;
    monitorApi('/api/monitoring/embedded/autostart', 'POST', { enabled: enabled }).then(function () {
      toast(enabled ? 'AdGuard Home will start with LANVault' : 'AdGuard Home auto-start disabled');
      refreshEmbeddedAdGuardHome();
    }).catch(function (error) {
      setMonitorText('aghRuntimeMessage', error.message);
    });
  }

  /* --------------------------------------------------------- network monitoring */
  function renderMonitoring() {
    els.view.innerHTML =
      '<div class="monitor-page">' +
        '<div class="monitor-hero"><div><p class="eyebrow">LANVAULT / NETWORK</p><h2>Network monitor</h2>' +
        '<p class="hint">Read-only monitoring for this TV, AdGuard Home, and your AdGuard DNS cloud account.</p></div>' +
        '<button class="primary" id="monitorRefresh">Refresh now</button></div>' +
        '<div class="monitor-alert" id="monitorAuthWarning">Monitoring integrations contain private network and account data. Set a unique, non-default username and password in LANVault Settings first. AdGuard sign-in is sent to this TV over local HTTP, so use this only on a trusted LAN.</div>' +
        '<section class="monitor-section"><h3>TV gateway health</h3><div class="monitor-cards" id="monitorLocalCards"><div class="monitor-card"><span>Loading</span><strong>…</strong></div></div>' +
        '<details class="monitor-details"><summary>Interfaces and observed router neighbours</summary><pre class="monitor-json" id="monitorLocalDetails">Loading…</pre></details>' +
        '<div class="monitor-proxy-form"><label for="monitorProxyPort">Every Proxy listening port <span>0 disables the check</span></label><div class="monitor-form-row"><input type="number" id="monitorProxyPort" min="0" max="65535" placeholder="e.g. 8080" /><button id="monitorProxySave">Save port</button></div><p class="hint" id="monitorProxyMessage">Checks whether a TCP listener responds on 127.0.0.1. It does not change Every Proxy.</p></div></section>' +
        '<section class="monitor-section"><div class="monitor-heading"><div><h3>AdGuard Home on this TV</h3><p class="hint">The actual AdGuard Home server is bundled into this APK for Android ARM/ARM64. Start it here; its own setup and filtering interface opens separately.</p></div><span class="monitor-badge" id="aghRuntimeBadge">Checking</span></div>' +
        '<div class="monitor-cards" id="aghRuntimeCards"></div><p class="hint" id="aghRuntimeMessage">Checking built-in server…</p>' +
        '<div class="monitor-actions"><button class="primary" id="aghRuntimeStart">Start AdGuard Home</button><button class="ghost" id="aghRuntimeStop">Stop AdGuard Home</button><button class="ghost" id="aghRuntimeCredentialsButton">Show generated admin login</button><label class="monitor-inline-check"><input type="checkbox" id="aghRuntimeAutostart" /> Start with LANVault when the TV boots</label></div>' +
        '<pre class="monitor-json" id="aghRuntimeCredentials" style="display:none">—</pre>' +
        '<div id="aghRuntimeLinks" class="monitor-link-list"></div>' +
        '<details class="monitor-details"><summary>AdGuard Home runtime log</summary><pre class="monitor-json" id="aghRuntimeLog">—</pre></details>' +
        '<p class="hint">This build uses DNS port 53535 so AdGuard Home can run without root. You can manage filtering and use the dashboard, but a typical router expects DNS on port 53. Router-wide ad blocking requires a supported privileged port-53 setup or a router-side forwarding rule; do not point router DNS here until DNS is confirmed reachable.</p></section>' +
        '<section class="monitor-section"><div class="monitor-heading"><div><h3>Connect to another AdGuard Home (optional)</h3><p class="hint">Use this only if you also have a separate AdGuard Home server elsewhere.</p></div><span class="monitor-badge" id="monitorHomeBadge">Not configured</span></div>' +
        '<div class="monitor-form-grid"><div class="field"><div><label for="monitorHomeUrl">AdGuard Home base URL</label><div class="desc">Example: http://192.168.1.20:3000. Use the origin only, not /control.</div></div><input type="text" id="monitorHomeUrl" placeholder="http://192.168.1.20:3000" autocomplete="url" /></div>' +
        '<div class="field"><div><label for="monitorHomeUser">Admin username</label></div><input type="text" id="monitorHomeUser" autocomplete="username" /></div>' +
        '<div class="field"><div><label for="monitorHomePassword">Admin password</label><div class="desc">Encrypted on the TV using Android Keystore.</div></div><input type="password" id="monitorHomePassword" autocomplete="current-password" /></div></div>' +
        '<div class="monitor-actions"><button class="primary" id="monitorHomeSave">Save &amp; test connection</button><button class="ghost" id="monitorHomeForget">Disconnect</button></div><p id="monitorHomeMessage" class="hint">Not connected.</p>' +
        '<div class="monitor-cards" id="monitorHomeCards"></div><div class="monitor-two-col"><div><h4>Known clients</h4><pre class="monitor-json" id="monitorHomeClients">—</pre></div><div><h4>Recent DNS queries</h4><pre class="monitor-json" id="monitorHomeQueries">—</pre></div></div>' +
        '<details class="monitor-details"><summary>Raw AdGuard Home response</summary><pre class="monitor-json" id="monitorHomeRaw">—</pre></details></section>' +
        '<section class="monitor-section"><div class="monitor-heading"><div><h3>AdGuard DNS account</h3><p class="hint">Sign in to sync your existing cloud DNS profiles, registered devices, and available query statistics.</p></div><span class="monitor-badge" id="monitorCloudBadge">Signed out</span></div>' +
        '<div id="monitorCloudSignIn"><div class="monitor-form-grid"><div class="field"><div><label for="monitorCloudEmail">AdGuard account email</label></div><input type="email" id="monitorCloudEmail" autocomplete="username" /></div>' +
        '<div class="field"><div><label for="monitorCloudPassword">Password</label></div><input type="password" id="monitorCloudPassword" autocomplete="current-password" /></div>' +
        '<div class="field"><div><label for="monitorCloudMfa">Two-factor code (optional)</label><div class="desc">Only needed when 2FA is enabled.</div></div><input type="text" id="monitorCloudMfa" inputmode="numeric" autocomplete="one-time-code" /></div></div>' +
        '<div class="monitor-actions"><button class="primary" id="monitorCloudSignInButton">Sign in with AdGuard</button></div></div>' +
        '<div id="monitorCloudConnected" style="display:none"><p class="hint" id="monitorCloudMessage"></p><div class="monitor-actions"><button class="primary" id="monitorCloudSync">Sync account now</button><button class="ghost" id="monitorCloudSignOut">Sign out</button></div></div>' +
        '<div class="monitor-cards" id="monitorCloudCards"></div><div class="monitor-two-col"><div><h4>Existing DNS server profiles</h4><pre class="monitor-json" id="monitorCloudServers">—</pre></div><div><h4>Registered devices</h4><pre class="monitor-json" id="monitorCloudDevices">—</pre></div></div>' +
        '<div class="monitor-two-col"><div><h4>Queries by device · last 24 hours</h4><pre class="monitor-json" id="monitorCloudDeviceStats">—</pre></div><div><h4>Top domains · last 24 hours</h4><pre class="monitor-json" id="monitorCloudDomainStats">—</pre></div></div>' +
        '<details class="monitor-details"><summary>Time-series stats · last 24 hours</summary><pre class="monitor-json" id="monitorCloudTimeStats">—</pre></details></section>' +
        '<section class="monitor-section"><h3>Bandwidth versus DNS activity</h3><p class="hint">AdGuard Home and AdGuard DNS show DNS queries and blocked requests, not total upload/download bytes per router device. This page reports the TV board\'s interface counters when Android exposes them and its ARP neighbour cache when readable. True per-device bandwidth requires router traffic counters/API or flow telemetry; no bandwidth totals are guessed.</p></section>' +
      '</div>';

    document.getElementById('monitorRefresh').addEventListener('click', refreshMonitoringData);
    document.getElementById('aghRuntimeStart').addEventListener('click', startEmbeddedAdGuardHome);
    document.getElementById('aghRuntimeStop').addEventListener('click', stopEmbeddedAdGuardHome);
    document.getElementById('aghRuntimeCredentialsButton').addEventListener('click', showEmbeddedAdGuardHomeCredentials);
    document.getElementById('aghRuntimeAutostart').addEventListener('change', setEmbeddedAdGuardHomeAutostart);
    document.getElementById('monitorHomeSave').addEventListener('click', saveHomeMonitor);
    document.getElementById('monitorHomeForget').addEventListener('click', forgetHomeMonitor);
    document.getElementById('monitorCloudSignInButton').addEventListener('click', signInCloudMonitor);
    document.getElementById('monitorCloudSync').addEventListener('click', refreshCloudMonitor);
    document.getElementById('monitorCloudSignOut').addEventListener('click', signOutCloudMonitor);
    document.getElementById('monitorProxySave').addEventListener('click', saveProxyMonitor);
    refreshMonitoringData();
  }

  function monitorApi(path, method, value) {
    var options = method ? { method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value || {}) } : undefined;
    return api(path, options);
  }

  function refreshMonitoringData() {
    return monitorApi('/api/monitoring/status').then(function (data) {
      var cards = [
        ['Android VPN', data.vpnActive ? 'Active' : 'Not detected'],
        ['Free system memory', data.systemMemoryAvailableMb == null ? 'Unavailable' : formatMonitorMb(data.systemMemoryAvailableMb) + ' MB'],
        ['App heap', formatMonitorMb(data.appHeapUsedMb) + ' / ' + formatMonitorMb(data.appHeapMaxMb) + ' MB'],
        ['Highest exposed thermal sensor', data.thermal && data.thermal.celsius != null ? data.thermal.celsius + ' °C' : 'Not exposed'],
        ['Every Proxy', data.everyProxyPort > 0 ? (data.everyProxyListening ? 'Listener detected' : 'Port not reachable') : 'Not configured']
      ];
      document.getElementById('monitorLocalCards').innerHTML = cards.map(function (card) {
        return '<div class="monitor-card"><span>' + escapeHtml(card[0]) + '</span><strong>' + escapeHtml(String(card[1])) + '</strong></div>';
      }).join('');
      document.getElementById('monitorLocalDetails').textContent = JSON.stringify({
        activeTransports: data.activeTransports, uptimeSeconds: data.uptimeSeconds,
        thermal: data.thermal, networkInterfaces: data.networkInterfaces, routerNeighbors: data.routerNeighbors
      }, null, 2);
      document.getElementById('monitorProxyPort').value = data.everyProxyPort || 0;
      document.getElementById('monitorAuthWarning').style.display = 'none';
      return refreshEmbeddedAdGuardHome().then(function () { return monitorApi('/api/monitoring/home'); });
    }).then(function (home) {
      document.getElementById('monitorHomeBadge').textContent = !home.configured ? 'Not configured' : (home.connected ? 'Connected' : 'Connection issue');
      setMonitorText('monitorHomeMessage', home.message || 'No status available.');
      var stats = home.stats || {};
      var cards = [
        ['DNS queries', stats.num_dns_queries],
        ['Blocked by filters', stats.num_blocked_filtering],
        ['Blocked malware', stats.num_replaced_safebrowsing],
        ['Avg. processing', stats.avg_processing_time == null ? null : Number(stats.avg_processing_time).toFixed(3) + ' s']
      ];
      document.getElementById('monitorHomeCards').innerHTML = cards.map(function (card) {
        return '<div class="monitor-card"><span>' + escapeHtml(card[0]) + '</span><strong>' + escapeHtml(card[1] == null ? '—' : String(card[1])) + '</strong></div>';
      }).join('');
      setMonitorText('monitorHomeClients', JSON.stringify(home.clients, null, 2) || 'No client data.');
      setMonitorText('monitorHomeQueries', JSON.stringify(home.queryLog, null, 2) || 'No query log.');
      setMonitorText('monitorHomeRaw', JSON.stringify(home, null, 2));
      if (home.baseUrl) document.getElementById('monitorHomeUrl').value = home.baseUrl;
      return monitorApi('/api/monitoring/cloud');
    }).then(function (cloud) {
      renderCloudMonitor(cloud);
    }).catch(function (error) {
      var warning = document.getElementById('monitorAuthWarning');
      if (warning) {
        warning.style.display = '';
        warning.textContent = error.message + ' Open Settings, set a unique non-default username and password, and reload this page before connecting monitoring services.';
      }
      setMonitorText('monitorHomeMessage', error.message);
      setMonitorText('monitorCloudMessage', error.message);
    });
  }

  function renderCloudMonitor(cloud) {
    var connected = !!cloud.connected;
    document.getElementById('monitorCloudBadge').textContent = connected ? 'Connected' : 'Signed out';
    document.getElementById('monitorCloudSignIn').style.display = connected ? 'none' : '';
    document.getElementById('monitorCloudConnected').style.display = connected ? '' : 'none';
    if (!connected) {
      setMonitorText('monitorCloudMessage', cloud.message || 'Sign in to sync your DNS account.');
      document.getElementById('monitorCloudCards').innerHTML = '';
      ['monitorCloudServers','monitorCloudDevices','monitorCloudDeviceStats','monitorCloudDomainStats','monitorCloudTimeStats'].forEach(function (id) { setMonitorText(id, 'No synced data.'); });
      return;
    }
    setMonitorText('monitorCloudMessage', (cloud.email || 'AdGuard account') + ' · ' + (cloud.message || 'Synced'));
    document.getElementById('monitorCloudCards').innerHTML = [
      ['DNS profiles', countCollection(cloud.dnsServers)],
      ['Registered devices', countCollection(cloud.devices)],
      ['Device query stats', countStats(cloud.deviceStats24h)],
      ['Domain query stats', countStats(cloud.domainStats24h)]
    ].map(function (card) {
      return '<div class="monitor-card"><span>' + escapeHtml(card[0]) + '</span><strong>' + escapeHtml(String(card[1])) + '</strong></div>';
    }).join('');
    showCloudCollection('monitorCloudServers', cloud.dnsServers);
    showCloudCollection('monitorCloudDevices', cloud.devices);
    showCloudCollection('monitorCloudDeviceStats', cloud.deviceStats24h);
    showCloudCollection('monitorCloudDomainStats', cloud.domainStats24h);
    showCloudCollection('monitorCloudTimeStats', cloud.timeStats24h);
  }

  function showCloudCollection(id, result) {
    var node = document.getElementById(id);
    if (!node) return;
    if (!result || !result.ok) {
      node.textContent = result && result.error ? result.error : 'No synced data.';
    } else {
      node.textContent = JSON.stringify(result.data, null, 2);
    }
  }

  function countCollection(result) {
    if (!result || !result.ok || result.data == null) return 'Unavailable';
    var data = result.data;
    if (Array.isArray(data)) return data.length;
    if (data.items && Array.isArray(data.items)) return data.items.length;
    if (data.data && Array.isArray(data.data)) return data.data.length;
    if (data.dns_servers && Array.isArray(data.dns_servers)) return data.dns_servers.length;
    if (data.devices && Array.isArray(data.devices)) return data.devices.length;
    return 'Synced';
  }

  function countStats(result) {
    if (!result || !result.ok || result.data == null) return 'Unavailable';
    var data = result.data;
    var items = Array.isArray(data) ? data : (data.data || data.items || data.stats || []);
    if (!Array.isArray(items)) return 'Synced';
    return items.reduce(function (sum, item) {
      var count = Number(item.queries != null ? item.queries : (item.query_count != null ? item.query_count : item.count));
      return sum + (Number.isFinite(count) ? count : 0);
    }, 0);
  }

  function saveHomeMonitor() {
    var button = document.getElementById('monitorHomeSave');
    var password = document.getElementById('monitorHomePassword');
    button.disabled = true;
    setMonitorText('monitorHomeMessage', 'Saving encrypted connection settings…');
    monitorApi('/api/monitoring/home/config', 'POST', {
      baseUrl: document.getElementById('monitorHomeUrl').value,
      username: document.getElementById('monitorHomeUser').value,
      password: password.value
    }).then(function () {
      toast('AdGuard Home settings saved');
      return monitorApi('/api/monitoring/home');
    }).then(function (home) {
      document.getElementById('monitorHomeBadge').textContent = home.connected ? 'Connected' : 'Connection issue';
      setMonitorText('monitorHomeMessage', home.message || 'Saved.');
      setMonitorText('monitorHomeRaw', JSON.stringify(home, null, 2));
      setMonitorText('monitorHomeClients', JSON.stringify(home.clients, null, 2));
      setMonitorText('monitorHomeQueries', JSON.stringify(home.queryLog, null, 2));
    }).catch(function (error) {
      setMonitorText('monitorHomeMessage', error.message);
    }).then(function () {
      password.value = '';
      button.disabled = false;
    });
  }

  function forgetHomeMonitor() {
    if (!window.confirm('Disconnect AdGuard Home and erase its saved credentials?')) return;
    monitorApi('/api/monitoring/home/forget', 'POST', {}).then(function () {
      setMonitorText('monitorHomeMessage', 'Connection removed.');
      document.getElementById('monitorHomePassword').value = '';
      refreshMonitoringData();
    }).catch(toast);
  }

  function signInCloudMonitor() {
    var button = document.getElementById('monitorCloudSignInButton');
    var password = document.getElementById('monitorCloudPassword');
    button.disabled = true;
    setMonitorText('monitorCloudMessage', 'Signing in and syncing existing DNS profiles…');
    monitorApi('/api/monitoring/cloud/login', 'POST', {
      email: document.getElementById('monitorCloudEmail').value,
      password: password.value,
      mfaCode: document.getElementById('monitorCloudMfa').value
    }).then(function (cloud) {
      renderCloudMonitor(cloud);
      toast('AdGuard DNS account synced');
      password.value = '';
      refreshMonitoringData();
    }).catch(function (error) {
      setMonitorText('monitorCloudMessage', error.message);
      toast(error.message);
    }).then(function () {
      password.value = '';
      button.disabled = false;
    });
  }

  function refreshCloudMonitor() {
    var button = document.getElementById('monitorCloudSync');
    button.disabled = true;
    monitorApi('/api/monitoring/cloud/refresh', 'POST', {}).then(function (cloud) {
      renderCloudMonitor(cloud);
      toast('AdGuard DNS data refreshed');
    }).catch(toast).then(function () { button.disabled = false; });
  }

  function signOutCloudMonitor() {
    if (!window.confirm('Sign out and erase the saved AdGuard DNS session tokens?')) return;
    monitorApi('/api/monitoring/cloud/logout', 'POST', {}).then(function () {
      toast('Signed out of AdGuard DNS');
      refreshMonitoringData();
    }).catch(toast);
  }

  function saveProxyMonitor() {
    var button = document.getElementById('monitorProxySave');
    button.disabled = true;
    var port = parseInt(document.getElementById('monitorProxyPort').value || '0', 10);
    monitorApi('/api/monitoring/proxy', 'POST', { port: port }).then(function (result) {
      setMonitorText('monitorProxyMessage', result.listening ? 'TCP listener detected on 127.0.0.1:' + result.port + '.' :
        (result.port === 0 ? 'Listener check disabled.' : 'No listener detected at 127.0.0.1:' + result.port + '. Check the Every Proxy port.'));
      refreshMonitoringData();
    }).catch(function (error) {
      setMonitorText('monitorProxyMessage', error.message);
    }).then(function () { button.disabled = false; });
  }

  function formatMonitorMb(value) {
    var number = Number(value);
    return Number.isFinite(number) ? (Math.round(number * 10) / 10).toString() : '—';
  }

  function setMonitorText(id, value) {
    var node = document.getElementById(id);
    if (node) node.textContent = value == null ? '' : String(value);
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
