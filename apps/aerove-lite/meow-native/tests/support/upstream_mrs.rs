// SPDX-License-Identifier: MIT
/*
Copyright (c) 2026 Max Lv

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
// Reused from meow-rs 727ddcd06f592e2f12c5222c4de6cce328df808c, crates/meow-rules/src/mrs_parser.rs tests.
// Only module imports and the two entry-point visibilities are adapted; the Native crate remains GPL-3.0-only.
use std::io::Cursor;
use meow_config::offline_rules::mrs_parser::UPSTREAM_MRS_MAGIC;

fn set_bit(bits: &mut Vec<u64>, idx: usize, value: bool) {
    let word = idx / 64;
    if bits.len() <= word {
        bits.resize(word + 1, 0);
    }
    if value {
        bits[word] |= 1u64 << (idx % 64);
    }
}

fn write_i64(out: &mut Vec<u8>, value: i64) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn write_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_be_bytes());
}

pub(super) fn encode_domain_set(entries: &[&str]) -> Vec<u8> {
    let mut keys: Vec<Vec<u8>> = entries
        .iter()
        .map(|entry| entry.as_bytes().iter().rev().copied().collect())
        .collect();
    keys.sort();

    let mut leaves = Vec::new();
    let mut label_bitmap = Vec::new();
    let mut labels = Vec::new();
    let mut label_idx = 0usize;
    let mut queue = vec![(0usize, keys.len(), 0usize)];

    let mut idx = 0usize;
    while idx < queue.len() {
        let (mut start, end, col) = queue[idx];
        if col == keys[start].len() {
            start += 1;
            set_bit(&mut leaves, idx, true);
        }
        let mut j = start;
        while j < end {
            let from = j;
            while j < end && keys[j][col] == keys[from][col] {
                j += 1;
            }
            queue.push((from, j, col + 1));
            labels.push(keys[from][col]);
            set_bit(&mut label_bitmap, label_idx, false);
            label_idx += 1;
        }
        set_bit(&mut label_bitmap, label_idx, true);
        label_idx += 1;
        idx += 1;
    }

    encode_domain_set_raw(&leaves, &label_bitmap, &labels)
}

fn encode_domain_set_raw(leaves: &[u64], label_bitmap: &[u64], labels: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.push(1);
    write_i64(&mut out, leaves.len() as i64);
    for word in leaves {
        write_u64(&mut out, *word);
    }
    write_i64(&mut out, label_bitmap.len() as i64);
    for word in label_bitmap {
        write_u64(&mut out, *word);
    }
    write_i64(&mut out, labels.len() as i64);
    out.extend_from_slice(labels);
    out
}

pub(super) fn encode_upstream_mrs(behavior: u8, count: usize, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    inner.extend_from_slice(&UPSTREAM_MRS_MAGIC);
    inner.push(behavior);
    write_i64(&mut inner, count as i64);
    write_i64(&mut inner, 0);
    inner.extend_from_slice(body);
    zstd::encode_all(Cursor::new(inner), 0).unwrap()
}
