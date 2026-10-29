//! Minimal IPv4/IPv6 + UDP/TCP handling for the DNS-only tunnel.
//!
//! The tunnel only carries packets addressed to the fake resolver and to well-known public
//! resolvers, so the only things we ever need are: read a UDP datagram, write a UDP reply,
//! and reset TCP connections (DoT/DoH bypass attempts) so apps fail over immediately.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

const PROTO_TCP: u8 = 6;
const PROTO_UDP: u8 = 17;
const PROTO_ICMP: u8 = 1;
const PROTO_ICMPV6: u8 = 58;

#[derive(Debug)]
pub struct Udp<'a> {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub payload: &'a [u8],
}

#[derive(Debug)]
pub struct TcpSyn {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub seq: u32,
}

#[derive(Debug)]
pub enum Packet<'a> {
    Udp(Udp<'a>),
    TcpSyn(TcpSyn),
    Other,
}

pub fn parse(p: &[u8]) -> Packet<'_> {
    if p.is_empty() {
        return Packet::Other;
    }
    let (src, dst, proto, l4) = match p[0] >> 4 {
        4 => {
            if p.len() < 20 {
                return Packet::Other;
            }
            let ihl = ((p[0] & 0x0F) as usize) * 4;
            let total = u16::from_be_bytes([p[2], p[3]]) as usize;
            // Fragments never carry a whole DNS query we could answer.
            let frag = u16::from_be_bytes([p[6], p[7]]);
            if ihl < 20 || total > p.len() || total < ihl || (frag & 0x3FFF) != 0 {
                return Packet::Other;
            }
            let src = IpAddr::V4(Ipv4Addr::new(p[12], p[13], p[14], p[15]));
            let dst = IpAddr::V4(Ipv4Addr::new(p[16], p[17], p[18], p[19]));
            (src, dst, p[9], &p[ihl..total])
        }
        6 => {
            if p.len() < 40 {
                return Packet::Other;
            }
            let plen = u16::from_be_bytes([p[4], p[5]]) as usize;
            if 40 + plen > p.len() {
                return Packet::Other;
            }
            let src = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&p[8..24]).unwrap()));
            let dst = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&p[24..40]).unwrap()));
            // Extension headers (hop-by-hop MLD etc.) are not DNS traffic.
            (src, dst, p[6], &p[40..40 + plen])
        }
        _ => return Packet::Other,
    };
    match proto {
        PROTO_UDP if l4.len() >= 8 => {
            let sport = u16::from_be_bytes([l4[0], l4[1]]);
            let dport = u16::from_be_bytes([l4[2], l4[3]]);
            let ulen = u16::from_be_bytes([l4[4], l4[5]]) as usize;
            if ulen < 8 || ulen > l4.len() {
                return Packet::Other;
            }
            Packet::Udp(Udp { src: SocketAddr::new(src, sport), dst: SocketAddr::new(dst, dport), payload: &l4[8..ulen] })
        }
        PROTO_TCP if l4.len() >= 20 => {
            let flags = l4[13];
            let syn = flags & 0x02 != 0;
            let ack = flags & 0x10 != 0;
            let rst = flags & 0x04 != 0;
            if !syn || ack || rst {
                return Packet::Other;
            }
            let sport = u16::from_be_bytes([l4[0], l4[1]]);
            let dport = u16::from_be_bytes([l4[2], l4[3]]);
            let seq = u32::from_be_bytes([l4[4], l4[5], l4[6], l4[7]]);
            Packet::TcpSyn(TcpSyn { src: SocketAddr::new(src, sport), dst: SocketAddr::new(dst, dport), seq })
        }
        _ => Packet::Other,
    }
}

/// Builds a UDP datagram `from` -> `to` carrying `payload` into `out` (cleared first).
pub fn build_udp(from: SocketAddr, to: SocketAddr, payload: &[u8], out: &mut Vec<u8>) {
    out.clear();
    let udp_len = 8 + payload.len();
    let ip_hdr = write_ip_header(from.ip(), to.ip(), PROTO_UDP, udp_len, out);
    let l4 = out.len();
    out.extend_from_slice(&from.port().to_be_bytes());
    out.extend_from_slice(&to.port().to_be_bytes());
    out.extend_from_slice(&(udp_len as u16).to_be_bytes());
    out.extend_from_slice(&[0, 0]);
    out.extend_from_slice(payload);
    let mut c = l4_checksum(from.ip(), to.ip(), PROTO_UDP, &out[l4..]);
    if c == 0 {
        c = 0xFFFF;
    }
    out[l4 + 6..l4 + 8].copy_from_slice(&c.to_be_bytes());
    debug_assert_eq!(ip_hdr, l4);
}

/// Builds a RST|ACK answering `syn`, so the client's connect() fails instantly.
pub fn build_tcp_rst(syn: &TcpSyn, out: &mut Vec<u8>) {
    out.clear();
    let (from, to) = (syn.dst, syn.src);
    write_ip_header(from.ip(), to.ip(), PROTO_TCP, 20, out);
    let l4 = out.len();
    out.extend_from_slice(&from.port().to_be_bytes());
    out.extend_from_slice(&to.port().to_be_bytes());
    out.extend_from_slice(&0u32.to_be_bytes());
    out.extend_from_slice(&syn.seq.wrapping_add(1).to_be_bytes());
    out.extend_from_slice(&[0x50, 0x14, 0, 0, 0, 0, 0, 0]); // data offset 5, RST|ACK, window 0
    let c = l4_checksum(from.ip(), to.ip(), PROTO_TCP, &out[l4..]);
    out[l4 + 16..l4 + 18].copy_from_slice(&c.to_be_bytes());
}

/// Builds an ICMP "port unreachable" for the UDP datagram `p` (a whole IP packet, parsed as
/// `u`), so the app's socket fails at once instead of waiting for its timeout: DNS-over-QUIC and
/// HTTP/3 DoH to the public resolvers routed here get nothing else from us. The error carries
/// the start of the offending packet, as RFC 792 / 4443 require (576 / 1280 bytes in all).
pub fn build_port_unreachable(p: &[u8], u: &Udp, out: &mut Vec<u8>) {
    out.clear();
    let (from, to) = (u.dst.ip(), u.src.ip());
    let v4 = from.is_ipv4();
    let quote = &p[..p.len().min(if v4 { 576 - 20 - 8 } else { 1280 - 40 - 8 })];
    let proto = if v4 { PROTO_ICMP } else { PROTO_ICMPV6 };
    write_ip_header(from, to, proto, 8 + quote.len(), out);
    let l4 = out.len();
    // Destination unreachable, port: type 3 code 3 (IPv4), type 1 code 4 (IPv6).
    out.extend_from_slice(if v4 { &[3, 3, 0, 0, 0, 0, 0, 0] } else { &[1, 4, 0, 0, 0, 0, 0, 0] });
    out.extend_from_slice(quote);
    // ICMPv4 sums the message alone; ICMPv6 adds the pseudo header like UDP.
    let c = if v4 { !fold(sum16(&out[l4..], 0)) } else { l4_checksum(from, to, PROTO_ICMPV6, &out[l4..]) };
    out[l4 + 2..l4 + 4].copy_from_slice(&c.to_be_bytes());
}

fn write_ip_header(src: IpAddr, dst: IpAddr, proto: u8, l4_len: usize, out: &mut Vec<u8>) -> usize {
    match (src, dst) {
        (IpAddr::V4(s), IpAddr::V4(d)) => {
            let total = (20 + l4_len) as u16;
            out.extend_from_slice(&[0x45, 0]);
            out.extend_from_slice(&total.to_be_bytes());
            out.extend_from_slice(&[0, 0, 0x40, 0, 64, proto, 0, 0]); // id 0, DF, ttl 64
            out.extend_from_slice(&s.octets());
            out.extend_from_slice(&d.octets());
            let c = fold(sum16(&out[out.len() - 20..], 0));
            let at = out.len() - 10;
            out[at..at + 2].copy_from_slice(&(!c).to_be_bytes());
        }
        (IpAddr::V6(s), IpAddr::V6(d)) => {
            out.extend_from_slice(&[0x60, 0, 0, 0]);
            out.extend_from_slice(&(l4_len as u16).to_be_bytes());
            out.extend_from_slice(&[proto, 64]);
            out.extend_from_slice(&s.octets());
            out.extend_from_slice(&d.octets());
        }
        _ => unreachable!("mixed address families"),
    }
    out.len()
}

fn l4_checksum(src: IpAddr, dst: IpAddr, proto: u8, l4: &[u8]) -> u16 {
    let mut s: u32 = 0;
    match (src, dst) {
        (IpAddr::V4(a), IpAddr::V4(b)) => {
            s = sum16(&a.octets(), s);
            s = sum16(&b.octets(), s);
        }
        (IpAddr::V6(a), IpAddr::V6(b)) => {
            s = sum16(&a.octets(), s);
            s = sum16(&b.octets(), s);
        }
        _ => {}
    }
    s += proto as u32 + l4.len() as u32;
    s = sum16(l4, s);
    !fold(s)
}

fn sum16(b: &[u8], mut s: u32) -> u32 {
    let (pairs, rest) = b.as_chunks::<2>();
    for c in pairs {
        s = s.wrapping_add(u16::from_be_bytes(*c) as u32);
    }
    if let [last] = rest {
        s = s.wrapping_add((*last as u32) << 8);
    }
    s
}

fn fold(mut s: u32) -> u16 {
    while s >> 16 != 0 {
        s = (s & 0xFFFF) + (s >> 16);
    }
    s as u16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn udp_roundtrip_v4_v6() {
        for (a, b) in [("10.111.222.2:40000", "10.111.222.1:53"), ("[fd00:ad::2]:40000", "[fd00:ad::53]:53")] {
            let (a, b): (SocketAddr, SocketAddr) = (a.parse().unwrap(), b.parse().unwrap());
            let mut out = Vec::new();
            build_udp(a, b, b"hello dns", &mut out);
            match parse(&out) {
                Packet::Udp(u) => {
                    assert_eq!((u.src, u.dst, u.payload), (a, b, &b"hello dns"[..]));
                    // A correct checksum makes the one's-complement sum over pseudo header + segment 0xFFFF.
                    let l4 = &out[if a.is_ipv4() { 20 } else { 40 }..];
                    assert_eq!(l4_checksum(a.ip(), b.ip(), PROTO_UDP, l4), 0);
                }
                other => panic!("{other:?}"),
            }
            if a.is_ipv4() {
                assert_eq!(fold(sum16(&out[..20], 0)), 0xFFFF);
            }
        }
    }

    /// UDP to a routed resolver on another port (DoQ, HTTP/3) is answered with a port
    /// unreachable that quotes the packet and has valid checksums.
    #[test]
    fn port_unreachable_for_other_udp() {
        for (a, b) in [("10.111.222.2:40000", "1.1.1.1:853"), ("[fd00:ad::2]:40000", "[2606:4700:4700::1111]:443")] {
            let (a, b): (SocketAddr, SocketAddr) = (a.parse().unwrap(), b.parse().unwrap());
            let mut q = Vec::new();
            build_udp(a, b, &[7u8; 40], &mut q);
            let Packet::Udp(u) = parse(&q) else { panic!() };
            let mut out = Vec::new();
            build_port_unreachable(&q, &u, &mut out);
            let hl = if a.is_ipv4() { 20 } else { 40 };
            let icmp = &out[hl..];
            assert_eq!(&icmp[8..], &q[..], "quotes the whole (small) packet");
            if a.is_ipv4() {
                assert_eq!((out[9], icmp[0], icmp[1]), (PROTO_ICMP, 3, 3));
                assert_eq!(fold(sum16(icmp, 0)), 0xFFFF);
                assert_eq!(fold(sum16(&out[..20], 0)), 0xFFFF);
                assert_eq!(&out[12..16], &[1, 1, 1, 1], "from the resolver");
            } else {
                assert_eq!((out[6], icmp[0], icmp[1]), (PROTO_ICMPV6, 1, 4));
                assert_eq!(l4_checksum(b.ip(), a.ip(), PROTO_ICMPV6, icmp), 0);
            }
        }
    }

    #[test]
    fn rst_answers_syn() {
        let syn = TcpSyn { src: "10.111.222.2:5555".parse().unwrap(), dst: "1.1.1.1:853".parse().unwrap(), seq: 41 };
        let mut out = Vec::new();
        build_tcp_rst(&syn, &mut out);
        assert_eq!(out.len(), 40);
        assert_eq!(&out[12..16], &[1, 1, 1, 1]);
        assert_eq!(u32::from_be_bytes(out[28..32].try_into().unwrap()), 42);
        assert_eq!(out[33], 0x14);
        assert_eq!(l4_checksum(syn.dst.ip(), syn.src.ip(), PROTO_TCP, &out[20..]), 0);
    }

    /// DoT to a routed public resolver's IPv6 address: the SYN is read from an IPv6 packet and
    /// the RST goes back from that address with a valid checksum.
    #[test]
    fn rst_answers_ipv6_syn() {
        let (src, dst): (SocketAddr, SocketAddr) = ("[fd00:ad::2]:5555".parse().unwrap(), "[2606:4700:4700::1111]:853".parse().unwrap());
        let mut syn = vec![0x60, 0, 0, 0, 0, 20, PROTO_TCP, 64];
        for ip in [src.ip(), dst.ip()] {
            let IpAddr::V6(a) = ip else { unreachable!() };
            syn.extend_from_slice(&a.octets());
        }
        syn.extend_from_slice(&5555u16.to_be_bytes());
        syn.extend_from_slice(&853u16.to_be_bytes());
        syn.extend_from_slice(&41u32.to_be_bytes());
        syn.extend_from_slice(&[0, 0, 0, 0, 0x50, 0x02, 0xFF, 0xFF, 0, 0, 0, 0]); // SYN
        let Packet::TcpSyn(s) = parse(&syn) else { panic!("an IPv6 SYN") };
        assert_eq!((s.src, s.dst, s.seq), (src, dst, 41));
        let mut out = Vec::new();
        build_tcp_rst(&s, &mut out);
        assert_eq!((out.len(), out[0] >> 4, out[6]), (60, 6, PROTO_TCP));
        assert_eq!(&out[8..24], &[0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11], "from the resolver");
        assert_eq!(u32::from_be_bytes(out[48..52].try_into().unwrap()), 42);
        assert_eq!(out[53], 0x14);
        assert_eq!(l4_checksum(dst.ip(), src.ip(), PROTO_TCP, &out[40..]), 0);
    }

    /// IPv6 UDP checksums (mandatory there, unlike IPv4) hold for odd lengths and for answers
    /// bigger than IPv6's 1,280-byte minimum MTU, which the 9,000-byte tunnel carries whole.
    #[test]
    fn ipv6_udp_checksums_at_every_size() {
        let (a, b): (SocketAddr, SocketAddr) = ("[fd00:ad::53]:53".parse().unwrap(), "[fd00:ad::2]:40000".parse().unwrap());
        for n in [0, 1, 511, 1232, 1233, 1452, 4001, 8951] {
            let payload: Vec<u8> = (0..n).map(|i| (i * 7 + 3) as u8).collect();
            let mut out = Vec::new();
            build_udp(a, b, &payload, &mut out);
            assert_eq!(out.len(), 48 + n);
            let Packet::Udp(u) = parse(&out) else { panic!("{n} bytes") };
            assert_eq!(u.payload, &payload[..]);
            assert_ne!(&out[46..48], &[0, 0], "{n} bytes: an IPv6 UDP checksum is never left out");
            assert_eq!(l4_checksum(a.ip(), b.ip(), PROTO_UDP, &out[40..]), 0, "{n} bytes");
        }
    }

    /// An ICMPv6 error quotes as much of the offending packet as fits in 1,280 bytes (RFC 4443
    /// 2.4 (c)), no more, so it is never too big for the path.
    #[test]
    fn icmpv6_error_fits_the_minimum_mtu() {
        let (a, b): (SocketAddr, SocketAddr) = ("[fd00:ad::2]:40000".parse().unwrap(), "[2606:4700:4700::1111]:443".parse().unwrap());
        let mut q = Vec::new();
        build_udp(a, b, &[9u8; 1600], &mut q);
        let Packet::Udp(u) = parse(&q) else { panic!() };
        let mut out = Vec::new();
        build_port_unreachable(&q, &u, &mut out);
        assert_eq!(out.len(), 1280);
        assert_eq!(u16::from_be_bytes([out[4], out[5]]) as usize, 1280 - 40, "payload length");
        assert_eq!(&out[48..], &q[..1280 - 48], "the start of the offending packet");
        assert_eq!(l4_checksum(b.ip(), a.ip(), PROTO_ICMPV6, &out[40..]), 0);
    }
}
