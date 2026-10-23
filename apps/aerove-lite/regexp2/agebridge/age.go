// SPDX-License-Identifier: GPL-3.0-only
// The cryptographic engine is the exact metacubex/age revision used by Standard.
package agebridge

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"time"

	"github.com/metacubex/age"
	"github.com/metacubex/age/armor"
)

type Limits struct {
	MaxCiphertextBytes int64
	MaxPlaintextBytes  int64
	MaxIdentityBytes   int64
	Timeout            time.Duration
}

const (
	maxCiphertext = 8 << 20
	maxPlaintext  = 4 << 20
	maxIdentity   = 64 << 10
	maxDuration   = 10 * time.Second
)

var DefaultLimits = Limits{maxCiphertext, maxPlaintext, maxIdentity, maxDuration}

type failure string

func (f failure) Error() string { return string(f) }

// Code exposes only fixed error codes; underlying parser/key/payload errors are never logged or returned.
func Code(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.Canceled) {
		return "cancelled"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	var coded failure
	if errors.As(err, &coded) {
		return string(coded)
	}
	return "decrypt_failed"
}

type checkedReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r checkedReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := r.reader.Read(p)
	if cancelled := r.ctx.Err(); cancelled != nil {
		return 0, cancelled
	}
	return n, err
}

// Decrypt authenticates the complete bounded message before releasing any plaintext.
// The isolated caller additionally owns a hard process watchdog for crypto work inside a library call.
func Decrypt(parent context.Context, ciphertext io.Reader, identities []byte, plaintext io.Writer, limits Limits) error {
	if limits.MaxCiphertextBytes <= 0 || limits.MaxCiphertextBytes > maxCiphertext ||
		limits.MaxPlaintextBytes <= 0 || limits.MaxPlaintextBytes > maxPlaintext ||
		limits.MaxIdentityBytes <= 0 || limits.MaxIdentityBytes > maxIdentity ||
		limits.Timeout <= 0 || limits.Timeout > maxDuration {
		return failure("invalid_limits")
	}
	ctx, cancel := context.WithTimeout(parent, limits.Timeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return err
	}
	if len(identities) == 0 {
		return failure("secret_required")
	}
	if int64(len(identities)) > limits.MaxIdentityBytes {
		return failure("identity_size")
	}
	key := bytes.Clone(identities)
	defer clear(key)
	parsed, err := age.ParseIdentities(bytes.NewReader(key))
	if err != nil {
		return failure("invalid_identity")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	input, err := io.ReadAll(io.LimitReader(checkedReader{ctx, ciphertext}, limits.MaxCiphertextBytes+1))
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return failure("input_error")
	}
	if int64(len(input)) > limits.MaxCiphertextBytes {
		return failure("ciphertext_size")
	}
	var reader io.Reader = bytes.NewReader(input)
	if bytes.HasPrefix(input, []byte(armor.Header)) {
		reader = armor.NewReader(reader)
	} else if !bytes.HasPrefix(input, []byte("age-encryption.org/v1\n")) {
		return failure("invalid_ciphertext")
	}
	decrypted, err := age.Decrypt(bufio.NewReader(checkedReader{ctx, reader}), parsed...)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		var noMatch *age.NoIdentityMatchError
		if errors.As(err, &noMatch) {
			return failure("wrong_key")
		}
		return failure("decrypt_failed")
	}
	output, err := io.ReadAll(io.LimitReader(checkedReader{ctx, decrypted}, limits.MaxPlaintextBytes+1))
	defer clear(output)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return failure("decrypt_failed")
	}
	if int64(len(output)) > limits.MaxPlaintextBytes {
		return failure("plaintext_size")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	// At this point EOF has authenticated the complete stream, including the final chunk.
	for len(output) > 0 {
		if err := ctx.Err(); err != nil {
			return err
		}
		n, err := plaintext.Write(output)
		if err != nil {
			return failure("output_error")
		}
		if n <= 0 || n > len(output) {
			return failure("output_error")
		}
		output = output[n:]
	}
	return ctx.Err()
}
