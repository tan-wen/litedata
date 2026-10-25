package com.litedata.mcp;

import com.litedata.security.UserApiKeyService;
import com.litedata.store.PlatformMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Transport credentials are resolved before reading any untrusted RPC body. */
@Component
public class McpSecurityFilter extends OncePerRequestFilter {
    public static final String IDENTITY=McpSecurityFilter.class.getName()+".identity";
    private final UserApiKeyService keys;
    private final PlatformMapper platform;
    public McpSecurityFilter(UserApiKeyService keys,PlatformMapper platform) { this.keys=keys; this.platform=platform; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/mcp") && !path.startsWith("/mcp/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        if(!sameOrigin(request)) { reject(response,403,"Origin 不受信任"); return; }
        var headers=Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        String authorization=headers.size()==1?headers.getFirst():null;
        if(request.getQueryString()!=null || authorization==null || !authorization.regionMatches(true,0,"Bearer ",0,7)) {
            platform.audit("anonymous","MCP_AUTH","/mcp","DENIED");
            reject(response,401,"需要有效的 MCP Bearer 凭证"); return;
        }
        try {
            var identity=keys.authenticate(authorization.substring(7));
            request.setAttribute(IDENTITY,identity);
            keys.touch(identity);
        } catch(ResponseStatusException exception) {
            platform.audit("anonymous","MCP_AUTH","/mcp","DENIED");
            reject(response,401,"MCP 凭证无效或已过期"); return;
        }
        chain.doFilter(request,response);
    }
    private boolean sameOrigin(HttpServletRequest request) {
        String origin=request.getHeader(HttpHeaders.ORIGIN);
        if(origin==null) return true;
        try {
            URI uri=URI.create(origin);
            int port=uri.getPort()<0?("https".equalsIgnoreCase(uri.getScheme())?443:80):uri.getPort();
            return uri.getScheme()!=null && uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                    && (uri.getPath()==null || uri.getPath().isEmpty())
                    && uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && uri.getHost().equalsIgnoreCase(request.getServerName()) && port==request.getServerPort();
        } catch(IllegalArgumentException exception) { return false; }
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);
        if(status==401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer realm=\"litedata-mcp\"");
        response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\""+message+"\"}");
    }
}
