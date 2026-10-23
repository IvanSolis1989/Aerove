// SPDX-License-Identifier: GPL-3.0-only
package agebridge

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/metacubex/age"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestFinalChunkAuthenticationNeverReleasesEarlierPlaintext(t *testing.T) {
	identity, err := age.GenerateX25519Identity()
	if err != nil {
		t.Fatal("identity generation failed")
	}
	var cipher bytes.Buffer
	writer, err := age.Encrypt(&cipher, identity.Recipient())
	if err != nil {
		t.Fatal("encryption failed")
	}
	if _, err = writer.Write(bytes.Repeat([]byte("synthetic-profile\n"), 8192)); err != nil {
		t.Fatal("write failed")
	}
	if err = writer.Close(); err != nil {
		t.Fatal("close failed")
	}
	tampered := bytes.Clone(cipher.Bytes())
	tampered[len(tampered)-1] ^= 1
	var output bytes.Buffer
	err = Decrypt(context.Background(), bytes.NewReader(tampered), []byte(identity.String()), &output, DefaultLimits)
	if Code(err) != "decrypt_failed" || output.Len() != 0 {
		t.Fatal("unverified final chunk released plaintext")
	}
}

func TestStandardGoInteropBothIdentityTypes(t *testing.T) {
	for _, name := range []string{"x25519", "hybrid"} {
		t.Run(name, func(t *testing.T) {
			var output bytes.Buffer
			if err := Decrypt(context.Background(), bytes.NewReader(fixture(t, name+".age")), fixture(t, name+".identity"), &output, DefaultLimits); err != nil {
				t.Fatal(Code(err))
			}
			if !bytes.Equal(output.Bytes(), fixture(t, "profile.yaml")) {
				t.Fatal("Standard plaintext differs")
			}
		})
	}
}

func TestWrongKeyTamperingAndLimitsNeverReleasePlaintext(t *testing.T) {
	encrypted := fixture(t, "x25519.age")
	key := fixture(t, "x25519.identity")
	tampered := bytes.Clone(encrypted)
	tampered[len(tampered)/2] ^= 1
	tests := []struct {
		name             string
		cipher, identity []byte
		limits           Limits
		code             string
	}{
		{"wrong key", encrypted, fixture(t, "wrong.identity"), DefaultLimits, "wrong_key"},
		{"missing", encrypted, nil, DefaultLimits, "secret_required"},
		{"invalid", encrypted, []byte("secret-do-not-log"), DefaultLimits, "invalid_identity"},
		{"tampered", tampered, key, DefaultLimits, "decrypt_failed"},
		{"cipher bound", encrypted, key, Limits{32, 4 << 20, 64 << 10, time.Second}, "ciphertext_size"},
		{"plain bound", encrypted, key, Limits{8 << 20, 4, 64 << 10, time.Second}, "plaintext_size"},
		{"identity bound", encrypted, key, Limits{8 << 20, 4 << 20, 4, time.Second}, "identity_size"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var output bytes.Buffer
			err := Decrypt(context.Background(), bytes.NewReader(test.cipher), test.identity, &output, test.limits)
			if Code(err) != test.code || output.Len() != 0 {
				t.Fatalf("code=%s, released=%d", Code(err), output.Len())
			}
		})
	}
}

type cancelReader struct {
	cancel context.CancelFunc
	data   *bytes.Reader
}

func (r cancelReader) Read(p []byte) (int, error) {
	n, err := r.data.Read(p)
	r.cancel()
	return n, err
}

func TestCancellationDuringReadAndExpiredDeadline(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var output bytes.Buffer
	err := Decrypt(ctx, cancelReader{cancel, bytes.NewReader(fixture(t, "x25519.age"))}, fixture(t, "x25519.identity"), &output, DefaultLimits)
	if Code(err) != "cancelled" || output.Len() != 0 {
		t.Fatal("cancelled read released plaintext")
	}
	ctx, cancel = context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	if Code(Decrypt(ctx, bytes.NewReader(nil), nil, &output, DefaultLimits)) != "timeout" {
		t.Fatal("deadline was ignored")
	}
}
