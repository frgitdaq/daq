from __future__ import absolute_import
from scapy.all import send, IP, UDP, DNS, DNSQR
import sys
import os

arguments = sys.argv

function = str(arguments[1])
domain = str(arguments[2])

multicast_address = "224.0.0.251"
mdns_port = 5353

if function == "send-mdns":
    ip = IP(dst=multicast_address)
    udp = UDP(sport=mdns_port, dport=mdns_port)
    dns = DNS(rd=1,qd=DNSQR(qname=domain, qtype="A"))
    send(ip/udp/dns)
