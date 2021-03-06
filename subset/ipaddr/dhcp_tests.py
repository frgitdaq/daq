from __future__ import absolute_import
import sys
import json
from scapy.all import rdpcap, DHCP

TEST_REQUEST = str(sys.argv[1])
DHCP_REQUEST = 3
DHCP_ACKNOWLEDGE = 5

def main():
    """main"""
    scan_file = '/scans/test_ipaddr.pcap'
    ipaddr_log = '/tmp/activate.log'
    module_config_file = '/config/device/module_config.json'
    dhcp_ranges = []
    report_filename = 'report.txt'
    dash_break_line = '--------------------\n'
    description_dhcp_short = 'Reconnect device and check for DHCP request.'
    description_private_address = 'Device supports all private address ranges.'
    running_port_toggle = 'Running dhcp port_toggle test'
    ip_notification = 'ip notification'
    result = None
    summary = None

    with open(module_config_file) as json_file:
        json_data = json.load(json_file)
        dhcp_ranges = json_data['modules']['ipaddr']['dhcp_ranges']

    def _write_report(string_to_append):
        with open(report_filename, 'a+') as file_open:
            file_open.write(string_to_append)

    def _get_dhcp_type(capture, dhcp_type, after=None):
        for packet in capture:
            if DHCP not in packet:
                continue
            if packet[DHCP].options[0][1] == dhcp_type:
                if after is None:
                    return packet
                if packet.time > after:
                    return packet
        return None

    def _get_dhcp_option(packet, option):
        for opt in packet[DHCP].options:
            if opt[0] == option:
                return opt[1]
        return None

    def _to_ipv4(ip):
        return tuple(int(n) for n in ip.split('.'))

    def _in_range(ip, start, end):
        return _to_ipv4(start) < _to_ipv4(ip) < _to_ipv4(end)

    def _supports_range(capture, start, end):
        found_request = False
        for packet in capture:
            if DHCP not in packet:
                continue
            if not packet[DHCP].options[0][1] == DHCP_REQUEST:
                continue
            if _get_dhcp_option(packet, 'requested_addr') is None:
                continue
            if _in_range(_get_dhcp_option(packet, 'requested_addr'), start, end):
                found_request = True
        return found_request

    def _test_dhcp_short():
        fd = open(ipaddr_log, 'r')
        run_dhcp_short = False
        for line in fd:
            if run_dhcp_short:
                if ip_notification in line:
                    fd.close()
                    return 'pass', 'DHCP request received.'
            if running_port_toggle in line:
                run_dhcp_short = True
        fd.close()
        return 'fail', 'No DHCP request received.'

    def _test_private_address():
        if len(dhcp_ranges) == 0:
            return 'skip', 'No private address ranges were specified.'
        capture = rdpcap(scan_file)
        passing = True
        for dhcp_range in dhcp_ranges:
            if not _supports_range(capture, dhcp_range['start'], dhcp_range['end']):
                passing = False
        if passing:
            return 'pass', 'All private address ranges are supported.'
        return 'fail', 'Not all private address ranges are supported.'

    _write_report("{b}{t}\n{b}".format(b=dash_break_line, t=TEST_REQUEST))

    if TEST_REQUEST == 'connection.network.dhcp_short':
        result, summary = _test_dhcp_short()
        _write_report("{d}\n{b}".format(b=dash_break_line, d=description_dhcp_short))
    elif TEST_REQUEST == 'connection.dhcp.private_address':
        result, summary = _test_private_address()
        _write_report("{d}\n{b}".format(b=dash_break_line, d=description_private_address))

    _write_report("RESULT {r} {t} {s}\n".format(r=result, t=TEST_REQUEST, s=summary))


if __name__ == "__main__":
    main()
