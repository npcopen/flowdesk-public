package com.flowdesk.ticket.approval;
import com.flowdesk.ticket.catalog.CatalogVersionClient;
import com.flowdesk.ticket.dto.CatalogWriteRequest;
import com.flowdesk.common.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
class CatalogVersionClientTest {
    /**
     * 验证目录客户端只接受认证请求所返回的已发布高风险工作流版本。
     */
    @Test void onlyAcceptsThePublishedHighRiskVersionReturnedForTheAuthenticatedRequest() throws Exception {
        UUID definition=UUID.randomUUID(), version=UUID.randomUUID();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        int[] status={200}; String[] risk={"HIGH"};
        server.createContext("/api/v1/workflow-versions/",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer same-tenant-user");
            byte[] body=("{\"data\":{\"workflowVersionId\":\""+version+"\",\"definitionId\":\""+definition+"\",\"versionNo\":2,\"status\":\"PUBLISHED\",\"condition\":{\"value\":\""+risk[0]+"\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0],body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        try (var scope = com.flowdesk.common.tenant.TenantContextScope.open(
                com.flowdesk.common.tenant.TenantContext.ofHumanJwt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))) {
            var client=new CatalogVersionClient(new WorkflowFeignTestClient("http://127.0.0.1:"+server.getAddress().getPort()));
            var binding=new CatalogWriteRequest.WorkflowBinding(definition,version,2);
            client.validate(binding,"Bearer same-tenant-user");
            assertThatThrownBy(()->client.validate(new CatalogWriteRequest.WorkflowBinding(definition,version,3),"Bearer same-tenant-user")).isInstanceOf(ApiException.class);
            risk[0]="NORMAL";assertThatThrownBy(()->client.validate(binding,"Bearer same-tenant-user")).isInstanceOf(ApiException.class);
            status[0]=404;assertThatThrownBy(()->client.validate(binding,"Bearer same-tenant-user")).isInstanceOf(ApiException.class);
            status[0]=503;assertThatThrownBy(()->client.validate(binding,"Bearer same-tenant-user")).isInstanceOf(ApiException.class).hasMessageContaining("暂时无法校验");
        } finally {server.stop(0);}
    }
}
