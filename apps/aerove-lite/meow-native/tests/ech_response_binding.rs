use hickory_proto::{op::{Message,MessageType,OpCode,ResponseCode},rr::{DNSClass,Name,RData,Record,rdata::{CNAME,HTTPS,SVCB,svcb::{EchConfigList,SvcParamKey,SvcParamValue}}}};
use meow_config::ech_dns::ech_config_from_message;
fn name(value:&str)->Name{Name::from_utf8(value).unwrap()}
fn answer(records:Vec<Record>)->Message{let mut message=Message::new(7,MessageType::Response,OpCode::Query);for record in records{message.add_answer(record);}message}
fn https(owner:&str,priority:u16,target:&str,bytes:Option<&[u8]>)->Record{
    let params=bytes.map(|bytes|vec![(SvcParamKey::EchConfigList,SvcParamValue::EchConfigList(EchConfigList(bytes.to_vec())))]).unwrap_or_default();
    Record::from_rdata(name(owner),60,RData::HTTPS(HTTPS(SVCB::new(priority,name(target),params))))
}
fn cname(owner:&str,target:&str)->Record{Record::from_rdata(name(owner),60,RData::CNAME(CNAME(name(target))))}
fn get(message:&Message)->std::io::Result<Vec<u8>>{ech_config_from_message(message,&name("EcH.Example"))}
#[test]
fn unrelated_owner_or_non_in_class_never_supplies_ech(){
    assert!(get(&answer(vec![https("unrelated.example",1,".",Some(&[9]))])).is_err());
    let mut wrong_class=https("ech.example",1,".",Some(&[9]));wrong_class.dns_class=DNSClass::CH;
    assert!(get(&answer(vec![wrong_class])).is_err());
    let message=answer(vec![https("unrelated.example",1,".",Some(&[9])),https("ECH.EXAMPLE.",1,".",Some(&[1,2]))]);
    assert_eq!(get(&message).unwrap(),vec![1,2],"unrelated answers are ignored, not accepted or blanket-rejected");
}
#[test]
fn negative_empty_and_missing_config_fail_closed(){
    let mut message=answer(vec![https("ech.example",1,".",Some(&[1]))]);message.metadata.response_code=ResponseCode::NXDomain;
    assert!(get(&message).unwrap_err().to_string().contains("Negative"));
    for message in [answer(vec![]),answer(vec![https("ech.example",1,".",None)]),answer(vec![https("ech.example",1,".",Some(&[]))])]{assert!(get(&message).is_err());}
}
#[test]
fn cname_and_https_alias_lineage_is_order_independent_and_case_root_insensitive(){
    let message=answer(vec![https("service.example.",1,".",Some(&[4,5])),https("alias.example",0,"SERVICE.example",Some(&[99])),cname("ECH.example.","Alias.example.")]);
    assert_eq!(get(&message).unwrap(),vec![4,5],"AliasMode params must be ignored; ServiceMode root target is valid");
    let message=answer(vec![cname("ech.example","next.example"),https("next.example",1,"endpoint.example",Some(&[8]))]);
    assert_eq!(get(&message).unwrap(),vec![8]);
}
#[test]
fn alias_mode_overrides_service_mode_and_unavailable_or_incomplete_alias_fails(){
    let message=answer(vec![https("ech.example",1,".",Some(&[99])),https("ech.example",0,"next.example",None),https("next.example",1,".",Some(&[3]))]);
    assert_eq!(get(&message).unwrap(),vec![3]);
    assert!(get(&answer(vec![https("ech.example",0,".",Some(&[9]))])).is_err());
    assert!(get(&answer(vec![https("ech.example",0,"missing.example",Some(&[9]))])).is_err());
    let mut bad=cname("ech.example","next.example");bad.dns_class=DNSClass::CH;
    assert!(get(&answer(vec![bad,https("next.example",1,".",Some(&[9]))])).is_err());
}
#[test]
fn loops_conflicting_aliases_and_cname_with_https_are_rejected(){
    for records in [vec![cname("ech.example","a.example"),cname("a.example","ech.example")],
        vec![https("ech.example",0,"a.example",None),https("a.example",0,"ECH.example.",None)],
        vec![cname("ech.example","a.example"),cname("ech.example","b.example")],
        vec![https("ech.example",0,"a.example",None),https("ech.example",0,"b.example",None)],
        vec![cname("ech.example","a.example"),https("ech.example",1,".",Some(&[9]))]]{
        assert!(get(&answer(records)).is_err());
    }
}
#[test]
fn alias_depth_is_bounded_and_service_priority_is_respected(){
    let mut records=vec![cname("ech.example","n0.example")];
    for i in 0..17{records.push(cname(&format!("n{i}.example"),&format!("n{}.example",i+1)));}
    records.push(https("n18.example",1,".",Some(&[9])));
    assert!(get(&answer(records)).unwrap_err().to_string().contains("AliasLimit"));
    assert_eq!(get(&answer(vec![https("ech.example",10,".",Some(&[10])),https("ech.example",1,".",Some(&[1]))])).unwrap(),vec![1]);
}
