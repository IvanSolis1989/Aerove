//go:build cgo

// AEROVE exact regexp2 isolated-process bridge.
package main

/*
#include <stdlib.h>
#include <stdint.h>
*/
import "C"
import (
	"bytes"
	"context"
	"dev.aerove/filter/agebridge"
	"dev.aerove/filter/service"
	"encoding/json"
	"sync"
	"time"
	"unsafe"
)

var engine = service.New()
var ageRequests sync.Map

//export aerove_filter_abi_version
func aerove_filter_abi_version() C.uint32_t { return 2 }

//export aerove_filter_batch_utf8
func aerove_filter_batch_utf8(input unsafe.Pointer, length C.size_t, outLength *C.size_t) unsafe.Pointer {
	var response service.Response
	if uint64(length) > service.MaxBytes || input == nil || outLength == nil {
		response = service.Response{Status: "invalid-request", Engine: service.Version, Error: &service.Failure{Path: "$", Kind: "invalid-request", Message: "invalid pointer or length"}}
	} else {
		request, err := service.Decode(C.GoBytes(input, C.int(length)))
		if err != nil {
			response = service.Response{Status: "invalid-request", Engine: service.Version, Error: &service.Failure{Path: "$", Kind: "invalid-request", Message: err.Error()}}
		} else {
			response = engine.Batch(context.Background(), request)
		}
	}
	encoded, _ := json.Marshal(response)
	if outLength == nil {
		return nil
	}
	*outLength = C.size_t(len(encoded))
	return C.CBytes(encoded)
}

//export aerove_filter_cancel
func aerove_filter_cancel(id C.uint64_t) C.int32_t {
	if id == 0 {
		return -1
	}
	if cancel, ok := ageRequests.Load(uint64(id)); ok {
		cancel.(context.CancelFunc)()
		return 1
	}
	if engine.Cancel(uint64(id)) {
		return 1
	}
	return 0
}

//export aerove_helper_decrypt_age
func aerove_helper_decrypt_age(cipher unsafe.Pointer, cipherLength C.size_t, identities unsafe.Pointer, identityLength C.size_t, id C.uint64_t, outLength *C.size_t) unsafe.Pointer {
	if outLength == nil {
		return nil
	}
	result := []byte("\x01invalid_request")
	if cipher != nil && uint64(cipherLength) <= 8<<20 && identities != nil && uint64(identityLength) <= 64<<10 && id > 0 {
		keys := C.GoBytes(identities, C.int(identityLength))
		defer clear(keys)
		ciphertext := C.GoBytes(cipher, C.int(cipherLength))
		defer clear(ciphertext)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if _, loaded := ageRequests.LoadOrStore(uint64(id), context.CancelFunc(cancel)); loaded {
			result = []byte("\x01busy")
		} else {
			defer ageRequests.Delete(uint64(id))
			var plain bytes.Buffer
			err := agebridge.Decrypt(ctx, bytes.NewReader(ciphertext), keys, &plain, agebridge.DefaultLimits)
			if err != nil {
				result = append([]byte{1}, []byte(agebridge.Code(err))...)
			} else {
				result = append([]byte{0}, plain.Bytes()...)
			}
			clear(plain.Bytes())
		}
	}
	*outLength = C.size_t(len(result))
	output := C.CBytes(result)
	clear(result)
	return output
}

//export aerove_filter_free
func aerove_filter_free(value unsafe.Pointer) { C.free(value) }
func main()                                   {}
