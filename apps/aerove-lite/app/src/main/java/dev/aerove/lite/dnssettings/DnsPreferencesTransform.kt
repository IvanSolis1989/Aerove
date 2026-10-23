package dev.aerove.lite.dnssettings

import java.net.URI

/** Call after script/standard rules, before native compatibility and resource preparation. */
fun transformDnsPreferences(source: Map<String, Any?>, global: DnsPreferences,
    profile: DnsPreferences? = null, customProfile: Boolean = false): Map<String, Any?> {
    val app = global.snapshot().requireValid()
    val selected = if (customProfile) (profile ?: DnsPreferences()).snapshot().requireValid() else app
    if (customProfile && selected.hosts.isNotEmpty()) dnsIssue("profile.dnsPreferences.hosts", DnsIssueCode.InvalidValue)
    val root = source.mapValuesTo(linkedMapOf()) { (_, value) -> copyDnsNode(value) }
    val rawDns = root["dns"]
    if (root.containsKey("dns") && rawDns !is Map<*, *>) dnsIssue("dns", DnsIssueCode.Type)
    val dns = stringMap(rawDns, "dns")
    // Standard's baseline is supplied only when the original section is not enabled.
    if (dns["enable"] != true) {
        listOf(DnsField.Enable, DnsField.EnhancedMode, DnsField.Nameserver, DnsField.DefaultNameserver).forEach { field ->
            putDnsField(dns, field, rawDnsValue(field.default))
        }
    }
    if (!customProfile && app.legacyFallbackServers != null &&
        ((rawDns as? Map<*, *>)?.get("nameserver") as? List<*>).isNullOrEmpty()) {
        dns["nameserver"] = app.legacyFallbackServers.toList()
    }
    selected.overridden.sortedBy { it.ordinal }.forEach { field ->
        if (customProfile || field.normalProfile) {
            val value = selected.value(field)
            val empty = when (value) {
                is DnsValue.Text -> value.value.isEmpty()
                is DnsValue.Entries -> value.value.isEmpty()
                is DnsValue.Policy -> value.value.isEmpty()
                else -> false
            }
            // setup.dart keeps explicit empty normal-profile overrides, except nameserver.
            if (!empty || (!customProfile && field != DnsField.Nameserver)) putDnsField(dns, field, rawDnsValue(value))
        }
    }
    if (dns["enhanced-mode"] == "redir-host") dnsIssue("dns.enhanced-mode", DnsIssueCode.UnsupportedCore)
    listOf("nameserver", "fallback", "default-nameserver", "proxy-server-nameserver").forEach { field ->
        if (dns.containsKey(field)) {
            val servers = dns[field] as? List<*> ?: dnsIssue("dns.$field", DnsIssueCode.Type)
            servers.forEachIndexed { index, raw ->
                validateDnsServer(raw as? String ?: dnsIssue("dns.$field[$index]", DnsIssueCode.Type), "dns.$field[$index]")
            }
        }
    }
    if (dns.containsKey("nameserver-policy")) {
        val policy = stringMap(dns["nameserver-policy"], "dns.nameserver-policy").mapValuesTo(linkedMapOf()) { (domain, raw) ->
            val servers = when (raw) {
                is String -> listOf(raw)
                is List<*> -> raw.map { it as? String ?: dnsIssue("dns.nameserver-policy.$domain", DnsIssueCode.Type) }
                else -> dnsIssue("dns.nameserver-policy.$domain", DnsIssueCode.Type)
            }
            servers
        }
        validateDnsValue(DnsField.NameserverPolicy, DnsValue.Policy(policy))
        dns["nameserver-policy"] = policy.mapValuesTo(linkedMapOf()) { it.value.single() }
    }
    root["dns"] = dns
    if (root.containsKey("hosts") && root["hosts"] !is Map<*, *>) dnsIssue("hosts", DnsIssueCode.Type)
    val hosts = stringMap(root["hosts"], "hosts")
    app.hosts.forEach { (host, targets) -> hosts[host] = targets.single() }
    hosts.forEach { (host, raw) ->
        val targets = when (raw) {
            is String -> listOf(raw)
            is List<*> -> raw.map { it as? String ?: dnsIssue("hosts.$host", DnsIssueCode.Type) }
            else -> dnsIssue("hosts.$host", DnsIssueCode.Type)
        }
        DnsPreferences(hosts = mapOf(host to targets)).requireValid()
        hosts[host] = targets.single()
    }
    if (root.containsKey("hosts") || hosts.isNotEmpty()) root["hosts"] = hosts
    return root
}

/** Call only after materialization has resolved the complete provider node catalog, before launch. */
fun validateDnsOutboundReferences(compiled: Map<String, Any?>, providerNames: Set<String>) {
    val dns = stringMap(compiled["dns"], "dns")
    val known = mutableSetOf("DIRECT", "REJECT", "GLOBAL").apply {
        addAll(providerNames)
        listOf("proxies", "proxy-groups").forEach { section ->
            (compiled[section] as? List<*>)?.forEach { item -> ((item as? Map<*, *>)?.get("name") as? String)?.let(::add) }
        }
    }
    fun checkReference(server: String, path: String) {
        val fragment = runCatching { URI(server).rawFragment }.getOrNull() ?: return
        val reference = fragment.split('&').firstOrNull { it.startsWith("proxy=") || '=' !in it }
            ?.removePrefix("proxy=") ?: return
        if (reference !in known) dnsIssue(path, DnsIssueCode.UnknownReference)
    }
    listOf("nameserver", "fallback", "default-nameserver", "proxy-server-nameserver").forEach { field ->
        (dns[field] as? List<*>)?.forEachIndexed { index, value -> if (value is String) checkReference(value, "dns.$field[$index]") }
    }
    (dns["nameserver-policy"] as? Map<*, *>)?.forEach { (domain, value) ->
        if (value is String) checkReference(value, "dns.nameserver-policy.$domain")
    }
}

private fun rawDnsValue(value: DnsValue): Any = when (value) {
    is DnsValue.Bool -> value.value
    is DnsValue.Text -> value.value
    is DnsValue.Entries -> value.value.toList()
    // Fixed core HashMap<String,String> holds one server, not Standard's array form.
    is DnsValue.Policy -> value.value.mapValuesTo(linkedMapOf()) { it.value.single() }
}

private fun putDnsField(dns: MutableMap<String, Any?>, field: DnsField, value: Any) {
    if (!field.path.startsWith("fallback-filter.")) dns[field.path] = value else {
        val filter = stringMap(dns["fallback-filter"], "dns.fallback-filter")
        filter[field.path.substringAfter('.')] = value
        dns["fallback-filter"] = filter
    }
}

private fun stringMap(value: Any?, path: String): MutableMap<String, Any?> {
    if (value == null) return linkedMapOf()
    val map = value as? Map<*, *> ?: dnsIssue(path, DnsIssueCode.Type)
    return map.entries.associateTo(linkedMapOf()) { (key, item) ->
        (key as? String ?: dnsIssue(path, DnsIssueCode.Type)) to copyDnsNode(item)
    }
}

private fun copyDnsNode(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { (key, item) -> key to copyDnsNode(item) }
    is List<*> -> value.map(::copyDnsNode)
    else -> value
}
