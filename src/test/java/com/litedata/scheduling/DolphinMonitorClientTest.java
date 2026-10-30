package com.litedata.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import com.litedata.config.PlatformProperties;
import com.litedata.service.QueryPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class DolphinMonitorClientTest {
    private HttpServer server;
    private final Queue<Reply> replies=new ArrayDeque<>();
    private final List<String> paths=new ArrayList<>();
    private final List<String> methods=new ArrayList<>(),contentTypes=new ArrayList<>(),bodies=new ArrayList<>();

    @BeforeEach void startServer() throws IOException {
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/",this::reply);
        server.start();
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void listsWorkflowInstancesWithDsPagingParametersAndSafeProjection() {
        replies.add(ok("{\"total\":7,\"totalList\":[{\"id\":41,\"workflowDefinitionCode\":88,\"name\":\"daily\",\"state\":\"SUCCESS\",\"startTime\":\"2026-10-24 01:00:00\",\"endTime\":\"2026-10-24 01:01:00\",\"runTimes\":2,\"commandParam\":\"password=secret\"}]}"));

        var page=client().instances(88,new QueryPage(2,20),"SUCCESS");

        assertEquals("/projects/9/workflow-instances",path(paths.getFirst()));
        assertEquals(Map.of("workflowDefinitionCode","88","pageNo","2","pageSize","20","stateType","SUCCESS"),query(paths.getFirst()));
        assertEquals(7,page.total());
        assertEquals(new DolphinMonitorClient.Workflow(41,88,"daily","SUCCESS","2026-10-24 01:00:00","2026-10-24 01:01:00",2),page.items().getFirst());
    }

    @Test void projectsInstanceDetailsWithoutRemoteParameters() {
        replies.add(ok("{\"id\":42,\"workflowDefinitionCode\":88,\"name\":\"run\",\"state\":\"RUNNING\",\"startTime\":\"2026-10-24 01:00:00\",\"endTime\":\"\",\"runTimes\":0,\"globalParams\":\"token=secret\",\"commandParam\":\"jdbc:mysql://user:password@host\"}"));

        var instance=client().instance(42);

        assertEquals("/projects/9/workflow-instances/42",paths.getFirst());
        assertEquals(new DolphinMonitorClient.Workflow(42,88,"run","RUNNING","2026-10-24 01:00:00","",0),instance);
        assertFalse(instance.toString().contains("secret"));
        assertFalse(instance.toString().contains("password"));
    }

    @Test void resolvesLegacyTaskIdentityThroughTheExactV2Endpoint() {
        replies.add(new Reply(200,"{\"id\":173,\"workflowInstanceId\":61,\"taskParams\":\"must-not-expose\"}"));
        assertEquals(61,client().workflowForTask(173));
        assertEquals("/v2/projects/9/task-instances/173",paths.getFirst());
        assertEquals("POST",methods.getFirst());
        assertEquals("application/json",contentTypes.getFirst());
        assertEquals("{}",bodies.getFirst());
        replies.add(new Reply(200,"{\"id\":174,\"workflowInstanceId\":61}"));
        assertThrows(IllegalStateException.class,()->client().workflowForTask(173));
    }

    @Test void rejectsTaskNodesFromAnotherWorkflowInstance() {
        replies.add(ok("{\"taskList\":[{\"id\":3,\"workflowInstanceId\":99,\"name\":\"shell\",\"state\":\"SUCCESS\"}]}"));

        var error=assertThrows(IllegalStateException.class,()->client().nodes(42));

        assertEquals("/projects/9/workflow-instances/42/tasks",paths.getFirst());
        assertTrue(error.getMessage().contains("返回格式不兼容"));
    }

    @Test void removesLogPathHeaderOnlyFromFirstLogPage() {
        replies.add(new Reply(200,"{\"code\":0,\"data\":\"[LOG-PATH] /private/path\\r\\nfirst line\\r\\nsecond line\"}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":\"[LOG-PATH] still-a-log-line\\nnext line\"}"));

        assertEquals("first line\nsecond line",client().log(7,0,100));
        assertEquals("[LOG-PATH] still-a-log-line\nnext line",client().log(7,100,100));
        assertEquals("/log/9/detail",path(paths.get(0)));
        assertEquals(Map.of("taskInstanceId","7","skipLineNum","0","limit","100"),query(paths.get(0)));
        assertEquals(Map.of("taskInstanceId","7","skipLineNum","100","limit","100"),query(paths.get(1)));
    }

    @Test void hidesRemoteBodiesForHttpBusinessAndIncompatibleResponses() {
        replies.add(new Reply(500,"{\"message\":\"password=secret\"}"));
        var http=assertThrows(IllegalStateException.class,()->client().instance(1));
        assertTrue(http.getMessage().contains("暂不可用"));
        assertFalse(http.getMessage().contains("secret"));

        replies.add(new Reply(200,"{\"code\":9001,\"msg\":\"token=secret\"}"));
        var business=assertThrows(IllegalStateException.class,()->client().instance(1));
        assertTrue(business.getMessage().contains("暂不可用"));
        assertFalse(business.getMessage().contains("secret"));

        replies.add(new Reply(200,"not-json"));
        var incompatible=assertThrows(IllegalStateException.class,()->client().instance(1));
        assertTrue(incompatible.getMessage().contains("返回格式不兼容"));
    }

    private DolphinMonitorClient client() {
        return new DolphinMonitorClient(new PlatformProperties("key","password","internal",
                new PlatformProperties.DolphinScheduler("http://localhost:"+server.getAddress().getPort(),"safe-token",9,"tenant"),null));
    }

    private Reply ok(String data) { return new Reply(200,"{\"code\":0,\"data\":"+data+"}"); }

    private String path(String value) { return java.net.URI.create(value).getPath(); }

    private Map<String,String> query(String value) {
        Map<String,String> result=new HashMap<>();
        String raw=java.net.URI.create(value).getRawQuery();
        for(String pair:raw.split("&")) {
            String[] parts=pair.split("=",2);
            result.put(parts[0],parts.length==2?parts[1]:"");
        }
        return result;
    }

    private void reply(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().toString());
        methods.add(exchange.getRequestMethod()); contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        Reply response=replies.remove();
        byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(response.status(),bytes.length);
        try(OutputStream output=exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Reply(int status,String body) {}
}
