package main

import (
	"bufio"
	"context"
	"dev.aerove/filter/service"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"
)

type command struct {
	Type  string          `json:"type"`
	ID    uint64          `json:"id"`
	Batch json.RawMessage `json:"batch"`
}

func main() {
	engine := service.New()
	input := bufio.NewReader(os.Stdin)
	output := bufio.NewWriter(os.Stdout)
	var writeMu sync.Mutex
	emit := func(response service.Response) {
		data, _ := json.Marshal(response)
		writeMu.Lock()
		defer writeMu.Unlock()
		binary.Write(output, binary.LittleEndian, uint32(len(data)))
		output.Write(data)
		output.Flush()
	}
	jobs := make(chan service.Request, 1)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		for request := range jobs {
			emit(engine.Batch(ctx, request))
		}
	}()
	for {
		var size uint32
		if err := binary.Read(input, binary.LittleEndian, &size); err != nil {
			if err != io.EOF {
				fmt.Fprintln(os.Stderr, err)
			}
			break
		}
		if size > service.MaxBytes {
			fmt.Fprintln(os.Stderr, "frame limit")
			break
		}
		data := make([]byte, size)
		if _, err := io.ReadFull(input, data); err != nil {
			break
		}
		var cmd command
		if err := json.Unmarshal(data, &cmd); err != nil {
			fmt.Fprintln(os.Stderr, err)
			break
		}
		if cmd.Type == "cancel" {
			engine.Cancel(cmd.ID)
			continue
		}
		if cmd.Type == "shutdown" {
			break
		}
		request, err := service.Decode(cmd.Batch)
		if err != nil {
			emit(service.Response{ID: cmd.ID, Status: "invalid-request", Error: &service.Failure{Path: "$", Kind: "invalid-request", Message: err.Error()}, Engine: service.Version})
			continue
		}
		select {
		case jobs <- request:
		default:
			emit(service.Response{ID: request.ID, Status: "busy", Error: &service.Failure{Path: "$", Kind: "busy", Message: "one active and one queued batch allowed"}, Engine: service.Version})
		}
	}
	cancel()
	close(jobs)
	<-done
}
