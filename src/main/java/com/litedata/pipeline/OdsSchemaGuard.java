package com.litedata.pipeline;

import com.litedata.data.*;
import com.litedata.model.Pipeline;
import com.litedata.modeling.*;
import com.litedata.store.ModelMapper;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
public class OdsSchemaGuard {
    private final SourceService sources;
    private final SourceSchemaInspector inspector;
    private final ModelMapper models;
    private final ModelingService modeling;
    private final DorisModelGateway gateway;
    public OdsSchemaGuard(SourceService sources,SourceSchemaInspector inspector,ModelMapper models,ModelingService modeling,DorisModelGateway gateway) {
        this.sources=sources; this.inspector=inspector; this.models=models; this.modeling=modeling; this.gateway=gateway;
    }
    public void validate(Pipeline pipeline) {
        if(pipeline.getSourceSchemaHash()==null) return;
        var source=sources.require(pipeline.getSourceId());
        var schema=inspector.inspect(source.getId(),pipeline.getSourceTable());
        if(!pipeline.getSourceSchemaHash().equals(OdsSchemaPolicy.sourceHash(source,pipeline.getSourceTable(),schema)))
            throw new IllegalArgumentException("源表结构已变化，已阻止同步；请核对字段、类型、主键并重新预览接入新目标表，原 ODS 不会自动修改");
        var registered=models.modelByTable(pipeline.getTargetTable());
        if(registered==null) throw new IllegalArgumentException("自动贴源的 ODS 模型不存在，请先核对模型");
        var model=modeling.preview(registered.getId()).model();
        if(!pipeline.getTargetSchemaHash().equals(OdsSchemaPolicy.targetHash(model)))
            throw new IllegalArgumentException("自动贴源的 ODS 模型字段已变化，请核对来源结构并重新预览接入新目标表");
        try {
            if(!SchemaComparison.compare(model,gateway.inspect(model.getTableName())).matches())
                throw new IllegalArgumentException("ODS 物理表与登记结构不一致，请先在数据建模中核对；同步水位不会推进");
        } catch(SQLException exception) { throw new IllegalArgumentException("无法核对 ODS 物理表，请检查 Doris 连接与结构读取权限"); }
    }
}
