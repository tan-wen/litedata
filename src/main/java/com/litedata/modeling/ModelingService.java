package com.litedata.modeling;

import com.litedata.model.*;
import com.litedata.store.ModelMapper;
import com.litedata.store.MetricMapper;
import com.litedata.store.PlatformMapper;
import com.litedata.store.SqlTaskMapper;
import com.litedata.service.QueryPage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelingService {
    public record Preview(DataModel model, String ddl, String hash) {}
    public record ExecutionResult(boolean success, String message) {}
    private final ModelMapper mapper;
    private final PlatformMapper platform;
    private final DorisDdl ddl;
    private final DorisModelGateway gateway;
    private final SqlTaskMapper sqlTasks;
    private final MetricMapper metrics;

    public ModelingService(ModelMapper mapper, PlatformMapper platform, DorisDdl ddl, DorisModelGateway gateway, SqlTaskMapper sqlTasks, MetricMapper metrics) {
        this.mapper = mapper; this.platform = platform; this.ddl = ddl; this.gateway = gateway;
        this.sqlTasks = sqlTasks;
        this.metrics = metrics;
    }

    public List<DataModel> list(String keyword, String layer) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return mapper.models().stream().filter(m -> layer == null || layer.isBlank() || layer.equals(m.getLayer()))
                .filter(m -> (m.getTableName() + " " + m.getName() + " " + m.getSubjectArea()).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    public long count(String keyword, String layer) {
        return mapper.countModels(query(keyword), normalizedLayer(layer));
    }

    public List<DataModel> list(String keyword, String layer, QueryPage page) {
        return mapper.modelPage(query(keyword), normalizedLayer(layer), page.pageSize(), page.offset());
    }

    public DataModel require(long id) {
        return withFields(requireModel(mapper.model(id)));
    }

    @Transactional
    public long save(Long id, DataModel input, String actor) {
        ddl.generate(input);
        if (id == null) {
            input.setId(null);
            mapper.insert(input);
            id = input.getId();
        } else {
            var current = requireModel(mapper.lockModel(id));
            checkVersion(current, input.getVersion());
            if (!current.getTableName().equals(input.getTableName()))
                throw new IllegalArgumentException("模型保存后表名不可更改，请新建模型");
            input.setId(id);
            mapper.update(input);
            mapper.deleteFields(id);
        }
        for (int i = 0; i < input.getFields().size(); i++) mapper.insertField(id, i + 1, input.getFields().get(i));
        platform.audit(actor, "MODEL_SAVE", Long.toString(id), "SUCCESS");
        return id;
    }

    public Preview preview(long id) {
        var model = require(id);
        return preview(model);
    }

    @Transactional
    public void archive(long id, int version, String actor) {
        sqlTasks.lockGraph();
        metrics.lockGraph();
        var model = requireModel(mapper.lockModel(id));
        checkVersion(model, version);
        if (sqlTasks.modelReferences(id)>0) throw new IllegalArgumentException("模型仍关联数据加工，请先归档加工任务");
        if (metrics.modelReferences(id)>0) throw new IllegalArgumentException("模型仍关联指标，请先归档相关指标");
        mapper.archive(id);
        platform.audit(actor, "MODEL_ARCHIVE", Long.toString(id), "SUCCESS");
    }

    /** Serialize metadata edits and execution against the same model row. No ALTER/DROP is generated. */
    @Transactional
    public ExecutionResult physicalize(long id, int version, String previewHash, String actor) {
        var model = withFields(requireModel(mapper.lockModel(id)));
        checkVersion(model, version);
        var preview = preview(model);
        if (!preview.hash().equals(previewHash)) throw new IllegalArgumentException("DDL 预览已变化，请刷新页面重新确认");
        try {
            gateway.create(preview.ddl());
            var comparison = SchemaComparison.compare(model, gateway.inspect(model.getTableName()));
            if (!comparison.matches()) {
                record(model, "CREATE", preview.ddl(), actor, "FAILED", "建表请求已执行，但结构核对未通过，请执行结构比对");
                mapper.physicalVersion(id, null);
                return new ExecutionResult(false, "建表请求已执行，但结构核对未通过，请执行结构比对");
            }
            mapper.physicalVersion(id, model.getVersion());
            record(model, "CREATE", preview.ddl(), actor, "SUCCESS", "已建表并核对结构");
            return new ExecutionResult(true, "Doris 表已创建，字段、数据模型、副本、分区和分桶核对通过");
        } catch (SQLException | IllegalStateException exception) {
            String message = safeFailure(exception);
            record(model, "CREATE", preview.ddl(), actor, "FAILED", message);
            return new ExecutionResult(false, message);
        }
    }

    @Transactional
    public SchemaComparison compare(long id, int version, String actor) {
        var model = withFields(requireModel(mapper.lockModel(id)));
        checkVersion(model, version);
        String generated = ddl.generate(model);
        try {
            var comparison = SchemaComparison.compare(model, gateway.inspect(model.getTableName()));
            mapper.physicalVersion(id, comparison.matches() ? model.getVersion() : null);
            record(model, "COMPARE", generated, actor, comparison.matches() ? "SUCCESS" : "DIFF",
                    comparison.matches() ? "字段、键列、聚合函数、数据模型、默认副本、分区和分桶一致" : "存在结构差异，请查看比对结果");
            return comparison;
        } catch (SQLException | IllegalStateException exception) {
            String message = safeFailure(exception);
            record(model, "COMPARE", generated, actor, "FAILED", message);
            return new SchemaComparison(false, List.of(new SchemaComparison.Difference("连接 / 读取", "可读取物理结构", message, false)));
        }
    }

    public List<ModelExecution> executions(long id) {
        var executions = mapper.executions(id);
        for (var execution : executions) if (execution.getCreatedAt() != null)
            execution.setCreatedAt(execution.getCreatedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        return executions;
    }

    private Preview preview(DataModel model) {
        String generated = ddl.generate(model);
        try {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((model.getId() + ":" + model.getVersion() + ":" + generated).getBytes(StandardCharsets.UTF_8)));
            return new Preview(model, generated, hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private DataModel withFields(DataModel model) { model.setFields(mapper.fields(model.getId())); return model; }
    private static String query(String keyword) { return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT); }
    private static String normalizedLayer(String layer) { return layer == null || layer.isBlank() ? "" : layer; }
    private static DataModel requireModel(DataModel model) {
        if (model == null) throw new IllegalArgumentException("模型不存在或已归档");
        return model;
    }
    private static void checkVersion(DataModel model, int version) {
        if (model.getVersion() != version) throw new IllegalArgumentException("模型已被修改，请刷新页面后重试");
    }
    private void record(DataModel model, String operation, String generated, String actor, String status, String message) {
        var execution = new ModelExecution();
        execution.setModelId(model.getId()); execution.setModelVersion(model.getVersion());
        execution.setOperation(operation); execution.setTargetTable("warehouse." + model.getTableName());
        execution.setDdl(generated); execution.setActor(actor); execution.setStatus(status); execution.setMessage(message);
        mapper.execution(execution);
        platform.audit(actor, "MODEL_" + operation, model.getId() + ":v" + model.getVersion(), status);
    }
    private static String safeFailure(Exception exception) {
        if (exception instanceof SQLException sql)
            return "Doris 操作未确认成功（SQLState=" + Objects.toString(sql.getSQLState(), "unknown").replaceAll("[^A-Za-z0-9]", "")
                    + "，code=" + sql.getErrorCode() + "）。请检查表是否已存在、账号权限及连接，并先比对结构再重试";
        return "Doris 建模连接未就绪，请检查建表账号配置与网络后重试";
    }
}
