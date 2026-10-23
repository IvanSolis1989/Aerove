// SPDX-License-Identifier: GPL-3.0-only
// Portable source-pipeline verification of the same pinned engine; not an Android runtime alternative.
package main

import (
	"bytes"
	"context"
	"dev.aerove/filter/agebridge"
	"encoding/binary"
	"io"
	"os"
	"time"
)

func main() {
	result := []byte("\x01invalid_request")
	defer func() {
		_ = binary.Write(os.Stdout, binary.LittleEndian, uint32(len(result)))
		_, _ = os.Stdout.Write(result)
		clear(result)
	}()
	var keyLength, cipherLength uint32
	if binary.Read(os.Stdin, binary.LittleEndian, &keyLength) != nil || keyLength > 64<<10 {
		return
	}
	keys := make([]byte, keyLength)
	defer clear(keys)
	if _, err := io.ReadFull(os.Stdin, keys); err != nil {
		return
	}
	if binary.Read(os.Stdin, binary.LittleEndian, &cipherLength) != nil || cipherLength > 8<<20 {
		return
	}
	cipher := make([]byte, cipherLength)
	defer clear(cipher)
	if _, err := io.ReadFull(os.Stdin, cipher); err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var plain bytes.Buffer
	err := agebridge.Decrypt(ctx, bytes.NewReader(cipher), keys, &plain, agebridge.DefaultLimits)
	if err != nil {
		result = append([]byte{1}, []byte(agebridge.Code(err))...)
	} else {
		result = append([]byte{0}, plain.Bytes()...)
	}
	clear(plain.Bytes())
}
