#!/usr/bin/env python3
"""Free TCP port on the loop (127.0.0.1) for verification servers: tools/stress.sh, tools/mp_scenario.sh."""
import socket

with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
