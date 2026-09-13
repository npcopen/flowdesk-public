package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.service.IdempotencyCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict pre-tenant guard for both Alert -> Ticket M7 directions. */
public final class IncidentEnvelopeGuard {
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> REQUIRED=Set.of("fd_envelope_version","fd_event_type","fd_contract_ref",
            "fd_contract_version","fd_producer","fd_tenant_id","fd_message_id","fd_business_intent_key",
            "fd_aggregate_type","fd_aggregate_id","fd_correlation_id","fd_occurred_at","fd_body_sha256");
    private static final Set<String> ALLOWED=new java.util.HashSet<>(REQUIRED);
    static{ALLOWED.add("fd_causation_id");}
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX=Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MICROS=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");

    public AcceptedIncidentMessage validateMain(IncidentTransportMessage message){
        var binding=bindingMain(message);return validate(message,binding,binding.topic(),binding.consumerGroup());}
    public AcceptedIncidentMessage validateDlq(IncidentTransportMessage message){
        var binding=bindingDlq(message);return validate(message,binding,binding.dlqTopic(),binding.dlqAcceptorGroup());}
    private static IncidentMessageContract.Binding bindingMain(IncidentTransportMessage m){require(m!=null,"DELIVERY_MISSING");
        for(var b:bindings())if(b.topic().equals(m.topic())&&b.consumerGroup().equals(m.consumerGroup()))return b;
        throw bad("CONSUMER_DIRECTION_MISMATCH");}
    private static IncidentMessageContract.Binding bindingDlq(IncidentTransportMessage m){require(m!=null,"DELIVERY_MISSING");
        for(var b:bindings())if(b.dlqTopic().equals(m.topic())&&b.dlqAcceptorGroup().equals(m.consumerGroup()))return b;
        throw bad("CONSUMER_DIRECTION_MISMATCH");}
    private AcceptedIncidentMessage validate(IncidentTransportMessage m,IncidentMessageContract.Binding b,String topic,String group){
        require(topic.equals(m.topic()),"TOPIC_MISMATCH");require(group.equals(m.consumerGroup()),"CONSUMER_DIRECTION_MISMATCH");
        require(m.tags()==null,"TAG_FORBIDDEN");Map<String,String> p=m.userProperties();
        for(String key:REQUIRED)require(p.containsKey(key),"MISSING_ENVELOPE_PROPERTY:"+key);
        for(String key:p.keySet())if(key!=null&&key.startsWith("fd_")&&!ALLOWED.contains(key))throw bad("UNKNOWN_ENVELOPE_PROPERTY:"+key);
        fixed(p,"fd_envelope_version","1");fixed(p,"fd_event_type",b.eventType());fixed(p,"fd_contract_ref",b.contractRef());
        fixed(p,"fd_contract_version","1");fixed(p,"fd_producer",IncidentMessageContract.ALERT_PRODUCER);
        fixed(p,"fd_aggregate_type",IncidentMessageContract.AGGREGATE_TYPE);
        UUID tenant=uuid(p.get("fd_tenant_id"),"fd_tenant_id"),messageId=uuid(p.get("fd_message_id"),"fd_message_id");
        UUID incident=uuid(p.get("fd_aggregate_id"),"fd_aggregate_id"),correlation=uuid(p.get("fd_correlation_id"),"fd_correlation_id");
        UUID causation=p.containsKey("fd_causation_id")?uuid(p.get("fd_causation_id"),"fd_causation_id"):null;
        require(messageId.toString().equals(m.keys()),"MESSAGE_KEY_MISMATCH");Instant envelopeAt=instant(p.get("fd_occurred_at"),"fd_occurred_at");
        byte[] body=m.body();require(body!=null&&body.length>0,"BODY_MISSING");require(body.length<=IncidentMessageContract.MAX_BODY_BYTES,"BODY_TOO_LARGE");
        byte[] bodyHash=sha(body),declared=hex(p.get("fd_body_sha256"),"fd_body_sha256");require(MessageDigest.isEqual(bodyHash,declared),"BODY_HASH_MISMATCH");
        JsonNode payload=parse(body);require(payload.isObject(),"PAYLOAD_TYPE_INVALID:root");
        for(String field:Set.of("schemaVersion","incidentId","createTicketIntentKey"))require(payload.get(field)!=null,"PAYLOAD_FIELD_MISSING:"+field);
        text(payload,"schemaVersion","1");UUID payloadIncident=payloadUuid(payload,"incidentId");require(incident.equals(payloadIncident),"AGGREGATE_PAYLOAD_MISMATCH");
        byte[] createIntent=payloadHex(payload,"createTicketIntentKey");byte[] intent=hex(p.get("fd_business_intent_key"),"fd_business_intent_key");
        byte[] expected=IdempotencyCodec.derivedKey(b.intentNamespace(),tenant,incident);require(MessageDigest.isEqual(intent,expected),"BUSINESS_INTENT_KEY_MISMATCH");
        String fingerprint=null,cycle=null,summary=null;Integer generation=null;Instant opened=null,recovered=null;UUID recoveryEvent=null;
        if(b.kind()==IncidentMessageContract.Kind.CREATE_REQUESTED){
            for(String field:Set.of("fingerprint","generationNo","cycleRef","summary","openedAt"))require(payload.get(field)!=null,"PAYLOAD_FIELD_MISSING:"+field);
            require(MessageDigest.isEqual(createIntent,intent),"CREATE_INTENT_KEY_MISMATCH");
            fingerprint=boundedText(payload,"fingerprint",1,255);cycle=boundedText(payload,"cycleRef",1,128);
            JsonNode number=payload.get("generationNo");require(number.isIntegralNumber()&&number.canConvertToInt()&&number.intValue()>0,"PAYLOAD_TYPE_INVALID:generationNo");generation=number.intValue();
            if(!payload.get("summary").isNull())summary=boundedText(payload,"summary",0,1000);opened=payloadInstant(payload,"openedAt");
        }else{
            for(String field:Set.of("recoveryEventId","recoveredAt"))require(payload.get(field)!=null,"PAYLOAD_FIELD_MISSING:"+field);
            recoveryEvent=payloadUuid(payload,"recoveryEventId");recovered=payloadInstant(payload,"recoveredAt");
        }
        return new AcceptedIncidentMessage(b,tenant,messageId,intent,incident,createIntent,fingerprint,generation,cycle,summary,opened,
                recoveryEvent,recovered,correlation,causation,envelopeAt,body,bodyHash);
    }
    private static JsonNode parse(byte[] body){try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            try(var parser=JSON.createParser(text)){JsonNode value=JSON.readTree(parser);
                require(parser.nextToken()==null,"PAYLOAD_TRAILING_CONTENT");return value;}}
        catch(Exception invalid){throw bad("PAYLOAD_INVALID_JSON",invalid);}}
    private static String boundedText(JsonNode n,String f,int min,int max){require(n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);
        String v=n.get(f).stringValue();int count=v.codePointCount(0,v.length());require(count>=min&&count<=max,"PAYLOAD_VALUE_INVALID:"+f);
        for(int i=0;i<v.length();){char c=v.charAt(i);if(Character.isSurrogate(c)){require(Character.isHighSurrogate(c)&&i+1<v.length()&&Character.isLowSurrogate(v.charAt(i+1)),"PAYLOAD_VALUE_INVALID:"+f);i+=2;continue;}require(c=='\t'||c=='\n'||c=='\r'||!Character.isISOControl(c),"PAYLOAD_VALUE_INVALID:"+f);i++;}return v;}
    private static UUID payloadUuid(JsonNode n,String f){require(n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return uuid(n.get(f).stringValue(),f);}
    private static byte[] payloadHex(JsonNode n,String f){require(n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return hex(n.get(f).stringValue(),f);}
    private static Instant payloadInstant(JsonNode n,String f){require(n.get(f).isString(),"PAYLOAD_TYPE_INVALID:"+f);return instant(n.get(f).stringValue(),f);}
    private static void text(JsonNode n,String f,String v){require(n.get(f).isString()&&v.equals(n.get(f).stringValue()),"PAYLOAD_VALUE_INVALID:"+f);}
    private static void fixed(Map<String,String> p,String f,String v){require(v.equals(p.get(f)),"ENVELOPE_VALUE_INVALID:"+f);}
    private static UUID uuid(String v,String f){require(v!=null&&UUID_TEXT.matcher(v).matches(),"UUID_FORMAT_INVALID:"+f);try{UUID id=UUID.fromString(v);require(id.toString().equals(v),"UUID_FORMAT_INVALID:"+f);return id;}catch(RuntimeException x){throw bad("UUID_FORMAT_INVALID:"+f,x);}}
    private static Instant instant(String v,String f){require(v!=null&&MICROS.matcher(v).matches(),"UTC_FORMAT_INVALID:"+f);try{return Instant.parse(v);}catch(RuntimeException x){throw bad("UTC_FORMAT_INVALID:"+f,x);}}
    private static byte[] hex(String v,String f){require(v!=null&&HEX.matcher(v).matches(),"SHA256_FORMAT_INVALID:"+f);return HexFormat.of().parseHex(v);}
    private static byte[] sha(byte[] b){try{return java.security.MessageDigest.getInstance("SHA-256").digest(b);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IncidentMessageContract.Binding[] bindings(){return new IncidentMessageContract.Binding[]{IncidentMessageContract.CREATE_REQUESTED,IncidentMessageContract.RECOVERED};}
    private static void require(boolean c,String code){if(!c)throw bad(code);}private static IncidentContractViolation bad(String c){return new IncidentContractViolation(c);}
    private static IncidentContractViolation bad(String c,Throwable x){return new IncidentContractViolation(c,x);}
}
