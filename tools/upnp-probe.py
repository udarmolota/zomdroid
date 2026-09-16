#!/usr/bin/env python3
"""Read-only UPnP/IGD probe of the LAN router, for checking phone-hosted coop servers.

Usage:  python tools/upnp-probe.py [gateway-ip]      (default: 192.168.1.1)

Prints every SSDP responder, then for each WANIPConnection/WANPPPConnection service the WAN
status, the router's external IP (flagging CGNAT and private ranges) and its port mappings,
plus a direct lookup of UDP 16261, the Project Zomboid default port. Sends only Get* SOAP
actions: it never adds or deletes a mapping.
"""
import ipaddress
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from xml.etree import ElementTree as ET

GATEWAY = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.1"
PZ_PORT = 16261
SEARCH_TARGETS = (
    "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
    "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
    "upnp:rootdevice",
)


def local_ip_towards(host):
    """Address of the interface that routes to `host` (connect() on UDP sends nothing)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((host, 1900))
        return s.getsockname()[0]
    finally:
        s.close()


def discover(local_ip):
    """M-SEARCH by multicast and straight at the gateway; returns {location: info}."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    if hasattr(socket, "SIO_UDP_CONNRESET"):
        # Windows turns an ICMP "port unreachable" for the unicast probe into a recv error.
        s.ioctl(socket.SIO_UDP_CONNRESET, False)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local_ip))
    s.bind((local_ip, 0))
    s.settimeout(0.3)
    for st in SEARCH_TARGETS:
        msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
               "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: %s\r\n\r\n" % st).encode()
        s.sendto(msg, ("239.255.255.250", 1900))
        s.sendto(msg, (GATEWAY, 1900))
    found = {}
    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            data, addr = s.recvfrom(65535)
        except (socket.timeout, ConnectionResetError):
            continue
        text = data.decode("utf-8", "replace")
        loc = re.search(r"(?im)^location:\s*(\S+)", text)
        if not loc:
            continue
        server = re.search(r"(?im)^server:\s*(.+?)\s*$", text)
        st = re.search(r"(?im)^st:\s*(\S+)", text)
        info = found.setdefault(loc.group(1), {
            "from": addr[0], "server": server.group(1) if server else "", "st": set()})
        if st:
            info["st"].add(st.group(1))
    s.close()
    return found


def local_name(element):
    return element.tag.rsplit("}", 1)[-1]


def soap(url, service, action, inner=""):
    body = ('<?xml version="1.0"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            '<u:%s xmlns:u="%s">%s</u:%s></s:Body></s:Envelope>') % (action, service, inner, action)
    request = urllib.request.Request(url, data=body.encode(), method="POST", headers={
        "Content-Type": 'text/xml; charset="utf-8"',
        "SOAPAction": '"%s#%s"' % (service, action)})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return 200, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # timeouts, refused connections
        return -1, repr(error)


def fields(xml):
    out = {}
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for element in root.iter():
        name = local_name(element)
        if (name.startswith("New") or name in ("errorCode", "errorDescription")) and element.text is not None:
            out[name] = element.text
    return out


def classify(ip):
    try:
        address = ipaddress.ip_address(ip)
    except ValueError:
        return "not an IP address"
    if address in ipaddress.ip_network("100.64.0.0/10"):
        return "CGNAT range: the provider shares this address, forwarding cannot reach the phone"
    if address.is_private:
        return "private range: another NAT in front of this router"
    if address.is_unspecified:
        return "unspecified: WAN down or hidden"
    return "public"


def probe_service(control, service, mapped):
    code, xml = soap(control, service, "GetStatusInfo")
    print("  status:", code, fields(xml))
    code, xml = soap(control, service, "GetExternalIPAddress")
    ip = fields(xml).get("NewExternalIPAddress", "")
    print("  external IP:", ip or (code, fields(xml)), "->", classify(ip) if ip else "")
    print("  port mappings:")
    for index in range(256):
        code, xml = soap(control, service, "GetGenericPortMappingEntry",
                         "<NewPortMappingIndex>%d</NewPortMappingIndex>" % index)
        if code != 200:
            print("    (%d listed)" % index)
            break
        f = fields(xml)
        print("    %s %s -> %s:%s enabled=%s lease=%s %r" % (
            f.get("NewProtocol"), f.get("NewExternalPort"), f.get("NewInternalClient"),
            f.get("NewInternalPort"), f.get("NewEnabled"), f.get("NewLeaseDuration"),
            f.get("NewPortMappingDescription")))
    code, xml = soap(control, service, "GetSpecificPortMappingEntry",
                     "<NewRemoteHost></NewRemoteHost><NewExternalPort>%d</NewExternalPort>"
                     "<NewProtocol>UDP</NewProtocol>" % PZ_PORT)
    f = fields(xml)
    if code == 200 and f.get("NewInternalClient"):
        mapped.append("%s:%s" % (f["NewInternalClient"], f.get("NewInternalPort")))
    print("  UDP %d lookup:" % PZ_PORT, code, f)


def main():
    local_ip = local_ip_towards(GATEWAY)
    print("LAN address %s, gateway %s" % (local_ip, GATEWAY))
    found = discover(local_ip)
    print("SSDP responders: %d" % len(found))
    for location, info in found.items():
        print("  %s from %s server=%r st=%s" % (location, info["from"], info["server"], sorted(info["st"])))
    services = 0
    mapped = []
    seen = set()
    for location in found:
        try:
            with urllib.request.urlopen(location, timeout=5) as response:
                root = ET.fromstring(response.read())
        except Exception as error:
            print("  cannot read %s: %r" % (location, error))
            continue
        base = location
        for element in root.iter():
            if local_name(element) == "URLBase" and element.text:
                base = element.text.strip()
        names = [e.text for e in root.iter()
                 if local_name(e) in ("manufacturer", "modelName", "friendlyName") and e.text]
        print("device %s: %s" % (location, " | ".join(names[:4])))
        for element in root.iter():
            if local_name(element) != "service":
                continue
            info = {local_name(child): (child.text or "").strip() for child in element}
            service = info.get("serviceType", "")
            if "WANIPConnection" not in service and "WANPPPConnection" not in service:
                continue
            control = urllib.parse.urljoin(base, info.get("controlURL", ""))
            if control in seen:
                continue
            seen.add(control)
            services += 1
            print("\n%s at %s" % (service, control))
            probe_service(control, service, mapped)
    if services == 0:
        print("\nVERDICT: no UPnP port-mapping service (IGD) answered. The game's UPnP cannot open a "
              "port on this network: turn UPnP on in the router, or forward UDP %d to the host "
              "phone by hand." % PZ_PORT)
    elif mapped:
        print("\nVERDICT: UDP %d is mapped to %s." % (PZ_PORT, ", ".join(mapped)))
    else:
        print("\nVERDICT: the router offers UPnP, but nothing maps UDP %d right now. Is a server "
              "hosting, with UPnP=true?" % PZ_PORT)


if __name__ == "__main__":
    main()
