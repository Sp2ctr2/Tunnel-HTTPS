const tabs = await (await fetch('http://127.0.0.1:18333/json')).json();
const tab = tabs.find(t => t.url.includes('/assets/index.html'));
if (!tab) throw new Error('Tunnel HTTPS WebView not found');
const expression = process.argv[2];
if (!expression) throw new Error('Supply a JavaScript expression');
const socket = new WebSocket(tab.webSocketDebuggerUrl);
const timeout = setTimeout(() => { socket.close(); process.exit(2); }, 15000);
socket.addEventListener('open', () => socket.send(JSON.stringify({
  id: 1, method: 'Runtime.evaluate', params: {expression, returnByValue: true, awaitPromise: true}
})));
socket.addEventListener('message', event => {
  const response = JSON.parse(event.data);
  if (response.id !== 1) return;
  console.log(JSON.stringify(response.result ?? response.error, null, 2));
  clearTimeout(timeout);
  socket.close();
  if (response.error || response.result?.exceptionDetails) process.exitCode = 1;
});
socket.addEventListener('error', event => { console.error(event.message); process.exit(1); });
