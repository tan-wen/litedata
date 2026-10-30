package com.litedata.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.litedata.config.PlatformProperties;
import com.litedata.model.Pipeline;
import com.litedata.store.PlatformMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class DolphinSchedulerClientTest {
    private HttpServer server;
    private PlatformMapper mapper;
    private final Queue<Reply> replies=new ArrayDeque<>();
    private final List<String> requests=new ArrayList<>();
    private final List<String> definitionNames=new ArrayList<>();
    private final List<String> definitionRequests=new ArrayList<>();

    @BeforeEach void startServer() throws IOException {
        mapper=mock(PlatformMapper.class);
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/",this::reply);
        server.start();
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void reports401WithoutLeakingRemoteResponse() {
        Pipeline pipeline=pipeline(100L);
        when(mapper.lockPipeline(1L)).thenReturn(pipeline);
        replies.add(new Reply(401,"{\"message\":\"token=top-secret\"}"));

        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().run(1L));

        assertTrue(error.getMessage().contains("发布工作流失败（HTTP 401）"));
        assertTrue(error.getMessage().contains("deploy/start.ps1"));
        assertTrue(error.getMessage().contains("deploy/start.sh"));
        assertTrue(!error.getMessage().contains("top-secret"));
        verify(mapper).lockPipeline(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void reports403ForTaskCodePermissionFailure() {
        when(mapper.lockPipeline(1L)).thenReturn(pipeline(null));
        replies.add(new Reply(403,"{\"message\":\"project private\"}"));

        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().run(1L));

        assertTrue(error.getMessage().contains("获取任务编号失败（HTTP 403）"));
        assertTrue(error.getMessage().contains("令牌权限"));
        verify(mapper).lockPipeline(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void reports500ForStartAndDoesNotWriteCompletionState() {
        when(mapper.lockPipeline(1L)).thenReturn(pipeline(100L));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        replies.add(new Reply(500,"{\"message\":\"internal details\"}"));

        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().run(1L));

        assertTrue(error.getMessage().contains("启动工作流失败（HTTP 500）"));
        verify(mapper).lockPipeline(1L);
        verify(mapper).rememberWorkflow("pipeline",1L,100L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void reportsBusinessCodeWithoutLeakingRemoteMessage() {
        when(mapper.lockPipeline(1L)).thenReturn(pipeline(null));
        replies.add(new Reply(200,"{\"code\":0,\"data\":[7]}"));
        replies.add(new Reply(200,"{\"code\":10001,\"msg\":\"token=top-secret\"}"));

        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().run(1L));

        assertTrue(error.getMessage().contains("创建工作流失败（业务码 10001）"));
        assertTrue(error.getMessage().contains("任务插件校验失败"));
        assertTrue(!error.getMessage().contains("top-secret"));
        verify(mapper).lockPipeline(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void createsPublishesAndStartsWorkflowInOrder() {
        Pipeline pipeline=pipeline(null);
        when(mapper.lockPipeline(1L)).thenReturn(pipeline);
        replies.add(new Reply(200,"{\"code\":0,\"data\":[7]}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":{\"code\":101}}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));

        client().run(1L);

        assertEquals(List.of(
                "GET /projects/9/task-definition/gen-task-codes?genNum=1",
                "POST /projects/9/workflow-definition",
                "POST /projects/9/workflow-definition/101/release",
                "POST /projects/9/executors/start-workflow-instance"),requests);
        verify(mapper).lockPipeline(1L);
        verify(mapper).definition(1L,101L);
        verify(mapper).rememberWorkflow("pipeline",1L,101L);
        verifyNoMoreInteractions(mapper);
        assertTrue(definitionRequests.getFirst().contains("--workflow-instance-id ${system.workflow.instance.id}"));
        assertTrue(definitionRequests.getFirst().contains("--task-instance-id ${system.task.instance.id}"));
    }

    @Test void reportsInvalidFormatWhenTaskCodeDataIsNotAList() {
        when(mapper.lockPipeline(1L)).thenReturn(pipeline(null));
        replies.add(new Reply(200,"{\"code\":0,\"data\":{}}"));

        IllegalStateException error=assertThrows(IllegalStateException.class,()->client().run(1L));

        assertTrue(error.getMessage().contains("获取任务编号失败（返回格式无效）"));
        verify(mapper).lockPipeline(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void publishesThenOnlinesExistingSchedule() {
        Pipeline pipeline=pipeline(101L);
        pipeline.setScheduleId(55);
        when(mapper.lockPipeline(1L)).thenReturn(pipeline);
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));

        client().schedule(1L);

        assertEquals(List.of(
                "POST /projects/9/workflow-definition/101/release",
                "POST /projects/9/schedules/55/online"),requests);
        verify(mapper).lockPipeline(1L);
        verify(mapper).schedule(1L,55,"SCHEDULED");
        verify(mapper).rememberWorkflow("pipeline",1L,101L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void createsANewWorkflowAndScheduleWhenAPausedFrequencyChangeClearedEngineIds() {
        Pipeline pipeline=pipeline(null);
        pipeline.setCronExpression("0 */5 * * * ?");
        when(mapper.lockPipeline(1L)).thenReturn(pipeline);
        replies.add(new Reply(200,"{\"code\":0,\"data\":[7]}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":{\"code\":101}}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":55}"));
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));

        client().schedule(1L);

        assertEquals(List.of(
                "GET /projects/9/task-definition/gen-task-codes?genNum=1",
                "POST /projects/9/workflow-definition",
                "POST /projects/9/workflow-definition/101/release",
                "POST /projects/9/schedules",
                "POST /projects/9/schedules/55/online"),requests);
        verify(mapper).definition(1L,101L);
        verify(mapper).rememberWorkflow("pipeline",1L,101L);
        verify(mapper).schedule(1L,55,"SCHEDULE_PENDING");
        verify(mapper).schedule(1L,55,"SCHEDULED");
    }

    @Test void replacementWorkflowNamesDoNotCollideWithPausedDefinitions() {
        for(int revision=1;revision<=2;revision++) {
            when(mapper.lockPipeline(1L)).thenReturn(pipeline(null));
            replies.add(new Reply(200,"{\"code\":0,\"data\":["+revision+"]}"));
            replies.add(new Reply(200,"{\"code\":0,\"data\":{\"code\":"+(100+revision)+"}}"));
            replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
            replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
            client().run(1L);
        }
        assertEquals(2,new HashSet<>(definitionNames).size());
        assertTrue(definitionNames.stream().allMatch(name->name.startsWith("platform-pipeline-1-")));
    }

    @Test void pausesFutureSchedulingWithoutReleasingAnActiveRunAndIsIdempotent() {
        Pipeline pipeline=pipeline(101L); pipeline.setScheduleId(55); pipeline.setStatus("SCHEDULED"); pipeline.setActiveRunId(7L);
        when(mapper.lockPipeline(1L)).thenReturn(pipeline);
        replies.add(new Reply(200,"{\"code\":0,\"data\":null}"));
        client().pause(1L);
        assertEquals(7L,pipeline.getActiveRunId());
        pipeline.setStatus("PAUSED"); client().pause(1L);
        assertEquals(List.of("POST /projects/9/schedules/55/offline"),requests);
        verify(mapper,times(2)).schedule(1L,55,"PAUSED");
        verify(mapper,never()).activeRun(anyLong(),any());
    }

    private DolphinSchedulerClient client() {
        return new DolphinSchedulerClient(mapper,new PlatformProperties("key","password","internal",
                new PlatformProperties.DolphinScheduler("http://localhost:"+server.getAddress().getPort(),"safe-token",9,"tenant"),null),mock(PipelineService.class));
    }

    private Pipeline pipeline(Long definitionCode) {
        Pipeline pipeline=new Pipeline();
        pipeline.setId(1L); pipeline.setName("orders"); pipeline.setDefinitionCode(definitionCode);
        return pipeline;
    }

    private void reply(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
        Reply response=replies.remove();
        byte[] body=response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        try(var requestBody=exchange.getRequestBody(); var responseBody=exchange.getResponseBody()) {
            String requestText=new String(requestBody.readAllBytes(),StandardCharsets.UTF_8);
            if(exchange.getRequestURI().getPath().equals("/projects/9/workflow-definition"))
                definitionRequests.add(java.net.URLDecoder.decode(requestText,StandardCharsets.UTF_8));
            if(exchange.getRequestURI().getPath().equals("/projects/9/workflow-definition"))
                for(String part:requestText.split("&")) if(part.startsWith("name="))
                    definitionNames.add(java.net.URLDecoder.decode(part.substring(5),StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(response.status(),body.length);
            responseBody.write(body);
        } finally { exchange.close(); }
    }

    private record Reply(int status,String body) {}
}
