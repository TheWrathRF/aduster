//! Just enough DNS wire format: parse a query, synthesize blocked/error answers, and inspect
//! upstream responses (TTLs for caching, CNAME chain for cloaking detection).

pub const TYPE_A: u16 = 1;
pub const TYPE_CNAME: u16 = 5;
pub const TYPE_SOA: u16 = 6;
pub const TYPE_AAAA: u16 = 28;
pub const TYPE_OPT: u16 = 41;
pub const CLASS_IN: u16 = 1;

pub const RCODE_NOERROR: u8 = 0;
pub const RCODE_SERVFAIL: u8 = 2;
pub const RCODE_NXDOMAIN: u8 = 3;
pub const RCODE_REFUSED: u8 = 5;

const MAX_NAME: usize = 255;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize, serde::Serialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum BlockMode {
    /// 0.0.0.0 / :: for A/AAAA, empty NOERROR for other types. Apps fail instantly, no retries.
    #[default]
    NullIp,
    NxDomain,
    Refused,
}

#[derive(Debug, Clone)]
pub struct Query {
    pub id: u16,
    pub flags: u16,
    /// Lowercase, dot-separated, no trailing dot ("" for the root).
    pub name: String,
    pub qtype: u16,
    pub qclass: u16,
    /// End offset of the question section.
    pub question_end: usize,
    pub edns: bool,
    pub do_bit: bool,
    /// The largest UDP answer the client takes: its EDNS size, or 512 without EDNS.
    pub udp_size: u16,
}

pub fn parse_query(b: &[u8]) -> Option<Query> {
    if b.len() < 12 {
        return None;
    }
    let id = u16::from_be_bytes([b[0], b[1]]);
    let flags = u16::from_be_bytes([b[2], b[3]]);
    let qd = u16::from_be_bytes([b[4], b[5]]);
    let an = u16::from_be_bytes([b[6], b[7]]);
    let ns = u16::from_be_bytes([b[8], b[9]]);
    let ar = u16::from_be_bytes([b[10], b[11]]);
    if flags & 0x8000 != 0 || (flags >> 11) & 0xF != 0 || qd != 1 {
        return None;
    }
    let (name, mut off) = read_plain_name(b, 12)?;
    if off + 4 > b.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([b[off], b[off + 1]]);
    let qclass = u16::from_be_bytes([b[off + 2], b[off + 3]]);
    off += 4;
    let question_end = off;

    let (mut edns, mut do_bit, mut udp_size) = (false, false, 512u16);
    if ar > 0 {
        for _ in 0..(an as usize + ns as usize) {
            off = skip_rr(b, off)?;
        }
        for _ in 0..ar {
            let name_end = skip_name(b, off)?;
            if name_end + 10 > b.len() {
                break;
            }
            let t = u16::from_be_bytes([b[name_end], b[name_end + 1]]);
            if t == TYPE_OPT {
                edns = true;
                do_bit = b[name_end + 6] & 0x80 != 0;
                // The OPT record's class is the requester's UDP payload size (RFC 6891).
                udp_size = u16::from_be_bytes([b[name_end + 2], b[name_end + 3]]).max(512);
            }
            off = skip_rr(b, off)?;
        }
    }
    Some(Query { id, flags, name, qtype, qclass, question_end, edns, do_bit, udp_size })
}

/// Question names never use compression; decode labels to a lowercase dotted string.
fn read_plain_name(b: &[u8], mut off: usize) -> Option<(String, usize)> {
    let mut name = String::with_capacity(32);
    loop {
        let len = *b.get(off)? as usize;
        off += 1;
        if len == 0 {
            break;
        }
        if len > 63 || off + len > b.len() || name.len() + len + 1 > MAX_NAME {
            return None;
        }
        if !name.is_empty() {
            name.push('.');
        }
        push_label(&mut name, &b[off..off + len]);
        off += len;
    }
    Some((name, off))
}

fn push_label(name: &mut String, label: &[u8]) {
    for &c in label {
        let c = c.to_ascii_lowercase();
        // Keep the matcher's alphabet; anything exotic is escaped so it can never match a rule by accident.
        if c.is_ascii_alphanumeric() || c == b'-' || c == b'_' {
            name.push(c as char);
        } else {
            name.push_str(&format!("\\{c:03}"));
        }
    }
}

/// Reads a possibly compressed name (used for CNAME targets in responses).
pub fn read_name(b: &[u8], mut off: usize) -> Option<(String, usize)> {
    let mut name = String::with_capacity(32);
    let mut end = None;
    let mut jumps = 0;
    loop {
        let len = *b.get(off)? as usize;
        if len & 0xC0 == 0xC0 {
            let ptr = ((len & 0x3F) << 8) | *b.get(off + 1)? as usize;
            if end.is_none() {
                end = Some(off + 2);
            }
            jumps += 1;
            if jumps > 32 || ptr >= b.len() {
                return None;
            }
            off = ptr;
            continue;
        }
        off += 1;
        if len == 0 {
            break;
        }
        if len > 63 || off + len > b.len() || name.len() + len + 1 > MAX_NAME {
            return None;
        }
        if !name.is_empty() {
            name.push('.');
        }
        push_label(&mut name, &b[off..off + len]);
        off += len;
    }
    Some((name, end.unwrap_or(off)))
}

fn skip_name(b: &[u8], mut off: usize) -> Option<usize> {
    loop {
        let len = *b.get(off)? as usize;
        if len & 0xC0 == 0xC0 {
            return (off + 2 <= b.len()).then_some(off + 2);
        }
        off += 1 + len;
        if len == 0 {
            return (off <= b.len()).then_some(off);
        }
    }
}

fn skip_rr(b: &[u8], off: usize) -> Option<usize> {
    let o = skip_name(b, off)?;
    if o + 10 > b.len() {
        return None;
    }
    let rdlen = u16::from_be_bytes([b[o + 8], b[o + 9]]) as usize;
    let end = o + 10 + rdlen;
    (end <= b.len()).then_some(end)
}

fn header(out: &mut Vec<u8>, q: &Query, rcode: u8, an: u16, ns: u16, ar: u16) {
    let rd = q.flags & 0x0100;
    let flags: u16 = 0x8000 | rd | 0x0080 | rcode as u16;
    out.extend_from_slice(&q.id.to_be_bytes());
    out.extend_from_slice(&flags.to_be_bytes());
    out.extend_from_slice(&1u16.to_be_bytes());
    out.extend_from_slice(&an.to_be_bytes());
    out.extend_from_slice(&ns.to_be_bytes());
    out.extend_from_slice(&ar.to_be_bytes());
}

fn opt_rr(out: &mut Vec<u8>) {
    out.extend_from_slice(&[0, 0, 41, 0x04, 0xD0, 0, 0, 0, 0, 0, 0]); // root, OPT, udp 1232, no flags, rdlen 0
}

/// Synthetic SOA so resolvers can negatively cache blocked names (RFC 2308) instead of re-asking.
fn soa_rr(out: &mut Vec<u8>, ttl: u32) {
    out.extend_from_slice(&[0xC0, 0x0C]);
    out.extend_from_slice(&TYPE_SOA.to_be_bytes());
    out.extend_from_slice(&CLASS_IN.to_be_bytes());
    out.extend_from_slice(&ttl.to_be_bytes());
    let mut rdata = Vec::with_capacity(48);
    rdata.extend_from_slice(b"\x07aduster\x00");
    rdata.extend_from_slice(b"\x05block\x07aduster\x00");
    for v in [1u32, 1800, 900, 604800, ttl] {
        rdata.extend_from_slice(&v.to_be_bytes());
    }
    out.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
    out.extend_from_slice(&rdata);
}

pub fn blocked_response(raw: &[u8], q: &Query, mode: BlockMode, ttl: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(q.question_end + 64);
    let ar = q.edns as u16;
    let question = &raw[12..q.question_end];
    match mode {
        BlockMode::NullIp if q.qclass == CLASS_IN && (q.qtype == TYPE_A || q.qtype == TYPE_AAAA) => {
            header(&mut out, q, RCODE_NOERROR, 1, 0, ar);
            out.extend_from_slice(question);
            out.extend_from_slice(&[0xC0, 0x0C]);
            out.extend_from_slice(&q.qtype.to_be_bytes());
            out.extend_from_slice(&CLASS_IN.to_be_bytes());
            out.extend_from_slice(&ttl.to_be_bytes());
            if q.qtype == TYPE_A {
                out.extend_from_slice(&[0, 4, 0, 0, 0, 0]);
            } else {
                out.extend_from_slice(&[0, 16]);
                out.extend_from_slice(&[0u8; 16]);
            }
        }
        BlockMode::NullIp | BlockMode::NxDomain => {
            let rcode = if mode == BlockMode::NxDomain { RCODE_NXDOMAIN } else { RCODE_NOERROR };
            header(&mut out, q, rcode, 0, 1, ar);
            out.extend_from_slice(question);
            soa_rr(&mut out, ttl);
        }
        BlockMode::Refused => {
            header(&mut out, q, RCODE_REFUSED, 0, 0, ar);
            out.extend_from_slice(question);
        }
    }
    if q.edns {
        opt_rr(&mut out);
    }
    out
}

pub fn error_response(raw: &[u8], q: &Query, rcode: u8) -> Vec<u8> {
    let mut out = Vec::with_capacity(q.question_end + 11);
    header(&mut out, q, rcode, 0, 0, q.edns as u16);
    out.extend_from_slice(&raw[12..q.question_end]);
    if q.edns {
        opt_rr(&mut out);
    }
    out
}

/// An empty answer with the TC bit: "too big for UDP", so the client stops
/// waiting at once instead of timing out on an answer that never comes.
pub fn truncated_response(raw: &[u8], q: &Query) -> Vec<u8> {
    let mut r = error_response(raw, q, RCODE_NOERROR);
    r[2] |= 0x02;
    r
}

/// The rcode of a response (0 if too short).
pub fn rcode_of(b: &[u8]) -> u8 {
    if b.len() >= 4 { b[3] & 0x0F } else { 0 }
}

/// A/AAAA question for the proxy's own lookups.
pub fn build_query(id: u16, name: &str, qtype: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(name.len() + 18 + 11);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1]);
    for label in name.split('.').filter(|l| !l.is_empty()) {
        let l = &label.as_bytes()[..label.len().min(63)];
        out.push(l.len() as u8);
        out.extend_from_slice(l);
    }
    out.push(0);
    out.extend_from_slice(&qtype.to_be_bytes());
    out.extend_from_slice(&CLASS_IN.to_be_bytes());
    opt_rr(&mut out);
    out
}

#[derive(Debug, Default)]
pub struct ResponseInfo {
    pub rcode: u8,
    pub truncated: bool,
    /// TTL to cache this response for: min answer TTL, or the negative TTL from the SOA.
    pub ttl: Option<u32>,
    /// Byte offsets of every TTL field (OPT excluded) so cached copies can be aged.
    pub ttl_offsets: Vec<u16>,
    pub cnames: Vec<String>,
    pub addrs: Vec<std::net::IpAddr>,
    /// Records in the answer section (an HTTPS/SVCB answer has some, but no addresses).
    pub answers: u16,
}

pub fn inspect(b: &[u8]) -> Option<ResponseInfo> {
    if b.len() < 12 || b[2] & 0x80 == 0 {
        return None;
    }
    let mut info = ResponseInfo { rcode: b[3] & 0x0F, truncated: b[2] & 0x02 != 0, ..Default::default() };
    let qd = u16::from_be_bytes([b[4], b[5]]) as usize;
    let an = u16::from_be_bytes([b[6], b[7]]) as usize;
    info.answers = an as u16;
    let ns = u16::from_be_bytes([b[8], b[9]]) as usize;
    let ar = u16::from_be_bytes([b[10], b[11]]) as usize;
    let mut off = 12;
    for _ in 0..qd {
        off = skip_name(b, off)? + 4;
    }
    let mut min_answer_ttl: Option<u32> = None;
    let mut negative_ttl: Option<u32> = None;
    for i in 0..(an + ns + ar) {
        let o = skip_name(b, off)?;
        if o + 10 > b.len() {
            return None;
        }
        let t = u16::from_be_bytes([b[o], b[o + 1]]);
        let ttl = u32::from_be_bytes([b[o + 4], b[o + 5], b[o + 6], b[o + 7]]);
        let rdlen = u16::from_be_bytes([b[o + 8], b[o + 9]]) as usize;
        let rd = o + 10;
        if rd + rdlen > b.len() {
            return None;
        }
        if t != TYPE_OPT {
            info.ttl_offsets.push((o + 4) as u16);
        }
        if i < an {
            min_answer_ttl = Some(min_answer_ttl.map_or(ttl, |m| m.min(ttl)));
            match t {
                TYPE_CNAME => {
                    if let Some((n, _)) = read_name(b, rd) {
                        info.cnames.push(n);
                    }
                }
                TYPE_A if rdlen == 4 => {
                    info.addrs.push(std::net::IpAddr::from(<[u8; 4]>::try_from(&b[rd..rd + 4]).unwrap()));
                }
                TYPE_AAAA if rdlen == 16 => {
                    info.addrs.push(std::net::IpAddr::from(<[u8; 16]>::try_from(&b[rd..rd + 16]).unwrap()));
                }
                _ => {}
            }
        } else if i < an + ns && t == TYPE_SOA && rdlen >= 20 {
            let minimum = u32::from_be_bytes(b[rd + rdlen - 4..rd + rdlen].try_into().unwrap());
            negative_ttl = Some(ttl.min(minimum));
        }
        off = rd + rdlen;
    }
    info.ttl = min_answer_ttl.or(negative_ttl);
    Some(info)
}

pub fn set_id(b: &mut [u8], id: u16) {
    if b.len() >= 2 {
        b[0..2].copy_from_slice(&id.to_be_bytes());
    }
}

pub fn id_of(b: &[u8]) -> u16 {
    if b.len() >= 2 { u16::from_be_bytes([b[0], b[1]]) } else { 0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query(name: &str, qtype: u16) -> Vec<u8> {
        let mut q = build_query(0x1234, name, qtype);
        q[2] = 0x01; // RD
        q
    }

    #[test]
    fn parse_and_block() {
        let raw = query("Ads.Example.COM", TYPE_A);
        let q = parse_query(&raw).unwrap();
        assert_eq!((q.id, q.name.as_str(), q.qtype, q.edns), (0x1234, "ads.example.com", TYPE_A, true));

        let r = blocked_response(&raw, &q, BlockMode::NullIp, 60);
        let info = inspect(&r).unwrap();
        assert_eq!(info.rcode, RCODE_NOERROR);
        assert_eq!(info.addrs, vec![std::net::IpAddr::from([0u8, 0, 0, 0])]);
        assert_eq!(id_of(&r), 0x1234);

        let raw6 = query("ads.example.com", 65);
        let q6 = parse_query(&raw6).unwrap();
        let r6 = blocked_response(&raw6, &q6, BlockMode::NullIp, 60);
        let info6 = inspect(&r6).unwrap();
        assert_eq!((info6.rcode, info6.addrs.len(), info6.ttl), (RCODE_NOERROR, 0, Some(60)));

        let nx = blocked_response(&raw, &q, BlockMode::NxDomain, 60);
        assert_eq!(inspect(&nx).unwrap().rcode, RCODE_NXDOMAIN);
    }

    #[test]
    fn rejects_responses_and_garbage() {
        let mut raw = query("a.com", TYPE_A);
        raw[2] |= 0x80;
        assert!(parse_query(&raw).is_none());
        assert!(parse_query(&[0u8; 5]).is_none());
        assert!(parse_query(&[0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 63]).is_none());
    }

    #[test]
    fn inspect_cname_chain() {
        // Response: tracker.shop.com CNAME shop.tracker-cdn.net, A 1.2.3.4
        let raw = query("tracker.shop.com", TYPE_A);
        let q = parse_query(&raw).unwrap();
        let mut r = Vec::new();
        header(&mut r, &q, 0, 2, 0, 0);
        r.extend_from_slice(&raw[12..q.question_end]);
        r.extend_from_slice(&[0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 1, 44]);
        let target = b"\x04shop\x0btracker-cdn\x03net\x00";
        r.extend_from_slice(&(target.len() as u16).to_be_bytes());
        let tpos = r.len();
        r.extend_from_slice(target);
        r.extend_from_slice(&[0xC0 | (tpos >> 8) as u8, tpos as u8, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 1, 2, 3, 4]);
        let info = inspect(&r).unwrap();
        assert_eq!(info.cnames, vec!["shop.tracker-cdn.net".to_string()]);
        assert_eq!(info.ttl, Some(30));
        assert_eq!(info.ttl_offsets.len(), 2);
    }
}
