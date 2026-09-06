#!/usr/bin/env python3
import http.server
import socket
import socketserver
import threading
import urllib.parse


class Http(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        try:
            size = min(max(int(query.get("size", ["1048576"])[0]), 0), 100 * 1024 * 1024)
        except ValueError:
            self.send_error(400)
            return
        data = bytes(range(251)) * 256
        self.connection.settimeout(15)
        self.send_response(200)
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            remaining = size
            while remaining:
                count = min(remaining, len(data))
                self.wfile.write(data if count == len(data) else data[:count])
                remaining -= count
        except (TimeoutError, BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


class Tcp(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(15)
        try:
            while data := self.request.recv(16384):
                self.request.sendall(data)
        except (TimeoutError, ConnectionError):
            pass


class Udp(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        sock.sendto(data, self.client_address)


class BoundedThreads(socketserver.ThreadingMixIn):
    daemon_threads = True

    def __init__(self, *args, **kwargs):
        self.workers = threading.BoundedSemaphore(64)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self.workers.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.workers.release()


class TcpServer(BoundedThreads, socketserver.TCPServer):
    allow_reuse_address = True
    request_queue_size = 64


class HttpServer(BoundedThreads, http.server.HTTPServer):
    request_queue_size = 64


class Http6(HttpServer):
    address_family = socket.AF_INET6


class Tcp6(TcpServer):
    address_family = socket.AF_INET6


class UdpServer(socketserver.UDPServer):
    max_packet_size = 65535
    allow_reuse_address = True


class Udp6(UdpServer):
    address_family = socket.AF_INET6


if __name__ == "__main__":
    servers = [HttpServer(("127.0.0.1", 18080), Http),
               TcpServer(("127.0.0.1", 18081), Tcp),
               UdpServer(("127.0.0.1", 18082), Udp),
               Http6(("::1", 18080), Http), Tcp6(("::1", 18081), Tcp),
               Udp6(("::1", 18082), Udp)]
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Fixtures ready: HTTP 18080, TCP echo 18081, UDP echo 18082", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        for server in servers:
            server.shutdown()
            server.server_close()
