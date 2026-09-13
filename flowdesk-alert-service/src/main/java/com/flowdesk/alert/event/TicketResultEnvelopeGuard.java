package com.flowdesk.alert.event;

import com.flowdesk.alert.service.AlertIdempotencyCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict pre-tenant guard for the Ticket-owned authoritative mapping result. */
final class TicketResultEnvelopeGuard {
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> REQUIRED=Set.of("fd_envelope_version","fd_event_type","fd_contract_ref","fd_contract_version","fd_producer","fd_tenant_id","fd_message_id","fd_business_intent_key","fd_aggregate_type","fd_aggregate_id","fd_correlation_id","fd_occurred_at","fd_body_sha256");
    private static final Set<String> ALLOWED=new HashSet<>(REQUIRED);static{ALLOWED.add("fd_causation_id");}
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX=Pattern.compile("[0-9a-f]{64}");private static final Pattern MICROS=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
    AcceptedTicketResult validateMain(TicketResultTransportMessage m){return validate(m,AlertEventContract.TICKET_ESTABLISHED.topic(),AlertEventContract.TICKET_ESTABLISHED.consumerGroup());}
    AcceptedTicketResult validateDlq(TicketResultTransportMessage m){return validate(m,AlertEventContract.TICKET_ESTABLISHED.dlqTopic(),AlertEventContract.TICKET_ESTABLISHED.dlqAcceptorGroup());}
    private AcceptedTicketResult validate(TicketResultTransportMessage m,String topic,String group){req(m!=null,"DELIVERY_MISSING");req(topic.equals(m.topic()),"TOPIC_MISMATCH");req(group.equals(m.consumerGroup()),"CONSUMER_DIRECTION_MISMATCH");req(m.tags()==null,"TAG_FORBIDDEN");Map<String,String> p=m.properties();
        for(String key:REQUIRED)req(p.containsKey(key),"MISSING_ENVELOPE_PROPERTY:"+key);for(String key:p.keySet())if(key!=null&&key.startsWith("fd_")&&!ALLOWED.contains(key))throw bad("UNKNOWN_ENVELOPE_PROPERTY:"+key);
        fixed(p,"fd_envelope_version","1");fixed(p,"fd_event_type",AlertEventContract.TICKET_ESTABLISHED.eventType());fixed(p,"fd_contract_ref",AlertEventContract.TICKET_ESTABLISHED.contractRef());fixed(p,"fd_contract_version","1");fixed(p,"fd_producer",AlertEventContract.TICKET_ESTABLISHED.producer());fixed(p,"fd_aggregate_type","INCIDENT");
        UUID tenant=uuid(p.get("fd_tenant_id"),"fd_tenant_id"),message=uuid(p.get("fd_message_id"),"fd_message_id"),incident=uuid(p.get("fd_aggregate_id"),"fd_aggregate_id"),correlation=uuid(p.get("fd_correlation_id"),"fd_correlation_id");UUID causation=p.containsKey("fd_causation_id")?uuid(p.get("fd_causation_id"),"fd_causation_id"):null;req(message.toString().equals(m.keys()),"MESSAGE_KEY_MISMATCH");Instant envelopeAt=instant(p.get("fd_occurred_at"),"fd_occurred_at");
        byte[] body=m.body();req(body!=null&&body.length>0,"BODY_MISSING");req(body.length<=AlertEventContract.MAX_BODY_BYTES,"BODY_TOO_LARGE");byte[] hash=AlertIdempotencyCodec.sha256(body);req(MessageDigest.isEqual(hash,hex(p.get("fd_body_sha256"),"fd_body_sha256")),"BODY_HASH_MISMATCH");JsonNode payload=parse(body);req(payload.isObject(),"PAYLOAD_TYPE_INVALID:root");Set<String> fields=Set.of("schemaVersion","incidentId","createTicketIntentKey","ticketId","establishedAt");for(String field:fields)req(payload.get(field)!=null,"PAYLOAD_FIELD_MISSING:"+field);
        text(payload,"schemaVersion","1");UUID payloadIncident=payloadUuid(payload,"incidentId");req(incident.equals(payloadIncident),"AGGREGATE_PAYLOAD_MISMATCH");byte[] createIntent=payloadHex(payload,"createTicketIntentKey");UUID ticket=payloadUuid(payload,"ticketId");Instant established=payloadInstant(payload,"establishedAt");byte[] intent=hex(p.get("fd_business_intent_key"),"fd_business_intent_key");byte[] expected=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_ESTABLISHED.intentNamespace(),tenant,incident);req(MessageDigest.isEqual(intent,expected),"BUSINESS_INTENT_KEY_MISMATCH");return new AcceptedTicketResult(tenant,message,intent,incident,createIntent,ticket,established,correlation,causation,envelopeAt,body,hash);}
    private static JsonNode parse(byte[] body){try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();try(var parser=JSON.createParser(text)){JsonNode value=JSON.readTree(parser);req(parser.nextToken()==null,"PAYLOAD_TRAILING_CONTENT");return value;}}catch(TicketResultContractViolation expected){throw expected;}catch(Exception e){throw bad("PAYLOAD_INVALID_JSON",e);}}
    private static UUID payloadUuid(JsonNode n,String f){req(n.get(f)!=null&&n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return uuid(n.get(f).stringValue(),f);}private static byte[] payloadHex(JsonNode n,String f){req(n.get(f)!=null&&n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return hex(n.get(f).stringValue(),f);}private static Instant payloadInstant(JsonNode n,String f){req(n.get(f)!=null&&n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return instant(n.get(f).stringValue(),f);}private static void text(JsonNode n,String f,String v){req(n.get(f)!=null&&n.get(f).isString()&&v.equals(n.get(f).stringValue()),"PAYLOAD_VALUE_INVALID:"+f);}
    private static void fixed(Map<String,String> p,String f,String v){req(v.equals(p.get(f)),"ENVELOPE_VALUE_INVALID:"+f);}private static UUID uuid(String v,String f){req(v!=null&&UUID_TEXT.matcher(v).matches(),"UUID_FORMAT_INVALID:"+f);try{UUID x=UUID.fromString(v);req(x.toString().equals(v),"UUID_FORMAT_INVALID:"+f);return x;}catch(RuntimeException e){throw bad("UUID_FORMAT_INVALID:"+f,e);}}private static Instant instant(String v,String f){req(v!=null&&MICROS.matcher(v).matches(),"UTC_FORMAT_INVALID:"+f);try{return Instant.parse(v);}catch(RuntimeException e){throw bad("UTC_FORMAT_INVALID:"+f,e);}}private static byte[] hex(String v,String f){req(v!=null&&HEX.matcher(v).matches(),"SHA256_FORMAT_INVALID:"+f);return HexFormat.of().parseHex(v);}private static void req(boolean c,String code){if(!c)throw bad(code);}private static TicketResultContractViolation bad(String c){return new TicketResultContractViolation(c);}private static TicketResultContractViolation bad(String c,Throwable e){return new TicketResultContractViolation(c,e);}
}
