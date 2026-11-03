/*
 * navigator.bluetooth (Web Bluetooth) untuk WebView Android.
 * Dipasang otomatis oleh aplikasi. Meneruskan semua perintah ke objek native `AndroidBle`.
 * Kode situs yang memakai Web Bluetooth tidak perlu diubah.
 */
(function () {
  'use strict';
  if (window.__kasirBle || !window.AndroidBle) return;

  var BASE = '-0000-1000-8000-00805f9b34fb';

  function normUuid(u) {
    if (typeof u === 'number') return ('00000000' + u.toString(16)).slice(-8) + BASE;
    u = String(u).toLowerCase();
    if (/^[0-9a-f]{4}$/.test(u)) return '0000' + u + BASE;
    if (/^[0-9a-f]{8}$/.test(u)) return u + BASE;
    return u;
  }

  function err(name, message) {
    return new DOMException(message, name);
  }

  var pending = {};
  var seq = 0;
  var devices = {};

  // Menjalankan perintah native dan menunggu hasilnya (native memanggil __kasirBle.done).
  function call(start) {
    return new Promise(function (resolve, reject) {
      var id = 'r' + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        start(id);
      } catch (e) {
        delete pending[id];
        reject(err('NetworkError', String(e && e.message ? e.message : e)));
      }
    });
  }

  function toBase64(data) {
    var u8;
    if (data instanceof ArrayBuffer) {
      u8 = new Uint8Array(data);
    } else if (ArrayBuffer.isView(data)) {
      u8 = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    } else {
      throw new TypeError('Data harus berupa ArrayBuffer atau TypedArray');
    }
    var s = '';
    var step = 0x8000;
    for (var i = 0; i < u8.length; i += step) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + step));
    }
    return btoa(s);
  }

  class Characteristic extends EventTarget {
    constructor(service, info) {
      super();
      this.service = service;
      this.uuid = info.uuid;
      this.value = null;
      this.properties = {
        broadcast: false,
        read: !!info.read,
        writeWithoutResponse: !!info.writeNoResp,
        write: !!info.write,
        notify: !!info.notify,
        indicate: !!info.indicate,
        authenticatedSignedWrites: false,
        reliableWrite: false,
        writableAuxiliaries: false
      };
      this._virtual = !!info.virtual;
    }
    _write(data, mode) {
      var b64;
      try {
        b64 = toBase64(data);
      } catch (e) {
        return Promise.reject(e);
      }
      var self = this;
      return call(function (id) {
        AndroidBle.write(id, self.service.device.id, self.service.uuid, self.uuid, b64, mode);
      }).then(function () { return undefined; });
    }
    writeValue(data) { return this._write(data, 'any'); }
    writeValueWithResponse(data) { return this._write(data, 'response'); }
    writeValueWithoutResponse(data) { return this._write(data, 'noresponse'); }
    startNotifications() { return Promise.resolve(this); }
    stopNotifications() { return Promise.resolve(this); }
    readValue() {
      return Promise.reject(err('NotSupportedError', 'readValue belum didukung di aplikasi'));
    }
  }

  class Service {
    constructor(device, info) {
      this.device = device;
      this.uuid = info.uuid;
      this.isPrimary = true;
      this._virtual = !!info.virtual;
      var self = this;
      this._chars = (info.characteristics || []).map(function (c) {
        return new Characteristic(self, c);
      });
    }
    getCharacteristic(u) {
      var id = normUuid(u);
      var found = this._chars.filter(function (c) { return c.uuid === id; })[0];
      if (!found && this._virtual) found = this._chars[0];
      return found
        ? Promise.resolve(found)
        : Promise.reject(err('NotFoundError', 'Characteristic ' + id + ' tidak ditemukan'));
    }
    getCharacteristics(u) {
      var list = this._chars;
      if (u !== undefined) {
        var id = normUuid(u);
        list = list.filter(function (c) { return c.uuid === id; });
        if (!list.length && this._virtual) list = this._chars.slice(0, 1);
        if (!list.length) {
          return Promise.reject(err('NotFoundError', 'Characteristic ' + id + ' tidak ditemukan'));
        }
      }
      return Promise.resolve(list);
    }
  }

  class Server {
    constructor(device) {
      this.device = device;
      this.connected = false;
      this._services = [];
    }
    connect() {
      var self = this;
      return call(function (id) { AndroidBle.connect(id, self.device.id); }).then(function (list) {
        self.connected = true;
        self._services = (list || []).map(function (s) { return new Service(self.device, s); });
        return self;
      });
    }
    disconnect() {
      this.connected = false;
      AndroidBle.disconnect(this.device.id);
    }
    getPrimaryService(u) {
      var id = normUuid(u);
      var found = this._services.filter(function (s) { return s.uuid === id; })[0];
      if (!found) found = this._services.filter(function (s) { return s._virtual; })[0];
      return found
        ? Promise.resolve(found)
        : Promise.reject(err('NotFoundError', 'Service ' + id + ' tidak ditemukan'));
    }
    getPrimaryServices(u) {
      var list = this._services;
      if (u !== undefined) {
        var id = normUuid(u);
        list = list.filter(function (s) { return s.uuid === id; });
        if (!list.length) list = this._services.filter(function (s) { return s._virtual; });
        if (!list.length) {
          return Promise.reject(err('NotFoundError', 'Service ' + id + ' tidak ditemukan'));
        }
      }
      return Promise.resolve(list);
    }
  }

  class Device extends EventTarget {
    constructor(info) {
      super();
      this.id = info.id;
      this.name = info.name || null;
      this.watchingAdvertisements = false;
      this.gatt = new Server(this);
    }
  }

  window.__kasirBle = {
    done: function (id, ok, payload) {
      var p = pending[id];
      if (!p) return;
      delete pending[id];
      var data = null;
      try { data = payload ? JSON.parse(payload) : null; } catch (e) { data = null; }
      if (ok) {
        p.resolve(data);
      } else {
        p.reject(err((data && data.name) || 'NetworkError', (data && data.message) || 'Kesalahan Bluetooth'));
      }
    },
    disconnected: function (address) {
      var d = devices[address];
      if (!d) return;
      d.gatt.connected = false;
      var ev = new Event('gattserverdisconnected');
      d.dispatchEvent(ev);
      if (typeof d.ongattserverdisconnected === 'function') d.ongattserverdisconnected(ev);
    }
  };

  var bluetooth = {
    requestDevice: function () {
      return call(function (id) { AndroidBle.requestDevice(id); }).then(function (info) {
        var d = devices[info.id];
        if (!d) d = devices[info.id] = new Device(info);
        if (info.name) d.name = info.name;
        return d;
      });
    },
    getAvailability: function () { return Promise.resolve(true); },
    getDevices: function () { return Promise.resolve([]); },
    addEventListener: function () {},
    removeEventListener: function () {}
  };

  try {
    Object.defineProperty(navigator, 'bluetooth', { value: bluetooth, configurable: true });
  } catch (e) {
    try { navigator.bluetooth = bluetooth; } catch (e2) { /* diabaikan */ }
  }
})();
