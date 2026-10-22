package core

import (
	"fmt"
	"os"
	"testing"

	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/constant"
)

func TestMain(m *testing.M) {
	home, err := os.MkdirTemp("", "aerove-core-tests-")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	previous := constant.Path.HomeDir()
	constant.SetHomeDir(home)
	cache := cachefile.Cache()
	code := m.Run()
	if cache.DB != nil {
		if err := cache.Close(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			code = 1
		}
	}
	constant.SetHomeDir(previous)
	if err := os.RemoveAll(home); err != nil {
		fmt.Fprintln(os.Stderr, err)
		code = 1
	}
	os.Exit(code)
}
