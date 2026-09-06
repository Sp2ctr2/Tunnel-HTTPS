#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const toolsDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.resolve(toolsDirectory, "..");
const indexPath = path.join(projectDirectory, "app", "src", "main", "assets", "index.html");
const indexHtml = fs.readFileSync(indexPath, "utf8");
const scriptMatch = indexHtml.match(/<script>\s*([\s\S]*?)\s*<\/script>/);
if (!scriptMatch) throw new Error(`inline UI script not found: ${indexPath}`);
const uiScript = scriptMatch[1];

class ClassList {
  constructor(value = "") {
    this.values = new Set(String(value).split(/\s+/).filter(Boolean));
  }

  add(...names) {
    names.forEach(name => this.values.add(name));
  }

  remove(...names) {
    names.forEach(name => this.values.delete(name));
  }

  contains(name) {
    return this.values.has(name);
  }

  toggle(name, force) {
    const enabled = force === undefined ? !this.values.has(name) : !!force;
    if (enabled) this.values.add(name);
    else this.values.delete(name);
    return enabled;
  }

  toString() {
    return [...this.values].join(" ");
  }
}

class ElementStub {
  constructor(document, { id = "", className = "", dataset = {} } = {}) {
    this.ownerDocument = document;
    this.id = id;
    this.classList = new ClassList(className);
    this.attributes = new Map();
    this.dataset = { ...dataset };
    this.style = {};
    this.children = [];
    this.parentNode = null;
    this.textContent = "";
    this.value = "";
    this.tabIndex = 0;
    this.scrollTop = 0;
    this.onclick = null;
    this.onkeydown = null;
    this.oninput = null;
    this.onsubmit = null;
    this._innerHTML = "";
  }

  get className() {
    return this.classList.toString();
  }

  set className(value) {
    this.classList = new ClassList(value);
  }

  get innerHTML() {
    return this._innerHTML;
  }

  set innerHTML(value) {
    this._innerHTML = String(value);
    this.children.forEach(child => { child.parentNode = null; });
    this.children = [];
  }

  get lastElementChild() {
    return this.children.length ? this.children[this.children.length - 1] : null;
  }

  setAttribute(name, value) {
    const stringValue = String(value);
    this.attributes.set(name, stringValue);
    if (name === "class") this.className = stringValue;
    if (name === "id") this.id = stringValue;
    if (name.startsWith("data-")) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      this.dataset[key] = stringValue;
    }
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  insertAdjacentHTML(position, html) {
    if (position !== "afterbegin") throw new Error(`unsupported insertAdjacentHTML position: ${position}`);
    const child = new ElementStub(this.ownerDocument);
    child._innerHTML = String(html);
    child.remove = () => {
      const index = this.children.indexOf(child);
      if (index >= 0) this.children.splice(index, 1);
      child.parentNode = null;
    };
    child.parentNode = this;
    this.children.unshift(child);
  }

  remove() {
    if (!this.parentNode) return;
    const index = this.parentNode.children.indexOf(this);
    if (index >= 0) this.parentNode.children.splice(index, 1);
    this.parentNode = null;
  }

  click() {
    if (typeof this.onclick === "function") {
      this.onclick({
        target: this,
        preventDefault() {},
        stopPropagation() {}
      });
    }
  }

  focus() {
    this.ownerDocument.activeElement = this;
  }

  getBoundingClientRect() {
    return { width: 300, height: 64 };
  }
}

function decodeHtml(value) {
  return String(value)
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function dynamicButtons(document, host, attribute) {
  const cacheKey = `${host.id}:${attribute}`;
  const cached = document.dynamicButtonsCache.get(cacheKey);
  if (cached && cached.html === host.innerHTML) return cached.buttons;
  const pattern = new RegExp(`${attribute}="([^"]*)"`, "g");
  const buttons = [...host.innerHTML.matchAll(pattern)].map(match => {
    const button = new ElementStub(document);
    button.dataset[attribute.slice(5)] = decodeHtml(match[1]);
    return button;
  });
  document.dynamicButtonsCache.set(cacheKey, { html: host.innerHTML, buttons });
  return buttons;
}

class DocumentStub {
  constructor() {
    this.elements = new Map();
    this.dynamicButtonsCache = new Map();
    this.activeElement = null;
    this.bottom = this.add(new ElementStub(this, { className: "bottom" }));
    this.signal = this.add(new ElementStub(this, { className: "signal-wrap" }));
    this.navItems = [
      this.add(new ElementStub(this, { id: "nav-secure", className: "nav-item active", dataset: { tab: "secure" } })),
      this.add(new ElementStub(this, { id: "nav-insights", className: "nav-item", dataset: { tab: "insights" } })),
      this.add(new ElementStub(this, { id: "nav-settings", className: "nav-item", dataset: { tab: "settings" } }))
    ];
    this.tabs = [
      this.add(new ElementStub(this, { id: "secure", className: "tab active" })),
      this.add(new ElementStub(this, { id: "insights", className: "tab" })),
      this.add(new ElementStub(this, { id: "settings", className: "tab" }))
    ];
    const switches = [
      ["adBlockToggle", "switch on"],
      ["turboToggle", "switch"],
      ["browserOnlyToggle", "switch"],
      ["dohToggle", "switch on"],
      ["routeAll", "switch on locked"],
      ["batteryToggle", "switch"],
      ["autoStartToggle", "switch"]
    ];
    switches.forEach(([id, className]) => this.add(new ElementStub(this, { id, className })));
    [
      "phone", "pc", "timer", "badgeText", "mainTitle", "mainSub", "protect", "adPill", "adStatus",
      "turboPill", "ping", "httpsState", "dohState", "rx", "tx", "total", "totalUnit", "bw", "graph",
      "logs", "boot", "bootGrad", "splitForm", "splitInput", "splitErr", "chips", "appSearch", "selectedApps",
      "appList", "power"
    ].forEach(id => this.add(new ElementStub(this, { id })));
  }

  add(element) {
    if (element.id) this.elements.set(element.id, element);
    return element;
  }

  getElementById(id) {
    return this.elements.get(id) || null;
  }

  querySelector(selector) {
    if (selector === ".bottom") return this.bottom;
    if (selector === ".signal-wrap") return this.signal;
    return null;
  }

  querySelectorAll(selector) {
    if (selector === ".switch") return [...this.elements.values()].filter(element => element.classList.contains("switch"));
    if (selector === ".nav-item") return [...this.navItems];
    if (selector === ".tab") return [...this.tabs];
    if (selector === "[data-rd]") return dynamicButtons(this, this.getElementById("chips"), "data-rd");
    if (selector === "[data-app]") return dynamicButtons(this, this.getElementById("appList"), "data-app");
    if (selector === "[data-ra]") return dynamicButtons(this, this.getElementById("selectedApps"), "data-ra");
    return [];
  }
}

function createHarness() {
  const document = new DocumentStub();
  let nextHandle = 1;
  const timeouts = new Map();
  const intervals = new Map();
  const listeners = new Map();
  const calls = {
    refreshProtection: 0,
    configureSplit: [],
    setTurboEnabled: [],
    requestNetworkSnapshot: [],
    installedApps: 0
  };
  const native = {
    getSavedConfig: () => JSON.stringify({}),
    getTurboState: () => JSON.stringify({ desired: false, state: "DISABLED", generation: 0, reason: "none", transitioning: false }),
    getCurrentStateSnapshot: () => JSON.stringify({ state: "disconnected", seconds: 0, reason: "SERVICE_RESTORE", generation: 0 }),
    getServiceGeneration: () => 0,
    getTrafficStats: () => JSON.stringify({ available: false }),
    getTurboAiStatus: () => JSON.stringify({ enabled: false, available: false, state: "fallback", samples: 0 }),
    getInstalledApps: () => {
      calls.installedApps += 1;
      return JSON.stringify([
        { label: "Browser", packageName: "com.example.browser" },
        { label: "Mail", packageName: "com.example.mail" }
      ]);
    },
    configureSplit: (...args) => calls.configureSplit.push(args),
    configureDns: () => {},
    configureAdvanced: () => {},
    configureAutoStart: () => {},
    refreshProtection: () => { calls.refreshProtection += 1; },
    setTurboEnabled: (...args) => calls.setTurboEnabled.push(args),
    requestNetworkSnapshot: (...args) => calls.requestNetworkSnapshot.push(args),
    notifyTurboEnabling: () => {},
    toggleVpn: () => {},
    vibrate: () => {}
  };

  const setTimeoutFake = (callback, delay, ...args) => {
    const handle = nextHandle++;
    timeouts.set(handle, { callback, delay, args });
    return handle;
  };
  const clearTimeoutFake = handle => timeouts.delete(handle);
  const setIntervalFake = (callback, delay, ...args) => {
    const handle = nextHandle++;
    intervals.set(handle, { callback, delay, args });
    return handle;
  };
  const clearIntervalFake = handle => intervals.delete(handle);
  const flushTimeouts = (limit = 100) => {
    let count = 0;
    while (timeouts.size > 0) {
      if (++count > limit) throw new Error("fake timer queue did not settle");
      const [handle, timer] = timeouts.entries().next().value;
      timeouts.delete(handle);
      timer.callback(...timer.args);
    }
  };

  const context = {
    console,
    document,
    TunnelAndroid: native,
    Date,
    setTimeout: setTimeoutFake,
    clearTimeout: clearTimeoutFake,
    setInterval: setIntervalFake,
    clearInterval: clearIntervalFake,
    requestAnimationFrame: callback => setTimeoutFake(() => callback(Date.now()), 0),
    cancelAnimationFrame: clearTimeoutFake,
    addEventListener: (type, listener) => {
      const registered = listeners.get(type) || [];
      registered.push(listener);
      listeners.set(type, registered);
    },
    removeEventListener: (type, listener) => {
      const registered = listeners.get(type) || [];
      listeners.set(type, registered.filter(candidate => candidate !== listener));
    }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(uiScript, context, { filename: indexPath });

  return {
    context,
    document,
    calls,
    flushTimeouts,
    element: id => document.getElementById(id)
  };
}

function connect(harness, generation = 1) {
  harness.context.window.setVpnState("connecting", 0, "", "USER_START", generation);
  harness.context.window.setVpnState("connected", 0, "", "USER_START", generation);
  harness.flushTimeouts();
  assert.equal(harness.element("phone").classList.contains("on"), true);
}

function testTurboStates() {
  const harness = createHarness();
  const { context, element } = harness;

  context.window.setTurboState({ desired: true, state: "ACTIVE", generation: 1, reason: "ready", transitioning: false });
  assert.equal(element("turboPill").textContent, "READY");
  assert.equal(element("phone").classList.contains("turbo"), false);

  connect(harness, 2);
  assert.equal(element("turboPill").textContent, "ACTIVE");
  assert.equal(element("phone").classList.contains("turbo"), true);

  context.window.setTurboState({ desired: true, state: "DEGRADED", generation: 3, reason: "probe", transitioning: false });
  assert.equal(element("turboPill").textContent, "READY");
  assert.equal(element("phone").classList.contains("turbo"), false);

  context.window.setTurboState({ desired: true, state: "ACTIVE", generation: 4, reason: "refresh", transitioning: true });
  assert.equal(element("turboPill").textContent, "READY");
  assert.equal(element("phone").classList.contains("turbo"), false);

  context.window.setVpnState("disconnected", 0, "", "USER_STOP", 5);
  assert.equal(element("turboPill").textContent, "READY");
  assert.equal(element("phone").classList.contains("turbo"), false);
}

function testConnectedSplitAndAppRefreshQueue() {
  const harness = createHarness();
  const { context, document, calls, element } = harness;
  connect(harness, 10);
  calls.refreshProtection = 0;
  calls.configureSplit.length = 0;

  element("splitInput").value = "example.com";
  element("splitForm").onsubmit({ preventDefault() {} });
  assert.equal(calls.refreshProtection, 1);
  assert.equal(calls.configureSplit.at(-1)[0], "example.com");

  document.getElementById("nav-settings").click();
  const appButton = document.querySelectorAll("[data-app]").find(button => button.dataset.app === "com.example.browser");
  assert.ok(appButton, "rendered app bypass button should be discoverable through the DOM stub");
  appButton.click();
  assert.equal(calls.refreshProtection, 1, "app edit should queue behind the in-flight split refresh");
  assert.equal(calls.configureSplit.at(-1)[0], "example.com");
  assert.equal(calls.configureSplit.at(-1)[1], "com.example.browser");

  context.window.setVpnState("connecting", 0, "", "SETTINGS_REFRESH", 11, 1);
  context.window.setVpnState("connected", 0, "", "SETTINGS_REFRESH", 11, 1);
  assert.equal(calls.refreshProtection, 2, "queued app refresh should start after the split refresh completes");

  context.window.setVpnState("connecting", 0, "", "SETTINGS_REFRESH", 12, 2);
  context.window.setVpnState("connected", 0, "", "SETTINGS_REFRESH", 12, 2);
  assert.equal(calls.refreshProtection, 2);
  assert.equal(vm.runInContext("configRefresh === null && settingsRefreshQueued === false", context), true);
}

const tests = [
  ["Turbo status and theme follow connected/active semantics", testTurboStates],
  ["connected split and app edits queue and drain refreshes", testConnectedSplitAndAppRefreshQueue]
];

for (const [name, test] of tests) {
  test();
  process.stdout.write(`ok - ${name}\n`);
}
process.stdout.write(`passed ${tests.length} UI runtime tests\n`);
