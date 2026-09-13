package com.flowdesk.ticket.service;

import com.flowdesk.ticket.api.CatalogWriteRequest;
import com.flowdesk.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.time.Duration;
import java.util.List;

@Component
public class CatalogVersionClient {
    private final RestClient client;
    public CatalogVersionClient(RestClient.Builder builder,
            @Value("${flowdesk.ticket.workflow.base-url:http://127.0.0.1:8083}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
    public void validate(CatalogWriteRequest.WorkflowBinding binding, String authorization) {
        try {
            var body = client.get().uri("/api/v1/workflow-versions/" + binding.workflowVersionId())
                    .header("Authorization", authorization).retrieve().body(String.class);
            var node = new tools.jackson.databind.ObjectMapper().readTree(body).path("data");
            if (!binding.workflowVersionId().toString().equals(node.path("workflowVersionId").asText())
                    || !binding.definitionId().toString().equals(node.path("definitionId").asText())
                    || binding.versionNo() != node.path("versionNo").asInt()
                    || !"PUBLISHED".equals(node.path("status").asText())
                    || !"HIGH".equals(node.path("condition").path("value").asText())) throw invalid();
        } catch (ApiException e) { throw e; }
        catch (HttpClientErrorException e) { throw invalid(); }
        catch (RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_VERSION_UNAVAILABLE",
                    "暂时无法校验审批版本，请稍后再保存。", true);
        }
    }
    private static ApiException invalid() {
        return ApiException.unprocessable("WORKFLOW_VERSION_INVALID", "请选择本租户已发布的高风险审批版本。", List.of());
    }
}
