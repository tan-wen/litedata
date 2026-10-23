package com.litedata.transform;

import static org.junit.jupiter.api.Assertions.*;
import com.litedata.config.PlatformProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class DolphinWorkflowClientTest {
    private HttpServer server; private final Queue<Reply> replies=new ArrayDeque<>(); private final List<Request> requests=new ArrayList<>();
    private final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws IOException { server=HttpServer.create(new InetSocketAddress("localhost",0),0); server.createContext("/",this::reply); server.start(); }
    @AfterEach void stop() { server.stop(0); }

    @Test void createsDiamondDagWithGeneratedCodesAndRootRelations() throws Exception {
        replies.add(new Reply(200,"{\"code\":0,\"data\":[11,12,13,14]}")); replies.add(new Reply(200,"{\"code\":0,\"data\":{\"code\":88}}"));
        long workflow=client().create("daily","desc",List.of(new DolphinWorkflowClient.Node("a","A","echo a",List.of()),
                new DolphinWorkflowClient.Node("b","B","echo b",List.of("a")),new DolphinWorkflowClient.Node("c","C","echo c",List.of("a")),
                new DolphinWorkflowClient.Node("d","D","echo d",List.of("b","c"))));
        assertEquals(88,workflow); assertEquals("/projects/9/task-definition/gen-task-codes?genNum=4",requests.getFirst().path());
        Map<String,String> form=form(requests.get(1).body());
        assertEquals("205",form.get("timeout"));
        List<?> tasks=json.readValue(form.get("taskDefinitionJson"),List.class); assertEquals(4,tasks.size());
        List<Map<?,?>> relations=json.readValue(form.get("taskRelationJson"),List.class); assertEquals(5,relations.size());
        assertTrue(relations.stream().anyMatch(r->Objects.equals(r.get("preTaskCode"),0) && Objects.equals(r.get("preTaskVersion"),0) && Objects.equals(r.get("postTaskCode"),11)));
        assertTrue(relations.stream().anyMatch(r->Objects.equals(r.get("preTaskCode"),11) && Objects.equals(r.get("preTaskVersion"),1) && Objects.equals(r.get("postTaskCode"),12)));
        assertTrue(relations.stream().anyMatch(r->Objects.equals(r.get("preTaskCode"),11) && Objects.equals(r.get("postTaskCode"),13)));
        assertTrue(relations.stream().anyMatch(r->Objects.equals(r.get("preTaskCode"),12) && Objects.equals(r.get("postTaskCode"),14)));
        assertTrue(relations.stream().anyMatch(r->Objects.equals(r.get("preTaskCode"),13) && Objects.equals(r.get("postTaskCode"),14)));
    }
    @Test void rejectsBadResponseWithoutLeakingSecret() {
        replies.add(new Reply(200,"{\"code\":0,\"data\":[1]}")); replies.add(new Reply(500,"{\"msg\":\"token=secret\"}"));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().create("x","",List.of(new DolphinWorkflowClient.Node("x","X","echo x",List.of()))));
        assertTrue(error.getMessage().contains("创建工作流失败（HTTP 500）")); assertFalse(error.getMessage().contains("secret"));
    }
    @Test void rejectsWrongTaskCodeCountAndBusinessResponseWithoutLeakingSecret() {
        replies.add(new Reply(200,"{\"code\":0,\"data\":[1]}"));
        IllegalStateException count=assertThrows(IllegalStateException.class,()->client().create("x","",List.of(
                new DolphinWorkflowClient.Node("a","A","echo a",List.of()),new DolphinWorkflowClient.Node("b","B","echo b",List.of()))));
        assertTrue(count.getMessage().contains("获取任务编号失败（返回格式无效）"));

        replies.add(new Reply(200,"{\"code\":9001,\"msg\":\"token=secret\"}"));
        IllegalStateException business=assertThrows(IllegalStateException.class,()->client().create("x","",List.of(new DolphinWorkflowClient.Node("x","X","echo x",List.of()))));
        assertTrue(business.getMessage().contains("获取任务编号失败（业务码 9001）")); assertFalse(business.getMessage().contains("secret"));
    }
    @Test void publishesBeforeStartingWhenCalledInOrder() {
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}")); replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        DolphinWorkflowClient workflow=client(); workflow.publish(88); workflow.start(88);
        assertEquals(List.of("/projects/9/workflow-definition/88/release","/projects/9/executors/start-workflow-instance"),requests.stream().map(Request::path).toList());
    }
    @Test void createsScheduleThenChangesState() throws Exception {
        replies.add(new Reply(200,"{\"code\":0,\"data\":{\"id\":77}}")); replies.add(new Reply(200,"{\"code\":0,\"data\":null}")); replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        assertEquals(77,client().createSchedule(88,"0 0 2 * * ?")); client().online(77); client().offline(77);
        assertEquals(List.of("/projects/9/schedules","/projects/9/schedules/77/online","/projects/9/schedules/77/offline"),requests.stream().map(Request::path).toList());
        Map<String,String> form=form(requests.getFirst().body()); Map<?,?> schedule=json.readValue(form.get("schedule"),Map.class);
        assertEquals("Asia/Shanghai",schedule.get("timezoneId")); assertEquals("2099-12-31 23:59:59",schedule.get("endTime"));
    }
    private DolphinWorkflowClient client() { return new DolphinWorkflowClient(new PlatformProperties("k","p","i",new PlatformProperties.DolphinScheduler("http://localhost:"+server.getAddress().getPort(),"safe-token",9,"tenant"),null)); }
    private Map<String,String> form(String value) { Map<String,String> result=new HashMap<>(); for(String pair:value.split("&")) { String[] p=pair.split("=",2); result.put(java.net.URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==1?"":java.net.URLDecoder.decode(p[1],StandardCharsets.UTF_8)); } return result; }
    private void reply(HttpExchange exchange) throws IOException { String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); requests.add(new Request(exchange.getRequestURI().toString(),body)); Reply answer=replies.remove(); byte[] bytes=answer.body().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(answer.status(),bytes.length); try(OutputStream output=exchange.getResponseBody()) { output.write(bytes); } }
    private record Reply(int status,String body) {} private record Request(String path,String body) {}
}
