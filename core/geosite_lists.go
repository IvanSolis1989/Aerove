package core

import (
	_ "unsafe"

	"github.com/metacubex/mihomo/common/singleflight"
	"github.com/metacubex/mihomo/component/geodata/router"
)

// mihomo keeps every decoded geosite list although only the matchers built from
// them are read again, and ClearGeoSiteCache would drop those matchers as well.
//
//go:linkname geoSiteLists github.com/metacubex/mihomo/component/geodata.loadGeoSiteMatcherListSF
var geoSiteLists singleflight.Group[[]*router.Domain]
