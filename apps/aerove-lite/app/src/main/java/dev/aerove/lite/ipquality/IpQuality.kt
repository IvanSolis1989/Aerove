package dev.aerove.lite.ipquality

import java.io.IOException
import org.json.JSONObject

enum class IpType { Residential, Mobile, Business, Hosting }
enum class IpQualityLevel { Good, Normal, Risky }
enum class IpQualitySource(val label: String) {
    IdentMe("ident.me"), IpApiCom("ip-api.com"), IpQuery("ipquery.io"),
    IpLocate("iplocate.io"), ProxyCheck("proxycheck.io"), IpApiIs("ipapi.is");

    fun url(ip: String): String {
        require(validQualityIp(ip)) { "Invalid exit IP address" }
        return when (this) {
            IdentMe -> "https://ident.me/json"
            IpApiCom -> "http://ip-api.com/json/$ip?fields=status,isp,org,as,mobile,proxy,hosting"
            IpQuery -> "https://api.ipquery.io/$ip?format=json"
            IpLocate -> "https://iplocate.io/api/lookup/$ip"
            ProxyCheck -> "https://proxycheck.io/v3/$ip"
            IpApiIs -> "https://api.ipapi.is/?q=$ip"
        }
    }
}
enum class IpQualitySourceStatus { NoType, Timeout, RateLimited, Failed, IpMismatch }
data class IpQualityFailure(val source: IpQualitySource, val status: IpQualitySourceStatus)
class IpQualityLookupException(val failures: List<IpQualityFailure>) : IOException("IP type lookup failed")
internal class IpQualitySourceException(val status: IpQualitySourceStatus) : IOException("IP quality source failed")

data class IpQuality(
    val ip: String, val source: IpQualitySource, val type: IpType,
    val organization: String? = null, val asn: Long? = null,
    val isProxy: Boolean = false, val isVpn: Boolean = false,
    val isTor: Boolean = false, val isAbuser: Boolean = false,
) {
    val level: IpQualityLevel get() = when {
        isTor || isAbuser -> IpQualityLevel.Risky
        type == IpType.Hosting -> IpQualityLevel.Normal
        else -> IpQualityLevel.Good
    }
}

internal data class IpQualityAnswer(val quality: IpQuality, val inferred: Boolean = false)

internal fun validQualityIp(ip: String): Boolean =
    if (':' in ip) ip.length in 2..45 && ip.count { it == ':' } >= 2 && ip.all { it in "0123456789abcdefABCDEF:." }
    else ip.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 } }

internal fun parseIpQuality(source: IpQualitySource, ip: String, json: JSONObject): IpQualityAnswer {
    fun text(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }
    fun asn(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is String -> Regex("[0-9]+").find(value)?.value?.toLongOrNull()
        else -> null
    }?.takeUnless { it == 0L }
    fun declared(value: Any?): IpType? = when (value) {
        "isp" -> IpType.Residential
        "business", "education", "government", "banking" -> IpType.Business
        "hosting" -> IpType.Hosting
        else -> null
    }
    fun flag(obj: JSONObject, key: String) = obj.opt(key) == true
    fun obj(parent: JSONObject, key: String) = parent.optJSONObject(key) ?: JSONObject()
    fun pick(hosting: Boolean, mobile: Boolean = false, declared: IpType? = null): IpType? = when {
        hosting || declared == IpType.Hosting -> IpType.Hosting
        mobile -> IpType.Mobile
        else -> declared
    }
    fun result(type: IpType?, organization: Any? = null, number: Any? = null, inferred: Boolean = false,
        proxy: Boolean = false, vpn: Boolean = false, tor: Boolean = false, abuser: Boolean = false): IpQualityAnswer {
        if (type == null) throw IpQualitySourceException(IpQualitySourceStatus.NoType)
        return IpQualityAnswer(IpQuality(ip, source, type, text(organization), asn(number), proxy, vpn, tor, abuser), inferred)
    }
    return when (source) {
        IpQualitySource.IdentMe -> {
            if (json.opt("ip") != ip) throw IpQualitySourceException(IpQualitySourceStatus.IpMismatch)
            result(declared(json.opt("type")), json.opt("aso"), json.opt("asn"))
        }
        IpQualitySource.IpApiCom -> {
            if (json.opt("status") != "success") throw IpQualitySourceException(IpQualitySourceStatus.Failed)
            val type = pick(flag(json, "hosting"), flag(json, "mobile"))
            result(type ?: IpType.Residential, text(json.opt("org")) ?: text(json.opt("isp")), json.opt("as"),
                inferred = type == null, proxy = flag(json, "proxy"))
        }
        IpQualitySource.IpQuery -> {
            val risk = obj(json, "risk"); val isp = obj(json, "isp")
            val type = pick(flag(risk, "is_datacenter"), flag(risk, "is_mobile"))
            result(type ?: IpType.Residential, text(isp.opt("org")) ?: text(isp.opt("isp")), isp.opt("asn"),
                inferred = type == null, proxy = flag(risk, "is_proxy"), vpn = flag(risk, "is_vpn"), tor = flag(risk, "is_tor"))
        }
        IpQualitySource.IpLocate -> {
            val privacy = obj(json, "privacy"); val company = obj(json, "company"); val network = obj(json, "asn")
            result(pick(flag(privacy, "is_hosting"), declared = declared(company.opt("type")) ?: declared(network.opt("type"))),
                text(company.opt("name")) ?: text(network.opt("name")), network.opt("asn"),
                proxy = flag(privacy, "is_proxy"), vpn = flag(privacy, "is_vpn"), tor = flag(privacy, "is_tor"), abuser = flag(privacy, "is_abuser"))
        }
        IpQualitySource.ProxyCheck -> {
            if (json.opt("status") == "denied") throw IpQualitySourceException(IpQualitySourceStatus.RateLimited)
            if (json.opt("status") !in listOf("ok", "warning")) throw IpQualitySourceException(IpQualitySourceStatus.Failed)
            val entry = obj(json, ip); val network = obj(entry, "network"); val detections = obj(entry, "detections")
            val type = network.opt("type")
            result(pick(flag(detections, "hosting") || type == "Hosting", type == "Wireless", when (type) {
                "Residential" -> IpType.Residential; "Business" -> IpType.Business; else -> null
            }), network.opt("organisation"), network.opt("asn"), proxy = flag(detections, "proxy"),
                vpn = flag(detections, "vpn"), tor = flag(detections, "tor"))
        }
        IpQualitySource.IpApiIs -> {
            val company = obj(json, "company"); val network = obj(json, "asn")
            result(pick(flag(json, "is_datacenter"), flag(json, "is_mobile"), declared(company.opt("type")) ?: declared(network.opt("type"))),
                text(company.opt("name")) ?: text(network.opt("org")), network.opt("asn"), proxy = flag(json, "is_proxy"),
                vpn = flag(json, "is_vpn"), tor = flag(json, "is_tor"), abuser = flag(json, "is_abuser"))
        }
    }
}
