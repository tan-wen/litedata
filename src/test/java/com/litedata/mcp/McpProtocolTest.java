package com.litedata.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.litedata.security.UserApiKeyService.Identity;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class McpProtocolTest {
    private final McpToolService tools=mock(McpToolService.class);
    private final McpController controller=new McpController(JsonMapper.builder().build(),tools);

    private MockHttpServletRequest request(String body) {
        var request=new MockHttpServletRequest("POST","/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setAttribute(McpSecurityFilter.IDENTITY,new Identity(1,2,"reader","VIEWER","services:read"));
        return request;
    }
    @Test void malformedOversizedBatchAndInvalidIdsNeverReachTools() throws Exception {
        for(String body:new String[]{"{", "[]", "", "null", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1.2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":[]}"}) {
            assertThat(controller.rpc(request(body)).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(controller.rpc(request(" ".repeat(65537))).getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(tools);
    }
    @Test void notificationCannotExecuteSideEffectsAndVersionHeaderIsValidated() throws Exception {
        assertThat(controller.rpc(request("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"run_pipeline\",\"arguments\":{\"pipelineId\":1}}}")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.rpc(request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).getStatusCode().value()).isEqualTo(202);
        var invalid=request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        invalid.addHeader("MCP-Protocol-Version","invalid");
        assertThat(controller.rpc(invalid).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(tools);
    }
    @Test void initializeNegotiatesAndUnknownMethodsUseProtocolErrors() throws Exception {
        var response=controller.rpc(request("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"future\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body=(Map<?,?>)response.getBody();
        assertThat(((Map<?,?>)body.get("result")).get("protocolVersion")).isEqualTo("2025-11-25");
        var unknown=(Map<?,?>)controller.rpc(request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown\"}")).getBody();
        assertThat(((Map<?,?>)unknown.get("error")).get("code")).isEqualTo(-32601);
    }
}
