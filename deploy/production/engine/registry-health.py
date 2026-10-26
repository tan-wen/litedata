import socket

with socket.create_connection(('127.0.0.1', 2181), timeout=3) as client:
    client.sendall(b'ruok')
    if client.recv(32) != b'imok':
        raise SystemExit(1)
