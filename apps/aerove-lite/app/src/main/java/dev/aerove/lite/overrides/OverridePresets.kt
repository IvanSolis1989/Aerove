package dev.aerove.lite.overrides

// Ordered exactly as Standard features/form/rule_preset.dart; validation remains the pinned core's responsibility.
enum class OverridePreset(val rules: List<String>) {
    BlockQuic(listOf("AND,((NETWORK,UDP),(DST-PORT,443)),REJECT-DROP")),
    BlockStun(listOf("AND,((NETWORK,UDP),(DST-PORT,3478/19302)),REJECT-DROP")),
    BlockDot(listOf("DST-PORT,853,REJECT")),
    BlockLoopback(listOf("IP-CIDR,127.0.0.0/8,REJECT,no-resolve", "IP-CIDR,0.0.0.0/32,REJECT,no-resolve",
        "IP-CIDR6,::1/128,REJECT,no-resolve", "IP-CIDR6,::/128,REJECT,no-resolve", "AND,((IN-TYPE,TUN),(DST-PORT,853)),REJECT")),
    LanDirect(listOf("GEOSITE,private,DIRECT", "GEOIP,LAN,DIRECT,no-resolve")),
    SystemDirect(listOf("GEOSITE,apple,DIRECT", "GEOSITE,microsoft,DIRECT")),
    BittorrentDirect(listOf("GEOSITE,category-public-tracker,DIRECT", "GEOSITE,category-pt,DIRECT",
        "PROCESS-NAME-REGEX,(?i)^(qbittorrent|transmission.*|deluge.*|aria2c|motrix|utorrent|bitcomet)(\\.exe)?$,DIRECT")),
}
