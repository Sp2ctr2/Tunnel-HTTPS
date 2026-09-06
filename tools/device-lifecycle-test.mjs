#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const toolsDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.resolve(toolsDirectory, "..");
const defaultResultsDirectory = path.join(projectDirectory, "tools", "device-probe", "results");
const DEFAULT_PORT = 18333;
const DEFAULT_CYCLES = 20;
const DEFAULT_STATE_DEADLINE_MS = 8_000;
const POLL_INTERVAL_MS = 100;
const CDP_EVALUATE_TIMEOUT_MS = 2_000;
const MAX_CYCLES = 20;
const MAX_STATE_DEADLINE_MS = 8_000;
const ALLOWED_STATES = new Set(["connecting", "connected", "disconnected", "error"]);

function usage() {
  process.stdout.write([
    "Usage: node tools/device-lifecycle-test.mjs [options]",
    "",
    "Runs bounded stop/start cycles through TunnelAndroid in the debug WebView.",
    "The default is 20 cycles with an 8-second deadline for each terminal state.",
    "",
    "Options:",
    "  --port N                 local native CDP HTTP port (default: 18333)",
    "  --cycles N               cycles, 1..20 (default: 20)",
    "  --state-timeout-ms N     per-state deadline, 1000..8000 (default: 8000)",
    "  --burst                  queue one stop/start pair without waiting between commands",
    "  --output PATH            results JSON path (default: tools/device-probe/results/...) ",
    "  -h, --help               show this help"
  ].join("\n") + "\n");
}

function integerOption(name, value, minimum, maximum) {
  if (!/^\d+$/.test(value || "")) throw new Error(`${name} must be an integer`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return parsed;
}

function parseArguments(argv) {
  const options = {
    port: DEFAULT_PORT,
    cycles: DEFAULT_CYCLES,
    stateTimeoutMs: DEFAULT_STATE_DEADLINE_MS,
    burst: false,
    output: path.join(
      defaultResultsDirectory,
      `device-lifecycle-${new Date().toISOString().replace(/[:.]/g, "-")}.json`
    )
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "-h" || argument === "--help") {
      usage();
      return null;
    }
    if (argument === "--burst") {
      options.burst = true;
      continue;
    }
    const value = argv[++index];
    if (value === undefined) throw new Error(`${argument} requires a value`);
    if (argument === "--port") options.port = integerOption(argument, value, 1, 65535);
    else if (argument === "--cycles") options.cycles = integerOption(argument, value, 1, MAX_CYCLES);
    else if (argument === "--state-timeout-ms") {
      options.stateTimeoutMs = integerOption(argument, value, 1_000, MAX_STATE_DEADLINE_MS);
    } else if (argument === "--output") {
      options.output = path.resolve(value);
    } else {
      throw new Error(`unknown option: ${argument}`);
    }
  }
  return options;
}

class CdpClient {
  constructor(webSocketDebuggerUrl) {
    if (typeof WebSocket !== "function") {
      throw new Error("Node WebSocket support is required");
    }
    this.socket = new WebSocket(webSocketDebuggerUrl);
    this.nextId = 1;
    this.pending = new Map();
    this.closed = false;
    this.socket.addEventListener("message", event => this.onMessage(event));
    this.socket.addEventListener("close", () => this.failPending("CDP socket closed"));
    this.socket.addEventListener("error", () => this.failPending("CDP socket error"));
  }

  async connect() {
    if (this.socket.readyState === 1) return;
    await new Promise((resolve, reject) => {
      const onOpen = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(new Error("unable to open CDP WebSocket"));
      };
      const cleanup = () => {
        this.socket.removeEventListener("open", onOpen);
        this.socket.removeEventListener("error", onError);
      };
      this.socket.addEventListener("open", onOpen);
      this.socket.addEventListener("error", onError);
    });
  }

  onMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (!message.id) return;
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id);
    clearTimeout(pending.timeout);
    if (message.error) pending.reject(new Error("CDP evaluate failed"));
    else pending.resolve(message);
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(new Error(message));
    }
    this.pending.clear();
  }

  evaluate(expression, timeoutMs = CDP_EVALUATE_TIMEOUT_MS) {
    if (this.closed) return Promise.reject(new Error("CDP client is closed"));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("CDP evaluation timed out"));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timeout });
      try {
        this.socket.send(JSON.stringify({
          id,
          method: "Runtime.evaluate",
          params: { expression, returnByValue: true, awaitPromise: true }
        }));
      } catch (error) {
        clearTimeout(timeout);
        this.pending.delete(id);
        reject(error);
      }
    }).then(message => {
      const result = message.result || {};
      if (result.exceptionDetails) throw new Error("WebView JavaScript exception");
      return result.result?.value;
    });
  }

  close() {
    this.closed = true;
    this.failPending("CDP client closed");
    try {
      this.socket.close();
    } catch {}
  }
}

async function findWebView(port) {
  const response = await fetch(`http://127.0.0.1:${port}/json`);
  if (!response.ok) throw new Error(`CDP target listing failed (${response.status})`);
  const tabs = await response.json();
  const tab = tabs.find(candidate =>
    typeof candidate.url === "string" && candidate.url.includes("/assets/index.html")
  );
  if (!tab?.webSocketDebuggerUrl) throw new Error("Tunnel HTTPS WebView not found");
  return tab;
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

const snapshotExpression = `(() => {
  const raw = JSON.parse(TunnelAndroid.getCurrentStateSnapshot());
  return {
    state: String(raw.state || ""),
    generation: Number(raw.generation),
    seconds: Number(raw.seconds) || 0,
    reason: String(raw.reason || "").slice(0, 64)
  };
})()`;

function commandExpression(shouldStart) {
  return `(() => { TunnelAndroid.toggleVpn(${shouldStart ? "true" : "false"}); return true; })()`;
}

function validateSnapshot(snapshot) {
  assert.ok(snapshot && typeof snapshot === "object", "invalid native state snapshot");
  assert.ok(ALLOWED_STATES.has(snapshot.state), `unexpected native state: ${snapshot.state}`);
  assert.ok(Number.isSafeInteger(snapshot.generation) && snapshot.generation >= 0, "invalid generation");
  return snapshot;
}

async function runProbe(options) {
  const startedAt = new Date().toISOString();
  const result = {
    schema: 1,
    startedAt,
    port: options.port,
    cyclesRequested: options.cycles,
    stateTimeoutMs: options.stateTimeoutMs,
    burstRequested: options.burst,
    status: "running",
    generationMonotonic: true,
    observations: [],
    cycles: [],
    burst: null,
    finalSnapshot: null,
    error: null
  };
  let client;
  let lastGeneration = null;
  let observationNumber = 0;
  const observe = (label, snapshot) => {
    const checked = validateSnapshot(snapshot);
    if (lastGeneration !== null && checked.generation < lastGeneration) {
      result.generationMonotonic = false;
      throw new Error(`generation regressed from ${lastGeneration} to ${checked.generation}`);
    }
    lastGeneration = checked.generation;
    const observation = {
      number: ++observationNumber,
      label,
      state: checked.state,
      generation: checked.generation,
      reason: checked.reason
    };
    result.observations.push(observation);
    return checked;
  };
  const readSnapshot = async label => {
    const snapshot = await client.evaluate(snapshotExpression);
    return observe(label, snapshot);
  };
  const issue = async shouldStart => {
    await client.evaluate(commandExpression(shouldStart));
  };
  const waitForState = async (target, label, minimumGeneration = null) => {
    const start = performance.now();
    let latest = await readSnapshot(`${label}:poll`);
    while (
      latest.state !== target ||
      (minimumGeneration !== null && latest.generation < minimumGeneration)
    ) {
      if (latest.state === "error") throw new Error(`${label} entered error state`);
      const elapsed = performance.now() - start;
      if (elapsed >= options.stateTimeoutMs) {
        throw new Error(
          `${label} did not reach ${target} at generation >= ${minimumGeneration ?? "any"} ` +
          `within ${options.stateTimeoutMs}ms`
        );
      }
      await sleep(Math.min(POLL_INTERVAL_MS, options.stateTimeoutMs - elapsed));
      latest = await readSnapshot(`${label}:poll`);
    }
    return {
      snapshot: latest,
      minimumGeneration,
      elapsedMs: Math.round(performance.now() - start)
    };
  };

  try {
    const tab = await findWebView(options.port);
    client = new CdpClient(tab.webSocketDebuggerUrl);
    await client.connect();
    const initial = await readSnapshot("initial");
    assert.equal(initial.state, "connected", "lifecycle probe requires an initially connected service");

    for (let cycle = 1; cycle <= options.cycles; cycle += 1) {
      const before = await readSnapshot(`cycle-${cycle}-before`);
      const cycleResult = { cycle, before, stop: null, start: null };
      await issue(false);
      cycleResult.stop = await waitForState(
        "disconnected",
        `cycle-${cycle}-stop`,
        before.generation + 1
      );
      await issue(true);
      cycleResult.start = await waitForState(
        "connected",
        `cycle-${cycle}-start`,
        cycleResult.stop.snapshot.generation + 1
      );
      result.cycles.push(cycleResult);
    }

    if (options.burst) {
      const prior = await readSnapshot("burst-before");
      assert.equal(prior.state, "connected", "burst probe requires a connected prior state");
      const burstStarted = performance.now();
      await client.evaluate(`(() => {
        TunnelAndroid.toggleVpn(false);
        TunnelAndroid.toggleVpn(true);
        return true;
      })()`);
      const final = await waitForState("connected", "burst-final", prior.generation + 2);
      result.burst = {
        queuedStopThenStart: true,
        prior,
        minimumGeneration: prior.generation + 2,
        elapsedMs: Math.round(performance.now() - burstStarted),
        final
      };
    }

    result.finalSnapshot = await readSnapshot("final");
    assert.equal(result.finalSnapshot.state, "connected", "final state must be connected");
    assert.equal(result.generationMonotonic, true, "service generation must be monotonic");
    result.status = "passed";
  } catch (error) {
    result.status = "failed";
    result.error = String(error?.message || error).slice(0, 256);
  } finally {
    client?.close();
  }
  return result;
}

async function main() {
  let options;
  try {
    options = parseArguments(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${String(error?.message || error)}\n`);
    usage();
    process.exitCode = 2;
    return;
  }
  if (options === null) return;

  const result = await runProbe(options);
  fs.mkdirSync(path.dirname(options.output), { recursive: true });
  fs.writeFileSync(options.output, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  process.stdout.write(`${result.status}: ${result.cycles.length}/${options.cycles} cycles; ` +
    `generationMonotonic=${result.generationMonotonic}; results=${options.output}\n`);
  if (result.status !== "passed") process.exitCode = 1;
}

await main();
