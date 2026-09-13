package com.flowdesk.alert.api;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

final class AlertQueryParams {
    private AlertQueryParams(){}
    static void validate(MultiValueMap<String,String> params,Set<String> allowed){
        for(String key:params.keySet()){
            if(!allowed.contains(key))throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER",
                    "请求包含未声明 query 参数。");
            var values=params.get(key);if(values==null||values.size()!=1||values.get(0)==null
                    ||values.get(0).isBlank())throw ApiException.badRequest("INVALID_QUERY_PARAMETER",
                    key+" 必须恰好出现一次且非空。");
        }
    }
    static Instant instant(String value){if(value==null)return null;try{return Instant.parse(value);}
        catch(RuntimeException invalid){throw ApiException.badRequest("INVALID_QUERY_PARAMETER","时间参数无效。");}}
    static UUID uuid(String value){if(value==null)return null;try{return UUID.fromString(value);}
        catch(RuntimeException invalid){throw ApiException.badRequest("INVALID_QUERY_PARAMETER","UUID 参数无效。");}}
    static int pageSize(String value){if(value==null)return 20;try{int size=Integer.parseInt(value);
        if(size<1||size>100)throw new NumberFormatException();return size;}
        catch(NumberFormatException invalid){throw ApiException.badRequest("INVALID_QUERY_PARAMETER","pageSize 必须为 1..100。");}}
}
