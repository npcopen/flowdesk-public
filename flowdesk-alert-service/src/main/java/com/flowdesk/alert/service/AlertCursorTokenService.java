package com.flowdesk.alert.service;

import com.flowdesk.common.web.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Tenant/scope-bound opaque cursor for API-050 and API-052. */
@Component
public final class AlertCursorTokenService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final byte[] key;
    public AlertCursorTokenService(CursorProperties properties){
        try{key=Base64.getDecoder().decode(properties.hmacKey());}
        catch(RuntimeException invalid){throw new IllegalStateException("Alert cursor key is invalid",invalid);}
        if(key.length<32)throw new IllegalStateException("Alert cursor key is not configured");
    }
    public String encode(UUID tenant,String scope,int size,String sort,InstantCursor value){
        try{
            Map<String,Object> map=new LinkedHashMap<>();map.put("tenant",tenant.toString());
            map.put("scope",scope);map.put("pageSize",size);map.put("sort",sort);
            map.put("lastValue",value.value());map.put("lastId",value.id().toString());
            String body=Base64.getUrlEncoder().withoutPadding().encodeToString(
                    JSON.writeValueAsBytes(map));return body+"."+mac(body);
        }catch(Exception failure){throw new IllegalStateException("Unable to encode Alert cursor",failure);}
    }
    public InstantCursor decode(String token,UUID tenant,String scope,int size,String sort){
        if(token==null)return null;
        try{
            int dot=token.lastIndexOf('.');if(dot<=0)throw invalid();
            String body=token.substring(0,dot),supplied=token.substring(dot+1);
            if(!MessageDigest.isEqual(mac(body).getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII)))throw invalid();
            @SuppressWarnings("unchecked") Map<String,Object> map=JSON.readValue(
                    Base64.getUrlDecoder().decode(body),Map.class);
            if(!tenant.toString().equals(map.get("tenant"))||!scope.equals(map.get("scope"))
                    ||!Integer.valueOf(size).equals(map.get("pageSize"))||!sort.equals(map.get("sort")))throw invalid();
            return new InstantCursor(String.valueOf(map.get("lastValue")),
                    UUID.fromString(String.valueOf(map.get("lastId"))));
        }catch(ApiException expected){throw expected;}
        catch(Exception invalid){throw invalid();}
    }
    private String mac(String value)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(
                mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));}
    private static ApiException invalid(){return ApiException.badRequest("CURSOR_INVALID",
            "pageToken 无效或与当前 Alert 查询作用域不匹配。");}
    public record InstantCursor(String value,UUID id){}
    @ConfigurationProperties(prefix="flowdesk.security.cursor-token")
    public static class CursorProperties{private String hmacKey="";
        public String hmacKey(){return hmacKey;}public void setHmacKey(String value){hmacKey=value;}}
}
