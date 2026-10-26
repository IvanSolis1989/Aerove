use super::{Env, Item};
use super::super::http_probe::{ProbeRequest, ProbeResult};
use std::{collections::HashMap, sync::OnceLock};
use axum::http::{Method, StatusCode};
use regex::Regex;

const SMALL: usize = 16 * 1024;
const HTML: usize = 512 * 1024;
const DISNEY_AUTH: &str = "Bearer ZGlzbmV5JmJyb3dzZXImMS4wLjA.Cu56AgSfBTDag5NiRA81oLHkDZfu5L3CKadnefEAY84";
const GEMINI_MARKER: &str = ",2,1,200,\"";
const TIKTOK: &str = r#""webapp\.app-context"\s*:\s*\{[^{}]*?"region"\s*:\s*"([^"]*)""#;
const PRIME: &str = r#""currentTerritory":"([^"]+)""#;
const ASSERTION: &str = r#""assertion"\s*:\s*"([^"]+)""#;
const REFRESH: &str = r#""refresh_token"\s*:\s*"([^"]+)""#;
const COUNTRY: &str = r#""countryCode"\s*:\s*"([^"]+)""#;
const SUPPORTED: &str = r#""inSupportedLocation"\s*:\s*(false|true)"#;
const DISCORD: &str = r#""country_code"\s*:\s*"([^"]+)""#;
const BILIBILI: &str = r#"^\s*\{\s*"code"\s*:\s*(-?\d+)"#;
const YOUTUBE: &[&str] = &[
    r#"(?i)id=["']country-code["'][^>]*>\s*([A-Za-z]{2,3})\s*<"#,
    r#""GL"\s*:\s*"([A-Za-z]{2})""#,
    r#""countryCode"\s*:\s*"([A-Za-z]{2})""#,
    r#""country_code"\s*:\s*"([A-Za-z]{2})""#,
];

fn pattern(value: &'static str) -> &'static Regex {
    static PATTERNS: OnceLock<HashMap<&'static str, Regex>> = OnceLock::new();
    PATTERNS.get_or_init(|| YOUTUBE.iter().copied().chain([TIKTOK, PRIME, ASSERTION, REFRESH, COUNTRY, SUPPORTED, DISCORD, BILIBILI])
        .map(|value| (value, Regex::new(value).unwrap())).collect()).get(value).unwrap()
}

fn capture(value: &'static str, body: &str) -> String {
    pattern(value).captures(body).and_then(|captures| captures.get(1)).map(|value| value.as_str().to_owned()).unwrap_or_default()
}

fn body(result: &ProbeResult) -> String { String::from_utf8_lossy(&result.body).into_owned() }

fn contains(result: &ProbeResult, needles: &[&str]) -> bool {
    let lower = body(result).to_lowercase();
    needles.iter().any(|needle| lower.contains(needle))
}

fn trace(result: &ProbeResult, name: &str) -> String {
    body(result).lines().find_map(|line| line.strip_prefix(&format!("{name}=")).map(|value| value.trim().to_owned())).unwrap_or_default()
}

fn code_status(code: StatusCode) -> &'static str {
    match code.as_u16() {
        200..=299 => "available",
        401 | 403 | 429 | 451 => "restricted",
        _ => "unavailable",
    }
}

fn status(mut item: Item, status: &str) -> Item { item.status = status.into(); item }

pub(super) async fn check(name: &str, env: &Env<'_>) -> Item {
    match name {
        "google" | "github" => {
            let url = if name == "google" { "https://www.google.com/generate_204" } else { "https://github.com/" };
            let (item, result) = env.get(url, 0).await;
            match result { Some(result) => status(item, code_status(result.status)), None => item }
        }
        "claude" => {
            let (mut item, result) = env.get("https://claude.ai/cdn-cgi/trace", SMALL).await;
            let Some(result) = result else { return item; };
            item.region = region(&trace(&result, "loc"));
            let outcome = if item.region.is_empty() { "failed" }
                else if ["AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY"].contains(&item.region.as_str()) { "unsupported-region" }
                else { "available" };
            status(item, outcome)
        }
        "gemini" => {
            let (mut item, result) = env.scan("https://gemini.google.com", |tail| tail.find(GEMINI_MARKER).is_some_and(|index| index + GEMINI_MARKER.len() + 3 <= tail.len())).await;
            let Some(result) = result else { return item; };
            let body = body(&result);
            let Some(code) = body.find(GEMINI_MARKER).and_then(|index| body.get(index + GEMINI_MARKER.len()..index + GEMINI_MARKER.len() + 3)) else { return status(item, "failed"); };
            if !code.bytes().all(|byte| byte.is_ascii_uppercase()) { return status(item, "failed"); }
            item.region = region(code);
            status(item, if ["CHN", "RUS", "BLR", "CUB", "IRN", "PRK", "SYR", "HKG", "MAC"].contains(&code) { "unsupported-region" } else { "available" })
        }
        "chatgpt" => chatgpt(env).await,
        "youtube" => {
            let (mut item, result) = env.get("https://www.youtube.com/premium?hl=en", HTML).await;
            let Some(result) = result else { return item; };
            let text = body(&result);
            item.region = YOUTUBE.iter().find_map(|value| { let value = capture(value, &text); (!value.is_empty()).then(|| region(&value)) }).unwrap_or_default();
            let outcome = if contains(&result, &["youtube premium is not available in your country", "premium is not available in your country", "premium is not available in your region"]) { "unsupported-region" }
                else if result.status.is_success() && contains(&result, &["youtube premium", "ad-free", "\"browseid\":\"spunlimited\""]) { "available" }
                else { code_status(result.status) };
            status(item, outcome)
        }
        "spotify" => {
            let (mut item, result) = env.get("https://www.spotify.com/api/content/v1/country-selector?platform=web&format=json", SMALL).await;
            let Some(result) = result else { return item; };
            let redirected = url::Url::parse(&result.url).ok().and_then(|url| url.path().trim_matches('/').split('/').next().map(str::to_owned))
                .filter(|part| !part.is_empty() && part != "api").map(|part| part.split('-').next().unwrap_or("").to_owned());
            item.region = region(&redirected.unwrap_or_else(|| capture(COUNTRY, &body(&result))));
            status(item, if matches!(result.status.as_u16(), 403 | 451) { "unsupported-region" }
                else if !result.status.is_success() { code_status(result.status) }
                else if contains(&result, &["not available in your country"]) { "unsupported-region" } else { "available" })
        }
        "tiktok" => {
            let (mut item, result) = env.scan("https://www.tiktok.com/", |tail| pattern(TIKTOK).is_match(tail)).await;
            let Some(result) = result else { return item; };
            item.region = region(&capture(TIKTOK, &body(&result)));
            status(item, if matches!(result.status.as_u16(), 403 | 451) { "unsupported-region" }
                else if !result.status.is_success() { "failed" }
                else if contains(&result, &["access denied", "not available in your region", "tiktok is not available"]) { "unsupported-region" } else { "available" })
        }
        "discord" => {
            let (mut item, result) = env.get("https://discord.com/api/v9/auth/location-metadata", SMALL).await;
            let Some(result) = result else { return item; };
            if result.status.is_success() { item.region = region(&capture(DISCORD, &body(&result))); }
            status(item, code_status(result.status))
        }
        "prime-video" => {
            let (mut item, result) = env.scan("https://www.primevideo.com", |tail| tail.contains("isServiceRestricted") || pattern(PRIME).is_match(tail)).await;
            let Some(result) = result else { return item; };
            let text = body(&result);
            if text.contains("isServiceRestricted") { return status(item, "unsupported-region"); }
            item.region = region(&capture(PRIME, &text));
            let outcome = if capture(PRIME, &text).is_empty() { "failed" } else { "available" };
            status(item, outcome)
        }
        "bilibili" => bilibili(env).await,
        "netflix" => netflix(env).await,
        "disney-plus" => disney(env).await,
        _ => status(Item::default(), "failed"),
    }
}

async fn chatgpt(env: &Env<'_>) -> Item {
    let (mut item, ios) = env.get("https://ios.chat.openai.com/", SMALL).await;
    if let Some(ios) = ios {
        item = status(item, if contains(&ios, &["you may be connected to a disallowed isp"]) { "disallowed-isp" }
            else if contains(&ios, &["sorry, you have been blocked"]) { "blocked" }
            else if contains(&ios, &["request is not allowed. please try again later."]) { "available" } else { "failed" });
    }
    if let (_, Some(trace_result)) = env.get("https://chat.openai.com/cdn-cgi/trace", SMALL).await { item.region = region(&trace(&trace_result, "loc")); }
    if let (_, Some(web)) = env.get("https://api.openai.com/compliance/cookie_requirements", SMALL).await
        && contains(&web, &["unsupported_country"]) { item.status = "unsupported-region".into(); }
    item
}

async fn bilibili(env: &Env<'_>) -> Item {
    const CN: &str = "https://api.bilibili.com/pgc/player/web/playurl?avid=82846771&qn=0&type=&otype=json&ep_id=307247&fourk=1&fnver=0&fnval=16&module=bangumi";
    const HMT: &str = "https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi";
    for (url, mainland) in [(CN, true), (HMT, false)] {
        let (mut item, result) = env.get(url, SMALL).await;
        let Some(result) = result else { return item; };
        let outcome = match capture(BILIBILI, &body(&result)).as_str() { "0" => "available", "-10403" => "unsupported-region", _ => "failed" };
        if mainland && outcome == "available" { item.region = "CN".into(); }
        if mainland && outcome == "unsupported-region" { continue; }
        return status(item, outcome);
    }
    unreachable!()
}

async fn netflix(env: &Env<'_>) -> Item {
    let (mut cdn_item, cdn) = env.get("https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5", SMALL).await;
    if let Some(result) = cdn {
        if result.status == StatusCode::FORBIDDEN { return status(cdn_item, "blocked"); }
        if let Ok(payload) = serde_json::from_slice::<serde_json::Value>(&result.body)
            && payload["targets"].as_array().is_some_and(|targets| !targets.is_empty())
        {
            cdn_item.region = region(payload["targets"][0]["location"]["country"].as_str().unwrap_or(""));
            return status(cdn_item, "available");
        }
    }
    let (mut item, first) = env.get("https://www.netflix.com/title/81280792", 0).await;
    let Some(first) = first else { return item; };
    let (second_item, second) = env.get("https://www.netflix.com/title/70143836", 0).await;
    let Some(second) = second else { return second_item; };
    let outcome = if first.status == StatusCode::NOT_FOUND && second.status == StatusCode::NOT_FOUND { "originals-only" }
        else if first.status == StatusCode::FORBIDDEN || second.status == StatusCode::FORBIDDEN { "unsupported-region" }
        else if [200, 301].contains(&first.status.as_u16()) || [200, 301].contains(&second.status.as_u16()) {
            let mut request = ProbeRequest::get("https://www.netflix.com/title/80018499", 0, env.timeout);
            request.no_redirect = true;
            if let (_, Some(result)) = env.send(request).await
                && let Some(location) = result.headers.get(axum::http::header::LOCATION).and_then(|value| value.to_str().ok())
                && let Some(country) = location.split('/').nth(3)
            { item.region = region(country.split('-').next().unwrap_or("")); }
            "available"
        } else { code_status(first.status) };
    status(item, outcome)
}

async fn disney(env: &Env<'_>) -> Item {
    let device_body = br#"{"deviceFamily":"browser","applicationRuntime":"chrome","deviceProfile":"windows","attributes":{}}"#;
    let (mut item, device) = env.send(disney_request(env, "/devices", "application/json; charset=UTF-8", device_body.to_vec())).await;
    let Some(device) = device else { return item; };
    if device.status == StatusCode::FORBIDDEN { return status(item, "blocked"); }
    let assertion = capture(ASSERTION, &body(&device));
    if assertion.is_empty() { return status(item, "failed"); }
    let form = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
        .append_pair("latitude", "0").append_pair("longitude", "0").append_pair("platform", "browser")
        .append_pair("subject_token", &assertion).append_pair("subject_token_type", "urn:bamtech:params:oauth:token-type:device").finish();
    let (token_item, token) = env.send(disney_request(env, "/token", "application/x-www-form-urlencoded", form.into_bytes())).await;
    let Some(token) = token else { return token_item; };
    let text = body(&token);
    if text.contains("forbidden-location") || text.contains("403 ERROR") { return status(item, "blocked"); }
    let refresh = capture(REFRESH, &text);
    if refresh.is_empty() { return status(item, "failed"); }
    let query = serde_json::json!({
        "query": "mutation refreshToken($input: RefreshTokenInput!) { refreshToken(refreshToken: $input) { activeSession { sessionId } } }",
        "variables": {"input": {"refreshToken": refresh}},
    }).to_string();
    let (_, session) = env.send(disney_request(env, "/graph/v1/device/graphql", "application/json", query.into_bytes())).await;
    let Some(session) = session else { return status(item, "failed"); };
    if session.body.is_empty() || session.status.as_u16() >= 400 { return status(item, "failed"); }
    let text = body(&session);
    item.region = region(&capture(COUNTRY, &text));
    if item.region.is_empty() { return status(item, "unavailable"); }
    status(item, match capture(SUPPORTED, &text).as_str() { "true" => "available", "false" => "coming-soon", _ => "failed" })
}

fn disney_request(env: &Env<'_>, path: &str, content_type: &str, body: Vec<u8>) -> ProbeRequest {
    let mut request = ProbeRequest::get(&format!("https://disney.api.edge.bamgrid.com{path}"), SMALL, env.timeout);
    request.method = Method::POST;
    request.headers.insert(axum::http::header::AUTHORIZATION, DISNEY_AUTH.parse().unwrap());
    request.headers.insert(axum::http::header::CONTENT_TYPE, content_type.parse().unwrap());
    request.body = body;
    request
}

fn region(code: &str) -> String {
    let code = code.trim().to_ascii_uppercase();
    if !code.bytes().all(|byte| byte.is_ascii_uppercase()) { return String::new(); }
    match code.len() {
        2 => code,
        3 => ISO3166.as_bytes().chunks_exact(5).find(|entry| &entry[..3] == code.as_bytes())
            .map(|entry| String::from_utf8(entry[3..].to_vec()).unwrap()).unwrap_or_default(),
        _ => String::new(),
    }
}

const ISO3166: &str = "ABWAWAFGAFAGOAOAIAAIALAAXALBALANDADAREAEARGARARMAMASMASATAAQATFTFATGAGAUSAUAUTATAZEAZBDIBIBELBEBENBJBESBQBFABFBGDBDBGRBGBHRBHBHSBSBIHBABLMBLBLRBYBLZBZBMUBMBOLBOBRABRBRBBBBRNBNBTNBTBVTBVBWABWCAFCFCANCACCKCCCHECHCHLCLCHNCNCIVCICMRCMCODCDCOGCGCOKCKCOLCOCOMKMCPVCVCRICRCUBCUCUWCWCXRCXCYMKYCYPCYCZECZDEUDEDJIDJDMADMDNKDKDOMDODZADZECUECEGYEGERIERESHEHESPESESTEEETHETFINFIFJIFJFLKFKFRAFRFROFOFSMFMGABGAGBRGBGEOGEGGYGGGHAGHGIBGIGINGNGLPGPGMBGMGNBGWGNQGQGRCGRGRDGDGRLGLGTMGTGUFGFGUMGUGUYGYHKGHKHMDHMHNDHNHRVHRHTIHTHUNHUIDNIDIMNIMINDINIOTIOIRLIEIRNIRIRQIQISLISISRILITAITJAMJMJEYJEJORJOJPNJPKAZKZKENKEKGZKGKHMKHKIRKIKNAKNKORKRKWTKWLAOLALBNLBLBRLRLBYLYLCALCLIELILKALKLSOLSLTULTLUXLULVALVMACMOMAFMFMARMAMCOMCMDAMDMDGMGMDVMVMEXMXMHLMHMKDMKMLIMLMLTMTMMRMMMNEMEMNGMNMNPMPMOZMZMRTMRMSRMSMTQMQMUSMUMWIMWMYSMYMYTYTNAMNANCLNCNERNENFKNFNGANGNICNINIUNUNLDNLNORNONPLNPNRUNRNZLNZOMNOMPAKPKPANPAPCNPNPERPEPHLPHPLWPWPNGPGPOLPLPRIPRPRKKPPRTPTPRYPYPSEPSPYFPFQATQAREUREROURORUSRURWARWSAUSASDNSDSENSNSGPSGSGSGSSHNSHSJMSJSLBSBSLESLSLVSVSMRSMSOMSOSPMPMSRBRSSSDSSSTPSTSURSRSVKSKSVNSISWESESWZSZSXMSXSYCSCSYRSYTCATCTCDTDTGOTGTHATHTJKTJTKLTKTKMTMTLSTLTONTOTTOTTTUNTNTURTRTUVTVTWNTWTZATZUGAUGUKRUAUMIUMURYUYUSAUSUZBUZVATVAVCTVCVENVEVGBVGVIRVIVNMVNVUTVUWLFWFWSMWSYEMYEZAFZAZMBZMZWEZW";
