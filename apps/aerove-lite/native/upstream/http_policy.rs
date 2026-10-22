//! Application HTTP policy; never used by an outbound node's TLS transport.
use std::sync::Arc;
use http::{HeaderMap, HeaderValue, header::USER_AGENT};
use rustls::{client::{WebPkiServerVerifier,danger::{ServerCertVerifier,ServerCertVerified,HandshakeSignatureValid}}, pki_types::{CertificateDer,ServerName,UnixTime},DigitallySignedStruct,SignatureScheme};

#[derive(Clone,PartialEq,Eq)]
pub(crate) struct Policy { user_agent:HeaderValue, check_certificate:bool }
impl Default for Policy {fn default()->Self{Self {user_agent:HeaderValue::from_static(crate::common::http::DEFAULT_USER_AGENT),check_certificate:true}}}
impl Policy {
    pub(crate) fn new(user_agent:Option<&str>,check_certificate:bool)->crate::Result<Self>{
        let user_agent=match user_agent {
            Some(value) if !value.trim().is_empty()&&value.len()<=4096&&!value.chars().any(char::is_control)=>HeaderValue::from_str(value).map_err(|_|super::invalid("$.global-ua: invalid HTTP header value"))?,
            None=>HeaderValue::from_static(crate::common::http::DEFAULT_USER_AGENT),
            _=>return Err(super::invalid("$.global-ua: invalid HTTP header value")),
        };
        Ok(Self {user_agent,check_certificate})
    }
    pub(crate) fn apply_headers(&self,headers:&mut HeaderMap){if !headers.contains_key(USER_AGENT){headers.insert(USER_AGENT,self.user_agent.clone());}}
    pub(crate) fn tls(&self,secure:Arc<rustls::ClientConfig>)->std::io::Result<Arc<rustls::ClientConfig>>{
        if self.check_certificate{return Ok(secure);}
        let pki=WebPkiServerVerifier::builder(crate::common::tls::GLOBAL_ROOT_STORE.clone()).build().map_err(std::io::Error::other)?;
        let mut relaxed=(*secure).clone();relaxed.dangerous().set_certificate_verifier(Arc::new(DownloadVerifier(pki)));Ok(Arc::new(relaxed))
    }
}
pub(crate) fn current()->Arc<Policy>{super::telemetry::source().map(|b|b.http_policy()).unwrap_or_else(||Arc::new(Policy::default()))}
#[derive(Debug)]
struct DownloadVerifier(Arc<WebPkiServerVerifier>);
impl ServerCertVerifier for DownloadVerifier {
    fn verify_server_cert(&self,leaf:&CertificateDer<'_>,chain:&[CertificateDer<'_>],name:&ServerName<'_>,ocsp:&[u8],now:UnixTime)->Result<ServerCertVerified,rustls::Error>{
        if self.0.verify_server_cert(leaf,chain,name,ocsp,now).is_err(){tracing::warn!("accepting an untrusted HTTP download certificate because certificate checking is disabled");}
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(&self,message:&[u8],cert:&CertificateDer<'_>,dss:&DigitallySignedStruct)->Result<HandshakeSignatureValid,rustls::Error>{self.0.verify_tls12_signature(message,cert,dss)}
    fn verify_tls13_signature(&self,message:&[u8],cert:&CertificateDer<'_>,dss:&DigitallySignedStruct)->Result<HandshakeSignatureValid,rustls::Error>{self.0.verify_tls13_signature(message,cert,dss)}
    fn supported_verify_schemes(&self)->Vec<SignatureScheme>{self.0.supported_verify_schemes()}
}
