package com.litedata.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.litedata.security.UserApiKeyService;
import com.litedata.security.UserApiKeyService.Identity;
import com.litedata.store.PlatformMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class McpSecurityFilterTest {
    @Test void malformedAndForeignOriginsAreRejectedBeforeAuthentication() throws Exception {
        var keys=mock(UserApiKeyService.class);
        var platform=mock(PlatformMapper.class);
        var chain=mock(FilterChain.class);
        var filter=new McpSecurityFilter(keys,platform);
        for(String origin:new String[]{"//localhost","null","http://[","http://localhost/path","https://localhost","http://other.invalid"}) {
            var request=new MockHttpServletRequest("POST","/mcp");
            request.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(keys,chain);
        var identity=new Identity(1,2,"viewer","VIEWER","services:read");
        when(keys.authenticate("test-key")).thenReturn(identity);
        var request=new MockHttpServletRequest("POST","/mcp");
        request.addHeader("Origin","http://localhost");
        request.addHeader("Authorization","Bearer test-key");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,chain);
        verify(chain).doFilter(request,response);
        assertThat(request.getAttribute(McpSecurityFilter.IDENTITY)).isEqualTo(identity);
    }
}
